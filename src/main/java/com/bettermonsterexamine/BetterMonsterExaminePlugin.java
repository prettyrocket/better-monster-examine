package com.bettermonsterexamine;

import javax.inject.Inject;
import javax.swing.SwingUtilities;

import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

import com.bettermonsterexamine.loot.DropPageService;
import com.bettermonsterexamine.loot.DropsCard;
import com.bettermonsterexamine.loot.ItemIdService;
import com.google.gson.Gson;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.client.util.ImageUtil;

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
	private static final String STATS_OPTION = "Stats";
	private static final String DROPS_OPTION = "Drops";

	@Provides
	BetterMonsterExamineConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BetterMonsterExamineConfig.class);
	}

	@Override
	protected void startUp() throws Exception
	{
		log.info("Better Monster Examine started");
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
		log.info("Better Monster Examine stopped");
	}

	private void addNavBar()
	{
		log.debug("Adding side panel navigation button");
		BufferedImage icon = titleIcon;
		DropsCard dropsCard = new DropsCard(itemManager, clientThread, itemIdService);
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
		if (!event.getGroup().equals("bettermonsterexamine"))
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
				// touch showStatsMenuOption (doing so left it stuck off after a re-enable).
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
		else if (event.getKey().equals("enableHistory"))
		{
			// Show/hide the panel's Recent/Favorites buttons and any open list view to match.
			BetterMonsterExaminePanel panel = monsterStatsPanel;
			if (panel != null)
			{
				SwingUtilities.invokeLater(panel::onHistoryConfigChanged);
			}
		}
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
		MenuOption menu = config.menuOptions();
		if (menu == MenuOption.NONE || event.getType() != MenuAction.EXAMINE_NPC.getId())
		{
			return;
		}

		// Resolve the NPC from the world view by the entry's identifier rather than
		// getMenuEntry().getNpc(), which isn't reliably populated for examine entries — the
		// approach the Loot Lookup plugin uses. Match by id or name so the options cover
		// variant ids the dataset lacks (e.g. Hellhounds in different dungeons).
		NPC npc = client.getTopLevelWorldView().npcs().byIndex(event.getIdentifier());
		if (npc == null || !dataService.isKnownMonster(npc.getId(), npc.getName()))
		{
			return;
		}

		// Add each enabled option only when it can actually do something. Drops opens in the side
		// panel; Stats can target the overlay or the panel (see statsActionAvailable). The entry
		// created last sits on top, so add Drops first and Stats above it.
		if (menu.showsDrops() && config.enableSidePanel())
		{
			addStatsEntry(DROPS_OPTION, event);
		}
		if (menu.showsStats() && statsActionAvailable())
		{
			addStatsEntry(STATS_OPTION, event);
		}
	}

	/** Append a RUNELITE menu entry for one of our options, anchored on the NPC's Examine entry. */
	private void addStatsEntry(String option, MenuEntryAdded event)
	{
		client.getMenu().createMenuEntry(client.getMenu().getMenuEntries().length)
				.setOption(option)
				.setTarget(event.getTarget())
				.setIdentifier(event.getIdentifier())
				.setType(MenuAction.RUNELITE)
				.setParam0(event.getActionParam0())
				.setParam1(event.getActionParam1());
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		boolean stats = STATS_OPTION.equals(event.getMenuOption());
		boolean drops = DROPS_OPTION.equals(event.getMenuOption());
		if (!stats && !drops)
		{
			return;
		}

		clientThread.invoke(() ->
		{
			NPC clickedNPC = client.getTopLevelWorldView().npcs().byIndex(event.getId());
			if (clickedNPC == null)
			{
				return;
			}
			// Resolve by id; if this spawn's id isn't in the dataset, fall back to the NPC's
			// name and match its in-game combat level to the right variant.
			MonsterData entry = dataService.getById(clickedNPC.getId());
			String name = entry != null ? entry.getName() : clickedNPC.getName();
			if (name == null || dataService.variantsForName(name).isEmpty())
			{
				log.debug("No dataset entry for clicked NPC {} (id {})", clickedNPC.getName(), clickedNPC.getId());
				return;
			}
			String version = entry != null ? entry.getVersion() : dataService.variantVersionForLevel(name, clickedNPC.getCombatLevel());

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
