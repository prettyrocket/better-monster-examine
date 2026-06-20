package com.bettermonsterexamine;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class BetterMonsterExaminePluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(BetterMonsterExaminePlugin.class);
		RuneLite.main(args);
	}
}