package com.bettermonsterexamine;

import javax.inject.Inject;
import javax.swing.SwingUtilities;

import java.awt.image.BufferedImage;

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
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
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
	private ClientToolbar clientToolbar;

	@Inject
	private MonsterDataService dataService;

	@Inject
	private WikiInfoboxService wikiService;

	// Touched from the client thread (lifecycle/menu), the event bus (config), and the EDT
	// (panel open), so kept volatile for safe publication across those threads.
	private volatile NavigationButton navButton;
	private volatile BetterMonsterExaminePanel monsterStatsPanel;
	// Cached on the client thread (GameTick) so the panel can read them safely off-thread (EDT).
	private volatile int playerCombatLevel = -1;
	private volatile int playerHpLevel = -1;
	private static final String STATS_OPTION = "Stats";

	@Provides
	BetterMonsterExamineConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BetterMonsterExamineConfig.class);
	}

	@Override
	protected void startUp() throws Exception
	{
		log.info("Better Monster Examine started");
		if (config.enableSidePanel())
		{
			addNavBar();
		}
	}

	@Override
	protected void shutDown() throws Exception
	{
		removeNavBar();
		log.info("Better Monster Examine stopped");
	}

	private void addNavBar()
	{
		log.debug("Adding side panel navigation button");
		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/icon.png");
		monsterStatsPanel = new BetterMonsterExaminePanel(monsterIcons, dataService, wikiService, config, () -> playerCombatLevel, () -> playerHpLevel, icon);
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
	}

	/** When resolving a monster by name, pick the variant whose combat level matches the NPC. */
	private String variantVersionForLevel(String name, int combatLevel)
	{
		for (MonsterData v : dataService.variantsForName(name))
		{
			if (v.getLevel() == combatLevel)
			{
				return v.getVersion();
			}
		}
		return null;
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		// Keep the player's combat and hitpoints levels current so the panel can colour the
		// monster level relative to combat and flag max hits above the player's HP; a couple
		// of field reads per tick is negligible.
		Player p = client.getLocalPlayer();
		if (p != null)
		{
			playerCombatLevel = p.getCombatLevel();
			playerHpLevel = client.getRealSkillLevel(Skill.HITPOINTS);
		}
		else
		{
			playerCombatLevel = -1;
			playerHpLevel = -1;
		}
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		// Anchor on the NPC's Examine entry — every NPC has exactly one, so the Stats option
		// appears once per monster regardless of its other options (Attack, Talk-to, …).
		if (!config.enableSidePanel() || !config.showStatsMenuOption()
			|| event.getType() != MenuAction.EXAMINE_NPC.getId())
		{
			return;
		}

		// Resolve the NPC from the world view by the entry's identifier rather than
		// getMenuEntry().getNpc(), which isn't reliably populated for examine entries — the
		// approach the Loot Lookup plugin uses. Match by id or name so the option covers
		// variant ids the dataset lacks (e.g. Hellhounds in different dungeons).
		NPC npc = client.getTopLevelWorldView().npcs().byIndex(event.getIdentifier());
		if (npc == null || !dataService.isKnownMonster(npc.getId(), npc.getName()))
		{
			return;
		}

		client.getMenu().createMenuEntry(client.getMenu().getMenuEntries().length)
				.setOption(STATS_OPTION)
				.setTarget(event.getTarget())
				.setIdentifier(event.getIdentifier())
				.setType(MenuAction.RUNELITE)
				.setParam0(event.getActionParam0())
				.setParam1(event.getActionParam1());
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (!event.getMenuOption().equals(STATS_OPTION))
		{
			return;
		}

		clientThread.invoke(() ->
		{
			NPC clickedNPC = client.getTopLevelWorldView().npcs().byIndex(event.getId());
			if (clickedNPC == null || monsterStatsPanel == null)
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
			String version = entry != null ? entry.getVersion() : variantVersionForLevel(name, clickedNPC.getCombatLevel());

			log.debug("Opening stats for {} (npc id {})", name, clickedNPC.getId());
			SwingUtilities.invokeLater(() ->
			{
				monsterStatsPanel.search(name, true, version);
				clientToolbar.openPanel(navButton);
			});
		});
	}
}
