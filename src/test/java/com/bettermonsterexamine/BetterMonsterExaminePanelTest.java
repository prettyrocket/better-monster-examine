package com.bettermonsterexamine;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class BetterMonsterExaminePanelTest
{
	@Test
	public void parsesPlainValue()
	{
		assertEquals(30, BetterMonsterExaminePanel.maxValue("30 (Magic)"));
	}

	@Test
	public void parsesBareNumber()
	{
		assertEquals(32, BetterMonsterExaminePanel.maxValue("32"));
	}

	@Test
	public void usesTheTopOfARange()
	{
		assertEquals(61, BetterMonsterExaminePanel.maxValue("46-61 (Melee)"));
	}

	@Test
	public void parsesLargeSpecialValue()
	{
		assertEquals(121, BetterMonsterExaminePanel.maxValue("121 (Dragonfire Bomb/Special)"));
	}

	@Test
	public void ignoresNumbersInTheLabel()
	{
		// Only the value before "(" counts — digits inside the label must not win.
		assertEquals(30, BetterMonsterExaminePanel.maxValue("30 (hits up to 99 in a thing)"));
	}

	@Test
	public void returnsMinusOneWhenNoValue()
	{
		assertEquals(-1, BetterMonsterExaminePanel.maxValue("—"));
	}
}
