package com.bettermonsterexamine;

import com.google.gson.Gson;
import java.util.List;
import static org.junit.Assert.assertEquals;
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

	private static final String ICE_GIANT = "{\"name\":\"Ice giant\",\"stab_defence_bonus\":20,"
		+ "\"slash_defence_bonus\":20,\"crush_defence_bonus\":0,\"standard_range_defence_bonus\":40,"
		+ "\"heavy_range_defence_bonus\":20,\"light_range_defence_bonus\":60,"
		+ "\"elemental_weakness\":\"fire\",\"elemental_weakness_percent\":50}";

	@Test
	public void noMonsterOrNoModeProducesNoSummary()
	{
		assertTrue(ExamineSummary.format(null, ExamineSummaryMode.ALL_DEFENCES).isEmpty());
		assertTrue(ExamineSummary.format(monster("{}"), null).isEmpty());
	}

	@Test
	public void allDefencesUsesSignedBonusesAndRequestedOrder()
	{
		assertEquals(List.of(
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
		assertTrue(lines.get(0).startsWith("<col=ff4040>Weakest melee:</col>"));
	}

	@Test
	public void allDefencesOmitsMissingElement()
	{
		List<String> lines = ExamineSummary.format(monster("{}"), ExamineSummaryMode.ALL_DEFENCES);

		assertEquals(2, lines.size());
		assertEquals("<col=ff4040>Melee:</col> Stab +0 | Slash +0 | Crush +0", lines.get(0));
	}

	@Test
	public void weaknessesOnlySelectsLowestBonusesAndElement()
	{
		assertEquals(List.of(
			"<col=ff4040>Weakest melee:</col> Crush (+0) | <col=5fc96b>Ranged:</col> Heavy (+20)"
				+ " | <col=56b4e9>Elemental weakness:</col> Fire 50%"),
			ExamineSummary.format(monster(ICE_GIANT), ExamineSummaryMode.WEAKNESSES));
	}

	@Test
	public void weaknessesOnlyRetainsTiesAndNegativeSigns()
	{
		MonsterData m = monster("{\"stab_defence_bonus\":-15,\"slash_defence_bonus\":-15,"
			+ "\"crush_defence_bonus\":-15,\"standard_range_defence_bonus\":10,"
			+ "\"heavy_range_defence_bonus\":-5,\"light_range_defence_bonus\":-5}");

		assertEquals(List.of(
			"<col=ff4040>Weakest melee:</col> Stab/Slash/Crush (-15)"
				+ " | <col=5fc96b>Ranged:</col> Heavy/Light (-5)"),
			ExamineSummary.format(m, ExamineSummaryMode.WEAKNESSES));
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
