package com.bettermonsterexamine;

/**
 * Which right-click entries the plugin adds on an NPC's Examine: the <b>Stats</b> card, the
 * <b>Drops</b> table, both, or neither. Stats renders per {@link RenderTarget} (panel / overlay);
 * Drops opens the side panel to its Drops tab.
 */
public enum MenuOption
{
	STATS_ONLY("Stats only"),
	DROPS_ONLY("Drops only"),
	BOTH("Both"),
	NONE("None");

	private final String label;

	MenuOption(String label)
	{
		this.label = label;
	}

	boolean showsStats()
	{
		return this == STATS_ONLY || this == BOTH;
	}

	boolean showsDrops()
	{
		return this == DROPS_ONLY || this == BOTH;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
