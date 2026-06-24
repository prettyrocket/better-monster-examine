package com.bettermonsterexamine;

/**
 * How the panel colour-codes player-relevant stats (combat level, flat armour, aggressive,
 * poisonous, over-HP max hits).
 */
public enum HighlightMode
{
	/** No colour coding — plain white text. */
	OFF("Off"),
	/** The default red/green palette. */
	STANDARD("Standard"),
	/** An orange/blue palette plus non-colour symbols, for colour-blind players. */
	COLOUR_BLIND("Colour-blind friendly");

	private final String label;

	HighlightMode(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
