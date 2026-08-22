package com.bettermonsterexamine;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("bettermonsterexamine")
public interface BetterMonsterExamineConfig extends Config
{
	@ConfigSection(
		name = "Right-click menu",
		description = "What the plugin adds when you examine a monster: the Stats/Drops right-click entries, where they render, and the optional combat summary in chat.",
		position = 0
	)
	String menuSection = "menuSection";

	@ConfigSection(
		name = "Side panel",
		description = "The searchable side panel and its Recent/Favorites lists.",
		position = 1
	)
	String panelSection = "panelSection";

	@ConfigSection(
		name = "Accessibility",
		description = "Colour palette for player-relevant stats and drop values, including a colour-blind-friendly mode.",
		position = 2
	)
	String highlightSection = "highlightSection";

	@ConfigSection(
		name = "Integrations",
		description = "Hand-offs to other plugins. Each needs that plugin installed and enabled to do anything.",
		position = 3
	)
	String integrationSection = "integrationSection";

	@ConfigItem(
		keyName = "statsMenuEntry",
		name = "Stats entry",
		description = "Add a 'Stats' option to a monster's right-click Examine. Shows the monster per 'Show stats in' below.",
		section = menuSection,
		position = 0
	)
	default boolean statsMenuEntry()
	{
		return true;
	}

	@ConfigItem(
		keyName = "dropsMenuEntry",
		name = "Drops entry",
		description = "Add a 'Drops' option to a monster's right-click Examine, opening the side panel on its Drops tab. Needs the side panel enabled.",
		section = menuSection,
		position = 1
	)
	default boolean dropsMenuEntry()
	{
		return true;
	}

	@ConfigItem(
		keyName = "statsRenderTarget",
		name = "Show stats in",
		description = "Where the right-click 'Stats' action shows a monster: the side panel, an in-game overlay, or both. Only applies when the Stats entry is enabled above.",
		section = menuSection,
		position = 2
	)
	default RenderTarget statsRenderTarget()
	{
		return RenderTarget.PANEL;
	}

	@ConfigItem(
		keyName = "requireShift",
		name = "Only show when Shift held",
		description = "Add the Stats/Drops right-click options only while Shift is held, to keep the normal menu uncluttered.",
		section = menuSection,
		position = 3
	)
	default boolean requireShift()
	{
		return false;
	}

	@ConfigItem(
		keyName = "examineSummaryEnabled",
		name = "Combat summary on Examine",
		description = "Append compact combat information after the game's own Examine text. Independent of the entries above, so they can all be off.",
		section = menuSection,
		position = 4
	)
	default boolean examineSummaryEnabled()
	{
		return false;
	}

	@ConfigItem(
		keyName = "examineSummaryDetail",
		name = "Summary detail",
		description = "How much the Examine summary shows: just the weakest melee and ranged styles, or every melee and ranged defence. Only applies when the summary above is on.",
		section = menuSection,
		position = 5
	)
	default ExamineSummaryMode examineSummaryDetail()
	{
		return ExamineSummaryMode.WEAKNESSES;
	}

	@ConfigItem(
		keyName = "examineOpensStats",
		name = "Open stats on Examine",
		description = "Examining a monster also shows it per 'Show stats in' above, so the Stats entry isn't needed. Unlike that entry, re-examining the same monster won't close the overlay.",
		section = menuSection,
		position = 6
	)
	default boolean examineOpensStats()
	{
		return false;
	}

	@ConfigItem(
		keyName = "enableSidePanel",
		name = "Enable side panel",
		description = "Enables the searchable side panel to display more monster stats.",
		section = panelSection,
		position = 0
	)
	default boolean enableSidePanel()
	{
		return true;
	}

	@ConfigItem(
		keyName = "enableHistory",
		name = "Recent & favorites",
		description = "Show Recent and Favorites lists in the side panel, reached via the ↺ / ★ buttons in the search row. Normal monster Examines are added to Recent. Needs the side panel enabled.",
		section = panelSection,
		position = 1
	)
	default boolean enableHistory()
	{
		return true;
	}

	@ConfigItem(
		keyName = "statHighlighting",
		name = "Colour palette",
		description = "Colour-code player-relevant stats. 'Colour-blind friendly' uses an orange/blue palette with warning symbols.",
		section = highlightSection,
		position = 0
	)
	default HighlightMode statHighlighting()
	{
		return HighlightMode.STANDARD;
	}

	@ConfigItem(
		keyName = "notEnoughRunesLink",
		name = "Not Enough Runes",
		description = "Requires the Not Enough Runes plugin. Clicking a drop opens the item there instead of the wiki; right-click opens the wiki. Falls back to the wiki when it isn't running.",
		section = integrationSection,
		position = 0
	)
	default boolean notEnoughRunesLink()
	{
		return false;
	}
}
