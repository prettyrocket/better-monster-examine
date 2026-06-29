package com.bettermonsterexamine;

import java.awt.Color;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class StatColorsTest
{
	@Test
	public void offModeIsAlwaysWhite()
	{
		assertEquals(Color.WHITE, StatColors.resolve(ColourRole.DANGER, HighlightMode.OFF));
		assertEquals(Color.WHITE, StatColors.resolve(ColourRole.GOOD, HighlightMode.OFF));
		assertEquals(Color.WHITE, StatColors.levelColor(HighlightMode.OFF, 100, 50));
	}

	@Test
	public void standardDangerAndGood()
	{
		assertEquals(StatColors.DANGER_RED, StatColors.resolve(ColourRole.DANGER, HighlightMode.STANDARD));
		assertEquals(net.runelite.client.ui.ColorScheme.PROGRESS_COMPLETE_COLOR, StatColors.resolve(ColourRole.GOOD, HighlightMode.STANDARD));
		assertEquals(Color.WHITE, StatColors.resolve(ColourRole.NEUTRAL, HighlightMode.STANDARD));
	}

	@Test
	public void colourBlindUsesOkabeIto()
	{
		assertEquals(StatColors.CB_DANGER, StatColors.resolve(ColourRole.DANGER, HighlightMode.COLOUR_BLIND));
		assertEquals(StatColors.CB_GOOD, StatColors.resolve(ColourRole.GOOD, HighlightMode.COLOUR_BLIND));
	}

	@Test
	public void standardLevelGradientByDifference()
	{
		// Monster far below the player → green; at parity → yellow; far above → red.
		assertEquals(new Color(0x00FF00), StatColors.levelColor(HighlightMode.STANDARD, 100, 50));
		assertEquals(new Color(0xFFFF00), StatColors.levelColor(HighlightMode.STANDARD, 100, 100));
		assertEquals(new Color(0xFF0000), StatColors.levelColor(HighlightMode.STANDARD, 50, 100));
	}

	@Test
	public void unknownPlayerLevelIsWhite()
	{
		assertEquals(Color.WHITE, StatColors.levelColor(HighlightMode.STANDARD, -1, 100));
		assertEquals(Color.WHITE, StatColors.levelColor(HighlightMode.COLOUR_BLIND, -1, 100));
	}

	@Test
	public void colourBlindLevelIsDirectional()
	{
		assertEquals(StatColors.CB_DANGER, StatColors.levelColor(HighlightMode.COLOUR_BLIND, 50, 100));
		assertEquals(StatColors.CB_GOOD, StatColors.levelColor(HighlightMode.COLOUR_BLIND, 100, 50));
		assertEquals(Color.WHITE, StatColors.levelColor(HighlightMode.COLOUR_BLIND, 100, 100));
	}
}
