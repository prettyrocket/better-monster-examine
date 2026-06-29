package com.bettermonsterexamine;

import java.util.Arrays;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class StatFormatTest
{
	@Test
	public void maxValueParsesPlainValue()
	{
		assertEquals(30, StatFormat.maxValue("30 (Magic)"));
	}

	@Test
	public void maxValueParsesBareNumber()
	{
		assertEquals(32, StatFormat.maxValue("32"));
	}

	@Test
	public void maxValueUsesTheTopOfARange()
	{
		assertEquals(61, StatFormat.maxValue("46-61 (Melee)"));
	}

	@Test
	public void maxValueParsesLargeSpecialValue()
	{
		assertEquals(121, StatFormat.maxValue("121 (Dragonfire Bomb/Special)"));
	}

	@Test
	public void maxValueIgnoresNumbersInTheLabel()
	{
		// Only the value before "(" counts — digits inside the label must not win.
		assertEquals(30, StatFormat.maxValue("30 (hits up to 99 in a thing)"));
	}

	@Test
	public void maxValueReturnsMinusOneWhenNoValue()
	{
		assertEquals(-1, StatFormat.maxValue("—"));
	}

	@Test
	public void bonusIsSigned()
	{
		assertEquals("+5", StatFormat.bonus(5));
		// Zero counts as non-negative, so it carries the + sign (matching the wiki's "+0").
		assertEquals("+0", StatFormat.bonus(0));
		assertEquals("-3", StatFormat.bonus(-3));
	}

	@Test
	public void numIsDashForAbsentLevels()
	{
		assertEquals("—", StatFormat.num(0));
		assertEquals("—", StatFormat.num(-1));
		assertEquals("99", StatFormat.num(99));
	}

	@Test
	public void numberDropsTrailingZero()
	{
		assertEquals("50", StatFormat.number(50.0));
		assertEquals("77.5", StatFormat.number(77.5));
		assertEquals("-50", StatFormat.number(-50.0));
	}

	@Test
	public void attributeNamesMapKnownKeys()
	{
		assertEquals("Draconic, Undead", StatFormat.attributeNames(Arrays.asList("dragon", "undead")));
		assertEquals("Vampyre (tier 2)", StatFormat.attributeNames(Arrays.asList("vampyre2")));
	}

	@Test
	public void joinSkipsBlanksAndNullsWhenEmpty()
	{
		assertEquals("7x7, Draconic", StatFormat.join(", ", "7x7", null, "", "Draconic"));
		assertNull(StatFormat.join(", ", null, ""));
	}
}
