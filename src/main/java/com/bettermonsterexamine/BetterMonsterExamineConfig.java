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
}
