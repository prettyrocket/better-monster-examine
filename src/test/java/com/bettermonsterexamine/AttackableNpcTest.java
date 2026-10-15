package com.bettermonsterexamine;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class AttackableNpcTest
{
	@Test
	public void attackActionMakesAnNpcAttackable()
	{
		assertTrue(BetterMonsterExaminePlugin.isAttackable(new String[]{null, "Attack", null, null, null}));
		assertTrue(BetterMonsterExaminePlugin.isAttackable(new String[]{"Talk-to", "attack"}));
	}

	@Test
	public void passiveNpcsAreNot()
	{
		// A passive critter has no Attack, whatever else it offers.
		assertFalse(BetterMonsterExaminePlugin.isAttackable(new String[]{null, null, null, null, null}));
		assertFalse(BetterMonsterExaminePlugin.isAttackable(new String[]{"Talk-to", null, "Trade"}));
		assertFalse(BetterMonsterExaminePlugin.isAttackable(null));
	}
}
