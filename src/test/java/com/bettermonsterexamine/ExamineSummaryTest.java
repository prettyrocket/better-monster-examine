package com.bettermonsterexamine;

import com.google.gson.Gson;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ExamineSummaryTest
{
	private static final Gson GSON = new Gson();

	private static MonsterData monster(String json)
	{
		return GSON.fromJson(json, MonsterData.class);
	}

	@Test
	public void offProducesNoSummary()
	{
		assertTrue(ExamineSummary.format(monster("{}"), ExamineSummaryMode.OFF).isEmpty());
		assertTrue(ExamineSummary.format(null, ExamineSummaryMode.ALL_DEFENCES).isEmpty());
	}

	@Test
	public void allDefencesUsesSignedBonusesAndRequestedOrder()
	{
		MonsterData m = monster("{\"name\":\"Ice giant\",\"stab_defence_bonus\":20,\"slash_defence_bonus\":20,"
			+ "\"crush_defence_bonus\":0,\"standard_range_defence_bonus\":40,"
			+ "\"heavy_range_defence_bonus\":20,\"light_range_defence_bonus\":60,"
			+ "\"elemental_weakness\":\"fire\",\"elemental_weakness_percent\":50}");

		assertEquals(List.of(
			"<colHIGHLIGHT>Examined Ice giant stats:<colNORMAL>",
			"<col=ff4040>Melee:</col> Stab +20 | Slash +20 | Crush +0",
			"<col=5fc96b>Ranged:</col> Standard +40 | Heavy +20 | Light +60",
			"<col=56b4e9>Elemental weakness:</col> Fire 50%"),
			ExamineSummary.format(m, ExamineSummaryMode.ALL_DEFENCES));
	}

	@Test
	public void allDefencesOmitsMissingElement()
	{
		List<String> lines = ExamineSummary.format(monster("{}"), ExamineSummaryMode.ALL_DEFENCES);

		assertEquals(3, lines.size());
		assertEquals("<colHIGHLIGHT>Examined monster stats:<colNORMAL>", lines.get(0));
		assertEquals("<col=ff4040>Melee:</col> Stab +0 | Slash +0 | Crush +0", lines.get(1));
	}

	@Test
	public void weaknessesOnlySelectsLowestBonusesAndElement()
	{
		MonsterData m = monster("{\"name\":\"Ice giant\",\"stab_defence_bonus\":20,\"slash_defence_bonus\":20,"
			+ "\"crush_defence_bonus\":0,\"standard_range_defence_bonus\":40,"
			+ "\"heavy_range_defence_bonus\":20,\"light_range_defence_bonus\":60,"
			+ "\"elemental_weakness\":\"fire\",\"elemental_weakness_percent\":50}");

		assertEquals(List.of(
			"<colHIGHLIGHT>Examined Ice giant stats:<colNORMAL>",
			"<col=ff4040>Weakest melee:</col> Crush (+0) | <col=5fc96b>Ranged:</col> Heavy (+20)"
				+ " | <col=56b4e9>Elemental weakness:</col> Fire 50%"),
			ExamineSummary.format(m, ExamineSummaryMode.WEAKNESSES));
	}

	@Test
	public void weaknessesOnlyRetainsTiesAndNegativeSigns()
	{
		MonsterData m = monster("{\"stab_defence_bonus\":-15,\"slash_defence_bonus\":-15,"
			+ "\"crush_defence_bonus\":-15,\"standard_range_defence_bonus\":10,"
			+ "\"heavy_range_defence_bonus\":-5,\"light_range_defence_bonus\":-5}");

		assertEquals(List.of(
			"<colHIGHLIGHT>Examined monster stats:<colNORMAL>",
			"<col=ff4040>Weakest melee:</col> Stab/Slash/Crush (-15)"
				+ " | <col=5fc96b>Ranged:</col> Heavy/Light (-5)"),
			ExamineSummary.format(m, ExamineSummaryMode.WEAKNESSES));
	}

	@Test
	public void headerEscapesFormattingAndFlattensNewlinesInMonsterName()
	{
		MonsterData m = monster("{\"name\":\"Boss <col=ff0000>@red</col>\\nform\"}");

		assertEquals("<colHIGHLIGHT>Examined Boss <lt>col=ff0000<gt><at>red"
			+ "<lt>/col<gt> form stats:<colNORMAL>",
			ExamineSummary.format(m, ExamineSummaryMode.WEAKNESSES).get(0));
	}

	@Test
	public void modeLabelsAreUserFacing()
	{
		assertEquals("Off", ExamineSummaryMode.OFF.toString());
		assertEquals("Weaknesses only", ExamineSummaryMode.WEAKNESSES.toString());
		assertEquals("All defences", ExamineSummaryMode.ALL_DEFENCES.toString());
	}
}
