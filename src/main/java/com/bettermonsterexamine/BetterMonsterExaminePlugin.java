package com.bettermonsterexamine;

import javax.inject.Inject;
import javax.swing.SwingUtilities;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
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

	private NavigationButton navButton;
	private BetterMonsterExaminePanel monsterStatsPanel;
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

	public void addNavBar()
	{
		log.debug("Adding side panel navigation button");
		monsterStatsPanel = new BetterMonsterExaminePanel(monsterIcons, dataService, wikiService, config, ImageUtil.loadImageResource(getClass(), "/icon.png"));
		navButton = NavigationButton.builder()
				.tooltip("Better Monster Examine")
				.icon(ImageUtil.loadImageResource(getClass(), "/icon.png"))
				.panel(monsterStatsPanel)
				.build();

		clientToolbar.addNavigation(navButton);
	}

	public void removeNavBar()
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

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		NPC npc = event.getMenuEntry().getNpc();

		// Anchor on the NPC's Examine entry: every NPC has exactly one, regardless of which
		// other options (Attack, Talk-to, …) it carries, so the Stats option appears once for
		// every monster we have data for — not just those whose second option is "Attack".
		if (config.enableSidePanel() && config.showStatsMenuOption()
			&& event.getType() == MenuAction.EXAMINE_NPC.getId() && npc != null
			&& dataService.getById(npc.getId()) != null)
		{
			client.createMenuEntry(client.getMenuEntries().length)
					.setOption(STATS_OPTION)
					.setTarget(event.getTarget())
					.setIdentifier(event.getIdentifier())
					.setType(MenuAction.RUNELITE)
					.setParam0(event.getActionParam0())
					.setParam1(event.getActionParam1());
		}
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
			MonsterData m = dataService.getById(clickedNPC.getId());
			if (m == null)
			{
				log.debug("No dataset entry for clicked NPC id {}", clickedNPC.getId());
				return;
			}
			log.debug("Opening stats for {} (npc id {})", m.getName(), clickedNPC.getId());
			SwingUtilities.invokeLater(() ->
			{
				monsterStatsPanel.search(m.getName(), true, m.getVersion());
				clientToolbar.openPanel(navButton);
			});
		});
	}
}
