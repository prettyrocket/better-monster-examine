package com.bettermonsterexamine;

import com.google.gson.Gson;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ExamineSummaryTest
{
	private static final Gson GSON = new Gson();

	private static MonsterData monster(String json)
	{
		return GSON.fromJson(json, MonsterData.class);
	}

	private static final String ICE_GIANT = "{\"name\":\"Ice giant\",\"defence_level\":40,"
		+ "\"magic_level\":1,\"stab_defence_bonus\":20,"
		+ "\"slash_defence_bonus\":20,\"crush_defence_bonus\":0,\"standard_range_defence_bonus\":40,"
		+ "\"heavy_range_defence_bonus\":20,\"light_range_defence_bonus\":60,"
		+ "\"magic_defence_bonus\":0,"
		+ "\"elemental_weakness\":\"fire\",\"elemental_weakness_percent\":50}";

	@Test
	public void noMonsterOrNoModeProducesNoSummary()
	{
		assertTrue(ExamineSummary.format(null, ExamineSummaryMode.ALL_DEFENCES).isEmpty());
		assertTrue(ExamineSummary.format(monster("{}"), null).isEmpty());
	}

	@Test
	public void allDefencesLeadsWithTheWeaknessThenTheNumbers()
	{
		assertEquals(List.of(
			"<col=56b4e9>Weakest to:</col> Magic (Fire), or Crush",
			"<col=ff4040>Melee:</col> Stab +20 | Slash +20 | Crush +0",
			"<col=5fc96b>Ranged:</col> Standard +40 | Heavy +20 | Light +60",
			"<col=56b4e9>Elemental weakness:</col> Fire 50%"),
			ExamineSummary.format(monster(ICE_GIANT), ExamineSummaryMode.ALL_DEFENCES));
	}

	@Test
	public void summaryNoLongerCarriesItsOwnNameHeader()
	{
		// The name moved onto the game's own Examine line, so the block starts with the stats.
		List<String> lines = ExamineSummary.format(monster(ICE_GIANT), ExamineSummaryMode.WEAKNESSES);

		assertEquals(1, lines.size());
		assertTrue(lines.get(0).contains("Weakest to:"));
	}

	/** The magic bonus is the same 0 as crush, but a Magic level of 1 makes casting far easier. */
	@Test
	public void weaknessesNamesTheElementOnlyWhenMagicWins()
	{
		assertEquals(List.of("<col=56b4e9>Weakest to:</col> Magic (Fire), or Crush"),
			ExamineSummary.format(monster(ICE_GIANT), ExamineSummaryMode.WEAKNESSES));
	}

	/**
	 * A melee answer carries no elemental weakness: the element is a damage multiplier, and
	 * printing it beside a melee recommendation reads as an endorsement of casting.
	 */
	@Test
	public void aNonMagicWinnerDropsTheElement()
	{
		MonsterData m = monster("{\"defence_level\":100,\"magic_level\":100,"
			+ "\"stab_defence_bonus\":-15,\"slash_defence_bonus\":-15,\"crush_defence_bonus\":-15,"
			+ "\"standard_range_defence_bonus\":60,\"heavy_range_defence_bonus\":60,"
			+ "\"light_range_defence_bonus\":60,\"magic_defence_bonus\":100,"
			+ "\"elemental_weakness\":\"air\",\"elemental_weakness_percent\":50}");

		List<String> lines = ExamineSummary.format(m, ExamineSummaryMode.WEAKNESSES);

		assertEquals(List.of("<col=ff4040>Weakest to:</col> Melee (2.5x over Ranged)"), lines);
		assertFalse(lines.get(0).contains("Air"));
	}

	/** A close race is not a weakness, and it does not get to mention the element either. */
	@Test
	public void aMarginalLeadSaysSoAndDropsTheElement()
	{
		MonsterData m = monster("{\"defence_level\":120,\"magic_level\":100,"
			+ "\"stab_defence_bonus\":10,\"slash_defence_bonus\":20,\"crush_defence_bonus\":40,"
			+ "\"standard_range_defence_bonus\":60,\"heavy_range_defence_bonus\":60,"
			+ "\"light_range_defence_bonus\":60,\"magic_defence_bonus\":100,"
			+ "\"elemental_weakness\":\"air\",\"elemental_weakness_percent\":50}");

		assertEquals(List.of("<col=ff4040>Weakest to:</col> nothing in particular"),
			ExamineSummary.format(m, ExamineSummaryMode.WEAKNESSES));
	}

	/** No defensive data drops the line rather than printing a seven-way tie of zeroes. */
	@Test
	public void aBlankRowGetsNoWeaknessLine()
	{
		List<String> lines = ExamineSummary.format(monster("{}"), ExamineSummaryMode.ALL_DEFENCES);

		assertEquals(2, lines.size());
		assertEquals("<col=ff4040>Melee:</col> Stab +0 | Slash +0 | Crush +0", lines.get(0));
		assertTrue(ExamineSummary.format(monster("{}"), ExamineSummaryMode.WEAKNESSES).isEmpty());
	}

	@Test
	public void chatNameEscapesFormattingAndFlattensNewlines()
	{
		assertEquals("Boss <lt>col=ff0000<gt><at>red<lt>/col<gt> form",
			ExamineSummary.chatName("Boss <col=ff0000>@red</col>\nform"));
	}

	@Test
	public void chatNameRejectsWhatCannotBeShown()
	{
		assertNull(ExamineSummary.chatName(null));
		assertNull(ExamineSummary.chatName("   "));
	}

	@Test
	public void modeLabelsAreUserFacing()
	{
		assertEquals("Weaknesses only", ExamineSummaryMode.WEAKNESSES.toString());
		assertEquals("All defences", ExamineSummaryMode.ALL_DEFENCES.toString());
	}
}
