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
		description = "The Stats/Drops entries added to a monster's right-click Examine, and where they render.",
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

	@ConfigItem(
		keyName = "menuOptions",
		name = "Right-click menu",
		description = "Which right-click options to add on a monster's Examine: Stats, Drops, both, or none. Drops needs the side panel enabled.",
		section = menuSection,
		position = 0
	)
	default MenuOption menuOptions()
	{
		return MenuOption.BOTH;
	}

	@ConfigItem(
		keyName = "statsRenderTarget",
		name = "Show stats in",
		description = "Where the right-click 'Stats' action shows a monster: the side panel, an in-game overlay, or both. Only applies when the Stats entry is enabled above.",
		section = menuSection,
		position = 1
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
		position = 2
	)
	default boolean requireShift()
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
		description = "Show Recent and Favorites lists in the side panel, reached via the ↺ / ★ buttons in the search row. Needs the side panel enabled.",
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
}
