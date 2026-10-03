package com.bettermonsterexamine;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("bettermonsterexamine")
public interface BetterMonsterExamineConfig extends Config
{
	/** Shared with the plugin's one-off migration off the retired menuOptions enum. */
	String STATS_MENU_ENTRY = "statsMenuEntry";
	String DROPS_MENU_ENTRY = "dropsMenuEntry";

	@ConfigSection(
		name = "Right-click menu",
		description = "",
		position = 2
	)
	String menuSection = "menuSection";

	@ConfigSection(
		name = "Examine",
		description = "What happens on the default Examine.",
		position = 3
	)
	String examineSection = "examineSection";

	@ConfigSection(
		name = "Side panel",
		description = "",
		position = 4
	)
	String panelSection = "panelSection";

	@ConfigSection(
		name = "Accessibility",
		description = "",
		position = 5
	)
	String highlightSection = "highlightSection";

	@ConfigSection(
		name = "Integrations",
		description = "",
		position = 6
	)
	String integrationSection = "integrationSection";

	// Sectionless on purpose: the right-click Stats entry and Examine both render through this, so
	// filing it under either section would misdescribe it.
	@ConfigItem(
		keyName = "statsRenderTarget",
		name = "Show stats in",
		description = "Where to show monster info: side panel and/or in-game overlay.",
		position = 0
	)
	default RenderTarget statsRenderTarget()
	{
		return RenderTarget.PANEL;
	}

	@ConfigItem(
		keyName = "showDefenceRolls",
		name = "Show defence rolls",
		description = "Show defence rolls instead of bonuses.",
		position = 1
	)
	default boolean showDefenceRolls()
	{
		return false;
	}

	@ConfigItem(
		keyName = STATS_MENU_ENTRY,
		name = "Stats entry",
		description = "Add a 'Stats' option to a monster's right-click menu.",
		section = menuSection,
		position = 0
	)
	default boolean statsMenuEntry()
	{
		return true;
	}

	@ConfigItem(
		keyName = DROPS_MENU_ENTRY,
		name = "Drops entry",
		description = "Add a 'Drops' option to a monster's right-click menu.",
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
		description = "Right-click options only show while Shift is held.",
		section = menuSection,
		position = 2
	)
	default boolean requireShift()
	{
		return false;
	}

	@ConfigItem(
		keyName = "collapseStackedExamine",
		name = "One Examine per stack",
		description = "",
		section = menuSection,
		position = 3
	)
	default boolean collapseStackedExamine()
	{
		return true;
	}

	@ConfigItem(
		keyName = "examineOpensStats",
		name = "Open stats",
		description = "Toggles opening the side-panel and/or overlay when examining a monster.",
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
		description = "Toggle monster summary in the chat.",
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
		description = "Configure what shows in the chat when enabled.",
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
		description = "",
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
		description = "Show Recent and Favorites lists in the side panel.",
		section = panelSection,
		position = 1
	)
	default boolean enableHistory()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showDropValues",
		name = "Drop values",
		description = "Show each drop's GE value under its quantity on the Drops tab. GE and High Alch are always in the row's tooltip.",
		section = panelSection,
		position = 2
	)
	default boolean showDropValues()
	{
		return true;
	}

	@ConfigItem(
		keyName = "statHighlighting",
		name = "Colour palette",
		description = "Colour-code player-relevant stats.",
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
		description = "",
		section = integrationSection,
		position = 0
	)
	default boolean notEnoughRunesLink()
	{
		return false;
	}
}
