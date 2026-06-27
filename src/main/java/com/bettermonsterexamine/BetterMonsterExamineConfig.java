package com.bettermonsterexamine;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("bettermonsterexamine")
public interface BetterMonsterExamineConfig extends Config
{
	@ConfigItem(
		keyName = "showStatsMenuOption",
		name = "Show Stats Menu Option",
		description = "Enable right-click 'Stats' option for NPCs, side panel must also be enabled."
	)
	default boolean showStatsMenuOption()
	{
		return true;
	}

	@ConfigItem(
		keyName = "enableSidePanel",
		name = "Enable Side Panel",
		description = "Enables the searchable side panel to display more monster stats."
	)
	default boolean enableSidePanel()
	{
		return true;
	}

	@ConfigItem(
		keyName = "statHighlighting",
		name = "Stat highlighting",
		description = "Colour-code player-relevant stats. 'Colour-blind friendly' uses an orange/blue palette with warning symbols."
	)
	default HighlightMode statHighlighting()
	{
		return HighlightMode.STANDARD;
	}

	@ConfigItem(
		keyName = "statsRenderTarget",
		name = "Stats render target",
		description = "Where the right-click 'Stats' action shows a monster: the side panel, an in-game overlay, or both."
	)
	default RenderTarget statsRenderTarget()
	{
		return RenderTarget.PANEL;
	}

	@ConfigItem(
		keyName = "enableHistory",
		name = "Recent & favorites",
		description = "Show Recent and Favorites lists in the side panel, reached via the ↺ / ★ buttons in the search row."
	)
	default boolean enableHistory()
	{
		return true;
	}
}
