package com.bettermonsterexamine;

/**
 * Where the right-click "Stats" action renders a monster's card: the searchable side panel,
 * an in-game overlay drawn in the game viewport, or both.
 */
public enum RenderTarget
{
	/** Open the searchable side panel (the original behaviour). */
	PANEL("Side panel"),
	/** Draw the card as an in-game overlay; clicking Stats again on the same monster hides it. */
	OVERLAY("Overlay"),
	/** Both the side panel and the overlay. */
	BOTH("Both");

	private final String label;

	RenderTarget(String label)
	{
		this.label = label;
	}

	boolean showsPanel()
	{
		return this == PANEL || this == BOTH;
	}

	boolean showsOverlay()
	{
		return this == OVERLAY || this == BOTH;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
