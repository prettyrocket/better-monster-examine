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
		description = "The Stats/Drops entries added to a monster's right-click Examine.",
		position = 1
	)
	String menuSection = "menuSection";

	@ConfigSection(
		name = "Examine",
		description = "What the plugin adds to the game's own Examine, without touching the right-click menu.",
		position = 2
	)
	String examineSection = "examineSection";

	@ConfigSection(
		name = "Side panel",
		description = "The searchable side panel and its Recent/Favorites lists.",
		position = 3
	)
	String panelSection = "panelSection";

	@ConfigSection(
		name = "Accessibility",
		description = "Colour palette for player-relevant stats and drop values, including a colour-blind-friendly mode.",
		position = 4
	)
	String highlightSection = "highlightSection";

	@ConfigSection(
		name = "Integrations",
		description = "Hand-offs to other plugins. Each needs that plugin installed and enabled to do anything.",
		position = 5
	)
	String integrationSection = "integrationSection";

	// Sectionless on purpose: the right-click Stats entry and Examine both render through this, so
	// filing it under either section would misdescribe it.
	@ConfigItem(
		keyName = "statsRenderTarget",
		name = "Show stats in",
		description = "Where a monster's stats appear: the side panel, an in-game overlay, or both. Used by the right-click 'Stats' entry and by 'Open stats on Examine'.",
		position = 0
	)
	default RenderTarget statsRenderTarget()
	{
		return RenderTarget.PANEL;
	}

	@ConfigItem(
		keyName = "statsMenuEntry",
		name = "Stats entry",
		description = "Add a 'Stats' option to a monster's right-click Examine, showing it per 'Show stats in'.",
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
		keyName = "requireShift",
		name = "Only show when Shift held",
		description = "Add the Stats/Drops right-click options only while Shift is held, to keep the normal menu uncluttered.",
		section = menuSection,
		position = 2
	)
	default boolean requireShift()
	{
		return false;
	}

	@ConfigItem(
		keyName = "examineOpensStats",
		name = "Open stats on Examine",
		description = "Examining a monster also shows it per 'Show stats in', so the Stats entry isn't needed. Unlike that entry, re-examining the same monster won't close the overlay.",
		section = examineSection,
		position = 0
	)
	default boolean examineOpensStats()
	{
		return false;
	}

	@ConfigItem(
		keyName = "examineSummaryEnabled",
		name = "Combat summary in chat",
		description = "Append compact combat information after the game's own Examine text.",
		section = examineSection,
		position = 1
	)
	default boolean examineSummaryEnabled()
	{
		return false;
	}

	@ConfigItem(
		keyName = "examineSummaryDetail",
		name = "Summary detail",
		description = "How much the chat summary shows: just the weakest melee and ranged styles, or every melee and ranged defence.",
		section = examineSection,
		position = 2
	)
	default ExamineSummaryMode examineSummaryDetail()
	{
		return ExamineSummaryMode.WEAKNESSES;
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
