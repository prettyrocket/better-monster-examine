package com.bettermonsterexamine;

import com.google.gson.Gson;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Golden cases taken from a full run of the cached bestiary, one per band and one per trap.
 */
public class DefenceRollsTest
{
	private static final Gson GSON = new Gson();

	private static MonsterData monster(String json)
	{
		return GSON.fromJson(json, MonsterData.class);
	}

	private static String bonuses(int stab, int slash, int crush, int std, int heavy, int light, int magic)
	{
		return "\"stab_defence_bonus\":" + stab
			+ ",\"slash_defence_bonus\":" + slash
			+ ",\"crush_defence_bonus\":" + crush
			+ ",\"standard_range_defence_bonus\":" + std
			+ ",\"heavy_range_defence_bonus\":" + heavy
			+ ",\"light_range_defence_bonus\":" + light
			+ ",\"magic_defence_bonus\":" + magic;
	}

	/** Fire giant: the highest defence bonus on it is magic, yet magic is by far the easiest to land. */
	@Test
	public void magicLevelBeatsMagicBonus()
	{
		MonsterData m = monster("{\"defence_level\":120,\"magic_level\":1,"
			+ bonuses(20, 10, 10, -10, -10, -10, 50) + "}");

		DefenceRolls.Result r = DefenceRolls.of(m);

		assertEquals(DefenceRolls.Band.DECISIVE, r.getBand());
		assertTrue(r.isMagicOnly());
		assertEquals("Ranged", DefenceRolls.describe(r.getBestNonMagic()));
		assertEquals(6.11d, r.getNonMagicCost(), 0.01d);
	}

	/** Reading the raw bonuses alone would pick ranged here; the roll picks magic. */
	@Test
	public void rawBonusAndRollDisagree()
	{
		MonsterData m = monster("{\"defence_level\":120,\"magic_level\":1,"
			+ bonuses(20, 10, 10, -10, -10, -10, 50) + "}");

		assertEquals("Magic", DefenceRolls.describe(DefenceRolls.of(m).getWeakest()));
	}

	/** Shellbane gryphon: stab leads slash by only 1.14x, which is not worth calling a weakness. */
	@Test
	public void aNarrowLeadIsMarginal()
	{
		MonsterData m = monster("{\"defence_level\":120,\"magic_level\":100,"
			+ bonuses(10, 20, 40, 60, 60, 60, 100) + "}");

		assertEquals(DefenceRolls.Band.MARGINAL, DefenceRolls.of(m).getBand());
	}

	/** Ice demon rolls magic off its Defence level, not its Magic level of 390. */
	@Test
	public void theMagicExceptionListApplies()
	{
		String stats = "\"defence_level\":160,\"magic_level\":390," + bonuses(50, 50, 50, 50, 50, 50, 40);
		MonsterData excepted = monster("{\"page_name\":\"Ice demon\"," + stats + "}");
		MonsterData plain = monster("{\"page_name\":\"Ice giant\"," + stats + "}");

		assertEquals("Magic", DefenceRolls.describe(DefenceRolls.of(excepted).getWeakest()));
		// Without the exception the same numbers bury magic behind every other style.
		assertFalse(DefenceRolls.of(plain).isMagicOnly());
	}

	/** Egg (Tombs of Amascut): every bonus is -100, so every roll clamps to zero. */
	@Test
	public void aBonusBelowMinusSixtyFourCannotInvertTheRanking()
	{
		MonsterData m = monster("{\"defence_level\":0,\"magic_level\":1,"
			+ bonuses(-100, -100, -100, -100, -100, -100, -100) + "}");

		DefenceRolls.Result r = DefenceRolls.of(m);

		assertEquals(DefenceRolls.Band.ALWAYS_HITS, r.getBand());
		assertEquals(DefenceRolls.allStyles().size(), r.getWeakest().size());
	}

	@Test
	public void everyStyleEqualIsFlat()
	{
		MonsterData m = monster("{\"defence_level\":50,\"magic_level\":50,"
			+ bonuses(0, 0, 0, 0, 0, 0, 0) + "}");

		assertEquals(DefenceRolls.Band.FLAT, DefenceRolls.of(m).getBand());
	}

	/** A blank row must not read as a seven-way tie of zeroes. */
	@Test
	public void missingBonusesAreNotZero()
	{
		assertEquals(DefenceRolls.Band.NO_DATA, DefenceRolls.of(monster("{}")).getBand());
		assertEquals(DefenceRolls.Band.NO_DATA, DefenceRolls.of(null).getBand());
	}

	/** A Defence level of 0 is real (Egg), so it must not be mistaken for a missing one. */
	@Test
	public void zeroLevelsAreStillData()
	{
		MonsterData m = monster("{\"defence_level\":0,\"magic_level\":0,"
			+ bonuses(0, 0, 0, 0, 0, 0, 0) + "}");

		assertTrue(DefenceRolls.of(m).getBand() != DefenceRolls.Band.NO_DATA);
	}

	@Test
	public void wholeFamiliesCollapseToOneLabel()
	{
		MonsterData m = monster("{\"defence_level\":135,\"magic_level\":1,"
			+ bonuses(0, 0, 0, 0, 0, 0, 0) + "}");

		assertEquals("Melee/Ranged", DefenceRolls.describe(DefenceRolls.of(m).getBestNonMagic()));
	}

	@Test
	public void aPartialFamilyIsNamedStyleByStyle()
	{
		MonsterData m = monster("{\"defence_level\":100,\"magic_level\":100,"
			+ bonuses(0, 0, 50, 50, 50, 50, 50) + "}");

		assertEquals("Stab/Slash", DefenceRolls.describe(DefenceRolls.of(m).getWeakest()));
	}

	@Test
	public void theElementIsCapitalisedAndOptional()
	{
		String stats = "\"defence_level\":120,\"magic_level\":1," + bonuses(20, 10, 10, -10, -10, -10, 50);

		assertEquals("Water", DefenceRolls.of(monster("{" + stats
			+ ",\"elemental_weakness\":\"water\",\"elemental_weakness_percent\":100}")).getElement());
		assertNull(DefenceRolls.of(monster("{" + stats + "}")).getElement());
	}
}
