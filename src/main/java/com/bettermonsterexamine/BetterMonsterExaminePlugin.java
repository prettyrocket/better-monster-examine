package com.bettermonsterexamine;

import javax.inject.Inject;
import javax.swing.SwingUtilities;

import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.Collections;

import com.bettermonsterexamine.loot.DropPageService;
import com.bettermonsterexamine.loot.DropsCard;
import com.bettermonsterexamine.loot.ItemIdService;
import com.google.gson.Gson;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PluginMessage;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;

@Slf4j
@PluginDescriptor(
		name = "Better Monster Examine",
		description = "Search any monster and view its full wiki-style combat stats",
		tags = {"npc", "stats", "examine", "search", "defensive", "weakness", "elemental", "bestiary", "monster", "wiki", "dps"}
)
public class BetterMonsterExaminePlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private MonsterIcons monsterIcons;

	@Inject
	private BetterMonsterExamineConfig config;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private MonsterDataService dataService;

	@Inject
	private DropPageService dropPageService;

	@Inject
	private ItemIdService itemIdService;

	@Inject
	private ItemManager itemManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private EventBus eventBus;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private Gson gson;

	// Touched from the client thread (lifecycle/menu), the event bus (config), and the EDT
	// (panel open), so kept volatile for safe publication across those threads.
	private volatile NavigationButton navButton;
	private volatile BetterMonsterExaminePanel monsterStatsPanel;
	private volatile MonsterCardOverlay cardOverlay;
	private MouseAdapter overlayMouseListener;
	// The monster the overlay currently shows (name + version), or null when hidden; lets a
	// second Stats click on the same monster toggle the overlay off. Touched from the client
	// thread (toggle) and an OkHttp callback (wiki landing), so volatile.
	private volatile String overlayKey;
	// The monster the user explicitly closed; suppresses the panel re-feeding it (e.g. when the
	// wiki fields land) so a dismissed overlay stays closed until stats are requested again.
	private volatile String dismissedKey;
	private volatile BufferedImage titleIcon;
	// Cached on the client thread (GameTick) so the panel can read them safely off-thread (EDT).
	private volatile int playerCombatLevel = -1;
	private volatile int playerHpLevel = -1;
	private volatile int playerSlayerLevel = -1;
	private final ExamineSummaryQueue examineSummaryQueue = new ExamineSummaryQueue();
	private static final String STATS_OPTION = "Stats";
	private static final String DROPS_OPTION = "Drops";
	private static final String CONFIG_GROUP = "bettermonsterexamine";
	/** Retired in favour of the statsMenuEntry/dropsMenuEntry checkboxes; read once to migrate. */
	private static final String LEGACY_MENU_OPTIONS = "menuOptions";

	@Provides
	BetterMonsterExamineConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BetterMonsterExamineConfig.class);
	}

	@Override
	protected void startUp() throws Exception
	{
		log.info("Better Monster Examine started");
		migrateMenuOptions();
		examineSummaryQueue.clear();
		titleIcon = ImageUtil.loadImageResource(getClass(), "/icon.png");
		cardOverlay = new MonsterCardOverlay(config, monsterIcons, () -> playerCombatLevel, () -> playerHpLevel, () -> playerSlayerLevel);
		overlayManager.add(cardOverlay);

		// Route left-clicks on the overlay's tab strip to the overlay, consuming them so they
		// don't also walk the player or interact with the scene underneath.
		overlayMouseListener = new MouseAdapter()
		{
			@Override
			public MouseEvent mousePressed(MouseEvent event)
			{
				MonsterCardOverlay overlay = cardOverlay;
				if (overlay != null && event.getButton() == MouseEvent.BUTTON1)
				{
					if (overlay.closeAt(event.getX(), event.getY()))
					{
						dismissOverlay();
						event.consume();
						return event;
					}
					int tab = overlay.tabAt(event.getX(), event.getY());
					if (tab >= 0)
					{
						overlay.setActiveTab(tab);
						event.consume();
					}
				}
				return event;
			}
		};
		mouseManager.registerMouseListener(overlayMouseListener);

		if (config.enableSidePanel())
		{
			addNavBar();
		}
	}

	@Override
	protected void shutDown() throws Exception
	{
		removeNavBar();
		if (overlayMouseListener != null)
		{
			mouseManager.unregisterMouseListener(overlayMouseListener);
			overlayMouseListener = null;
		}
		if (cardOverlay != null)
		{
			overlayManager.remove(cardOverlay);
			cardOverlay = null;
		}
		overlayKey = null;
		examineSummaryQueue.clear();
		log.info("Better Monster Examine stopped");
	}

	private void addNavBar()
	{
		log.debug("Adding side panel navigation button");
		BufferedImage icon = titleIcon;
		DropsCard dropsCard = new DropsCard(itemManager, clientThread, itemIdService, config, new NotEnoughRunesLink(eventBus, pluginManager, config));
		monsterStatsPanel = new BetterMonsterExaminePanel(monsterIcons, dataService, dropPageService, itemIdService, dropsCard, config, configManager, gson, () -> playerCombatLevel, () -> playerHpLevel, () -> playerSlayerLevel, icon);
		// Mirror whatever the panel is showing into the overlay (when the overlay is a target).
		monsterStatsPanel.setSelectionListener(this::showInOverlay);
		navButton = NavigationButton.builder()
				.tooltip("Better Monster Examine")
				.icon(icon)
				// Sidebar buttons sort by priority ascending (lower = higher up). Core plugins
				// occupy 0–10 (Configuration pinned at 0); 5 places this mid-band, among the
				// normal plugins rather than down with transient raid panels.
				.priority(5)
				.panel(monsterStatsPanel)
				// Right-clicking the sidebar icon reaches the tracker without opening the panel, and
				// costs no panel space; the footer link covers people who never try a right-click.
				.popup(Collections.singletonMap("Report an issue",
					() -> LinkBrowser.browse(BetterMonsterExaminePanel.ISSUES_URL)))
				.build();

		clientToolbar.addNavigation(navButton);
	}

	private void removeNavBar()
	{
		if (navButton != null && monsterStatsPanel != null)
		{
			log.debug("Removing side panel navigation button");
			clientToolbar.removeNavigation(navButton);
			navButton = null;
			monsterStatsPanel = null;
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!event.getGroup().equals(CONFIG_GROUP))
		{
			return;
		}

		if (event.getKey().equals("enableSidePanel"))
		{
			boolean enableSidePanel = Boolean.parseBoolean(event.getNewValue());
			log.debug("Side panel {}", enableSidePanel ? "enabled" : "disabled");
			if (enableSidePanel)
			{
				// Idempotent: only add when not already present.
				if (navButton == null)
				{
					addNavBar();
				}
			}
			else
			{
				// The right-click Stats option is independently gated on enableSidePanel()
				// in onMenuEntryAdded, so simply dropping the panel is enough — no need to
				// touch statsMenuEntry (doing so left it stuck off after a re-enable).
				removeNavBar();
			}
		}
		else if (event.getKey().equals("statHighlighting"))
		{
			// Re-render the open side-panel card so the new palette/symbols apply immediately.
			// The overlay reads the palette live each frame, so it needs no nudge.
			BetterMonsterExaminePanel panel = monsterStatsPanel;
			if (panel != null)
			{
				SwingUtilities.invokeLater(panel::refresh);
			}
		}
		else if (event.getKey().equals("statsRenderTarget"))
		{
			// If the overlay is no longer a render target, hide whatever it's showing.
			if (!config.statsRenderTarget().showsOverlay())
			{
				hideOverlay();
			}
		}
		else if (event.getKey().equals("notEnoughRunesLink"))
		{
			// Clicks resolve the link live, but the rendered tooltips say which button does what —
			// re-render so they don't advertise the old arrangement.
			BetterMonsterExaminePanel panel = monsterStatsPanel;
			if (panel != null)
			{
				SwingUtilities.invokeLater(panel::refresh);
			}
		}
		else if (event.getKey().equals("enableHistory"))
		{
			// Show/hide the panel's Recent/Favorites buttons and any open list view to match.
			BetterMonsterExaminePanel panel = monsterStatsPanel;
			if (panel != null)
			{
				SwingUtilities.invokeLater(panel::onHistoryConfigChanged);
			}
		}
		else if (event.getKey().equals("examineSummaryEnabled") || event.getKey().equals("examineSummaryDetail"))
		{
			// A response still in flight should not use the mode that was active before this change.
			examineSummaryQueue.clearPending();
		}
	}

	/**
	 * Another plugin asking us to open a monster — see {@link MonsterLookupMessage} for the contract.
	 * Deliberately ungated by config: a switch we own but the sender can't read would leave a live,
	 * correct-looking button in their UI that silently does nothing.
	 */
	@Subscribe
	public void onPluginMessage(PluginMessage event)
	{
		if (!MonsterLookupMessage.NAMESPACE.equals(event.getNamespace())
			|| !MonsterLookupMessage.DISPLAY_MONSTER.equals(event.getName()))
		{
			return;
		}

		MonsterLookupMessage request = MonsterLookupMessage.of(event.getData());
		if (request == null)
		{
			log.debug("Ignoring displayMonster with neither a name nor an npcId");
			return;
		}

		// An id resolves to one variant outright; a name has to be matched, and its level (when given)
		// picks between the variants sharing it.
		MonsterData byId = request.getNpcId() == null ? null : dataService.getById(request.getNpcId());
		String name = byId != null ? byId.getName() : request.getName();
		if (name == null)
		{
			log.debug("Ignoring displayMonster for unknown npc id {}", request.getNpcId());
			return;
		}
		String version = byId != null ? byId.getVersion()
			: (request.getLevel() == null ? null : dataService.variantVersionForLevel(name, request.getLevel()));

		BetterMonsterExaminePanel panel = monsterStatsPanel;
		if (panel == null || navButton == null)
		{
			// Renders to the side panel regardless of statsRenderTarget: the overlay is for in-game NPC
			// context, and a request from another panel belongs in ours.
			log.debug("Ignoring displayMonster for {}: the side panel is disabled", name);
			return;
		}
		if (!dataService.isLoaded())
		{
			log.debug("displayMonster for {} arrived before the dataset loaded", name);
		}

		// Logged from inside the hop, where the outcome is actually known: a name that resolves to
		// nothing still opens the panel, and "opening X" for a monster we never found is the kind of
		// line that sends you hunting for a bug on the wrong side of the plugin boundary.
		SwingUtilities.invokeLater(() ->
		{
			if (panel.openMonsterRequested(name, version, request.isDrops()))
			{
				log.debug("Opened {} (version {}, {}) for a plugin request", name, version, request.isDrops() ? "drops" : "stats");
			}
			else
			{
				log.debug("No monster named {}; showing it as a search instead", name);
			}
			clientToolbar.openPanel(navButton);
		});
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		// Do not pair an Examine response from before a hop/logout with a later click.
		// Keep injected markers: RuneLite's global chat queue may still deliver one after this event.
		examineSummaryQueue.clearPending();
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		// Keep the player's combat, hitpoints and Slayer levels current so the panel can colour the
		// monster level relative to combat, flag max hits above the player's HP, and flag Slayer
		// requirements above the player's Slayer level; a few field reads per tick is negligible.
		Player p = client.getLocalPlayer();
		if (p != null)
		{
			playerCombatLevel = p.getCombatLevel();
			playerHpLevel = client.getRealSkillLevel(Skill.HITPOINTS);
			playerSlayerLevel = client.getRealSkillLevel(Skill.SLAYER);
		}
		else
		{
			playerCombatLevel = -1;
			playerHpLevel = -1;
			playerSlayerLevel = -1;
		}
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		// Anchor on the NPC's Examine entry — every NPC has exactly one, so the options appear once
		// per monster regardless of its other entries (Attack, Talk-to, …).
		boolean wantStats = config.statsMenuEntry();
		boolean wantDrops = config.dropsMenuEntry();
		if ((!wantStats && !wantDrops) || event.getType() != MenuAction.EXAMINE_NPC.getId())
		{
			return;
		}

		// Optionally gate the entries behind Shift so they don't clutter every monster's menu.
		if (config.requireShift() && !client.isKeyPressed(KeyCode.KC_SHIFT))
		{
			return;
		}

		// Resolve the NPC from the world view by the entry's identifier rather than
		// getMenuEntry().getNpc(), which isn't reliably populated for examine entries — the
		// approach the Loot Lookup plugin uses. Resolve now so name fallback must match the live
		// combat level; otherwise a cosmetic pet sharing a monster's name would gain these options.
		NPC npc = client.getTopLevelWorldView().npcs().byIndex(event.getIdentifier());
		if (resolveMonster(npc) == null)
		{
			return;
		}

		// Add each enabled option only when it can actually do something. Drops opens in the side
		// panel; Stats can target the overlay or the panel (see statsActionAvailable). The entry
		// created last sits on top, so add Drops first and Stats above it.
		if (wantDrops && config.enableSidePanel())
		{
			addMenuEntry(DROPS_OPTION, event);
		}
		if (wantStats && statsActionAvailable())
		{
			addMenuEntry(STATS_OPTION, event);
		}
	}

	/** Append a RUNELITE menu entry for one of our options, anchored on the NPC's Examine entry. */
	private void addMenuEntry(String option, MenuEntryAdded event)
	{
		client.getMenu().createMenuEntry(client.getMenu().getMenuEntries().length)
				.setOption(option)
				.setTarget(event.getTarget())
				.setIdentifier(event.getIdentifier())
				.setType(MenuAction.RUNELITE)
				.setParam0(event.getActionParam0())
				.setParam1(event.getActionParam1());
	}

	@Subscribe(priority = -1)
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		// Run after normal-priority subscribers so an Examine another plugin consumed is not tracked:
		// consumed menu actions never produce the vanilla chat response this feature waits for.
		if (event.isConsumed())
		{
			return;
		}

		// This path is independent of the plugin's extra Stats/Drops menu entries: the intended
		// compact setup is menuOptions=None with the normal Examine left untouched.
		if (event.getMenuAction() == MenuAction.EXAMINE_NPC
			&& "Examine".equals(event.getMenuOption()))
		{
			trackNpcExamine(event);
			return;
		}

		boolean stats = STATS_OPTION.equals(event.getMenuOption());
		boolean drops = DROPS_OPTION.equals(event.getMenuOption());
		if (!stats && !drops)
		{
			return;
		}

		clientThread.invoke(() ->
		{
			NPC clickedNPC = client.getTopLevelWorldView().npcs().byIndex(event.getId());
			MonsterData entry = resolveMonster(clickedNPC);
			if (entry == null)
			{
				if (clickedNPC != null)
				{
					log.debug("No dataset entry for clicked NPC {} (id {})", clickedNPC.getName(), clickedNPC.getId());
				}
				return;
			}
			String name = entry.getName();
			String version = entry.getVersion();

			if (drops)
			{
				log.debug("Opening drops for {} (npc id {})", name, clickedNPC.getId());
				openInPanel(name, version, true);
			}
			else
			{
				log.debug("Opening stats for {} (npc id {})", name, clickedNPC.getId());
				openStats(name, version);
			}
		});
	}

	/**
	 * Carry the retired {@code menuOptions} enum (Stats only / Drops only / Both / None) over to the
	 * two checkboxes that replaced it. The plugin is on the hub, so without this everyone who had
	 * narrowed or switched off the entries silently gets both back on update — the booleans would
	 * simply fall to their defaults with nothing to read. Unsetting the old key makes it a one-off.
	 */
	private void migrateMenuOptions()
	{
		String legacy = configManager.getConfiguration(CONFIG_GROUP, LEGACY_MENU_OPTIONS);
		if (legacy == null)
		{
			return;
		}
		configManager.setConfiguration(CONFIG_GROUP, "statsMenuEntry",
			"STATS_ONLY".equals(legacy) || "BOTH".equals(legacy));
		configManager.setConfiguration(CONFIG_GROUP, "dropsMenuEntry",
			"DROPS_ONLY".equals(legacy) || "BOTH".equals(legacy));
		configManager.unsetConfiguration(CONFIG_GROUP, LEGACY_MENU_OPTIONS);
		log.debug("Migrated menuOptions={} to the Stats/Drops checkboxes", legacy);
	}

	/** Record a native Examine in history and, when enabled, await its vanilla chat response. */
	private void trackNpcExamine(MenuOptionClicked event)
	{
		NPC npc = client.getTopLevelWorldView().npcs().byIndex(event.getId());
		MonsterData monster = resolveMonster(npc);
		if (monster != null)
		{
			BetterMonsterExaminePanel panel = monsterStatsPanel;
			if (panel != null)
			{
				String name = monster.getName();
				String version = monster.getVersion();
				SwingUtilities.invokeLater(() -> panel.recordLookup(name, version));
			}
		}

		if (!config.examineSummaryEnabled())
		{
			return;
		}

		// Unknown monsters deliberately add an empty slot so rapid Examine responses stay aligned.
		examineSummaryQueue.add(ExamineSummary.format(monster, config.examineSummaryDetail()), client.getTickCount());
	}

	/**
	 * Wait for the game's own Examine message, then queue one compact block behind it. A single
	 * queued message with {@code <br>} keeps the multi-line summary together in the chat box.
	 */
	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.NPC_EXAMINE)
		{
			return;
		}

		String message = examineSummaryQueue.onNpcExamine(event.getMessage(), client.getTickCount());
		if (message == null)
		{
			return;
		}

		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.NPC_EXAMINE)
			.runeLiteFormattedMessage(message)
			.build());
	}

	/** Resolve an NPC by spawn id, falling back to name plus its in-game combat level. */
	private MonsterData resolveMonster(NPC npc)
	{
		if (npc == null)
		{
			return null;
		}
		NPCComposition composition = npc.getTransformedComposition();
		if (composition != null && composition.isFollower())
		{
			return null;
		}

		MonsterData entry = dataService.getById(npc.getId());
		if (entry != null)
		{
			return entry;
		}

		String name = npc.getName();
		if (name == null)
		{
			return null;
		}
		return dataService.variantForLevel(name, npc.getCombatLevel());
	}

	/** Handle a Stats click: render to the overlay and/or side panel per the render target. */
	private void openStats(String name, String version)
	{
		RenderTarget target = config.statsRenderTarget();
		// The overlay draws on the client thread, so update it here; the panel is Swing (EDT).
		if (target.showsOverlay())
		{
			toggleOverlay(name, version);
		}
		// Feeding the panel records the lookup via its own select() choke point.
		if (target.showsPanel() && openInPanel(name, version, false))
		{
			return;
		}
		// Overlay-only target (or panel unavailable): still record so it lands in Recent.
		BetterMonsterExaminePanel panel = monsterStatsPanel;
		if (panel != null)
		{
			SwingUtilities.invokeLater(() -> panel.recordLookup(name, version));
		}
	}

	/**
	 * Open the side panel to the monster's Stats or Drops tab (on the EDT). Returns false when the
	 * panel isn't available, so a Stats click can fall back to just recording the lookup.
	 */
	private boolean openInPanel(String name, String version, boolean drops)
	{
		BetterMonsterExaminePanel panel = monsterStatsPanel;
		if (panel == null || navButton == null)
		{
			return false;
		}
		SwingUtilities.invokeLater(() ->
		{
			panel.openMonster(name, version, drops);
			clientToolbar.openPanel(navButton);
		});
		return true;
	}

	/** True when a Stats click would do something given the current render target and config. */
	private boolean statsActionAvailable()
	{
		RenderTarget target = config.statsRenderTarget();
		return target.showsOverlay() || (target.showsPanel() && config.enableSidePanel());
	}

	/**
	 * Show the overlay for the given monster, or hide it if it's already showing that exact
	 * monster (a second Stats click toggles it off). Client thread.
	 */
	private void toggleOverlay(String name, String version)
	{
		MonsterCardOverlay overlay = cardOverlay;
		if (overlay == null)
		{
			return;
		}
		String key = name + ' ' + version;
		if (key.equals(overlayKey))
		{
			// Already showing this monster — a second Stats click closes it (and keeps it closed).
			dismissOverlay();
			return;
		}
		MonsterData selection = dataService.variant(name, version);
		if (selection == null)
		{
			return;
		}
		overlay.setMonster(selection);
		overlayKey = key;
		dismissedKey = null;
	}

	/** Clear the overlay (e.g. it's no longer a render target), forgetting any dismissal. */
	private void hideOverlay()
	{
		MonsterCardOverlay overlay = cardOverlay;
		if (overlay != null)
		{
			overlay.clear();
		}
		overlayKey = null;
		dismissedKey = null;
	}

	/** Close the overlay at the user's request, remembering it so it doesn't auto-reopen. */
	private void dismissOverlay()
	{
		MonsterCardOverlay overlay = cardOverlay;
		if (overlay != null)
		{
			overlay.clear();
		}
		dismissedKey = overlayKey;
		overlayKey = null;
	}

	/**
	 * Mirror the side panel's current monster into the overlay (when the overlay is a render
	 * target), so searching or switching variants in the panel updates the overlay. Called on the
	 * EDT. The data is synchronous now, so an unchanged selection needs no update (the overlay
	 * redraws live each frame); only a different monster swaps the overlay and resets its tab.
	 */
	private void showInOverlay(MonsterData m)
	{
		MonsterCardOverlay overlay = cardOverlay;
		if (overlay == null || m == null || !config.statsRenderTarget().showsOverlay())
		{
			return;
		}
		String key = m.getName() + ' ' + m.getVersion();
		// Honour an explicit close (while this monster stays selected), and skip redundant
		// re-pushes of the monster already on screen.
		if (key.equals(dismissedKey) || key.equals(overlayKey))
		{
			return;
		}
		overlay.setMonster(m);
		overlayKey = key;
		dismissedKey = null;
	}

}
