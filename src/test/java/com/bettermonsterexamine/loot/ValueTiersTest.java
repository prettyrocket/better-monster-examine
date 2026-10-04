package com.bettermonsterexamine.loot;

import java.awt.Color;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ValueTiersTest
{
	private static final Color LIGHT_BLUE = Color.decode("#66B2FF");
	private static final Color GREEN = Color.decode("#99FF99");
	private static final Color ORANGE = Color.decode("#FF9600");
	private static final Color PINK = Color.decode("#FF66B2");

	@Test
	public void defaultsMatchGroundItems()
	{
		ValueTiers t = ValueTiers.DEFAULTS;

		assertNull(t.colorFor(5_000));
		assertEquals(LIGHT_BLUE, t.colorFor(20_001));
		assertEquals(GREEN, t.colorFor(250_000));
		assertEquals(ORANGE, t.colorFor(1_500_000));
		assertEquals(PINK, t.colorFor(50_000_000));
	}

	@Test
	public void aValueMustBeStrictlyAboveATiersPrice()
	{
		// Ground Items compares with >, so exactly 20K is still untiered and exactly 1M is still green.
		assertNull(ValueTiers.DEFAULTS.colorFor(20_000));
		assertEquals(GREEN, ValueTiers.DEFAULTS.colorFor(1_000_000));
	}

	@Test
	public void aTierPricedAtZeroIsSwitchedOff()
	{
		ValueTiers t = new ValueTiers(0, LIGHT_BLUE, 100_000, GREEN, 1_000_000, ORANGE, 0, PINK);

		assertNull(t.colorFor(50_000));
		assertEquals(ORANGE, t.colorFor(99_000_000));
	}

	@Test
	public void recognisesOnlyTheTierSettings()
	{
		assertTrue(ValueTiers.isTierSetting("lowValuePrice"));
		assertTrue(ValueTiers.isTierSetting("insaneValueColor"));
		assertFalse(ValueTiers.isTierSetting("highlightedColor"));
		assertFalse(ValueTiers.isTierSetting("hiddenItems"));
	}
}
