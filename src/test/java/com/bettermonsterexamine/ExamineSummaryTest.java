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

	/** Fixed indices so an expected string can spell the tag out. */
	private static final ExamineIconSet ICONS = new ExamineIconSet(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

	private static final String ICE_GIANT = "{\"name\":\"Ice giant\",\"stab_defence_bonus\":20,"
		+ "\"slash_defence_bonus\":20,\"crush_defence_bonus\":0,\"standard_range_defence_bonus\":40,"
		+ "\"heavy_range_defence_bonus\":20,\"light_range_defence_bonus\":60,"
		+ "\"elemental_weakness\":\"fire\",\"elemental_weakness_percent\":50}";

	@Test
	public void noMonsterOrNoModeProducesNoSummary()
	{
		assertTrue(ExamineSummary.format(null, ExamineSummaryMode.ALL_DEFENCES, ICONS).isEmpty());
		assertTrue(ExamineSummary.format(monster("{}"), null, ICONS).isEmpty());
	}

	@Test
	public void allDefencesUsesIconsSignedBonusesAndRequestedOrder()
	{
		assertEquals(List.of(
			"<col=ff4040>Melee:</col> <img=1> +20 | <img=2> +20 | <img=3> +0",
			"<col=5fc96b>Ranged:</col> <img=4> +40 | <img=5> +20 | <img=6> +60",
			"<col=56b4e9>Element:</col> <img=10> 50%"),
			ExamineSummary.format(monster(ICE_GIANT), ExamineSummaryMode.ALL_DEFENCES, ICONS));
	}

	@Test
	public void noIconSetSpellsTheStylesOut()
	{
		assertEquals(List.of(
			"<col=ff4040>Melee:</col> Stab +20 | Slash +20 | Crush +0",
			"<col=5fc96b>Ranged:</col> Standard +40 | Heavy +20 | Light +60",
			"<col=56b4e9>Element:</col> Fire 50%"),
			ExamineSummary.format(monster(ICE_GIANT), ExamineSummaryMode.ALL_DEFENCES, null));
	}

	@Test
	public void summaryNoLongerCarriesItsOwnNameHeader()
	{
		// The name moved onto the game's own Examine line, so the block starts with the stats.
		List<String> lines = ExamineSummary.format(monster(ICE_GIANT), ExamineSummaryMode.WEAKNESSES, ICONS);

		assertEquals(1, lines.size());
		assertTrue(lines.get(0).startsWith("<colHIGHLIGHT>Weakest:<colNORMAL>"));
	}

	@Test
	public void allDefencesOmitsMissingElement()
	{
		List<String> lines = ExamineSummary.format(monster("{}"), ExamineSummaryMode.ALL_DEFENCES, ICONS);

		assertEquals(2, lines.size());
		assertEquals("<col=ff4040>Melee:</col> <img=1> +0 | <img=2> +0 | <img=3> +0", lines.get(0));
	}

	@Test
	public void weaknessesOnlySelectsLowestBonusesAndElement()
	{
		assertEquals(List.of(
			"<colHIGHLIGHT>Weakest:<colNORMAL> <img=3> (+0) | <img=5> (+20) | <img=10> 50%"),
			ExamineSummary.format(monster(ICE_GIANT), ExamineSummaryMode.WEAKNESSES, ICONS));
	}

	@Test
	public void weaknessesOnlyRetainsTiesAndNegativeSigns()
	{
		MonsterData m = monster("{\"stab_defence_bonus\":-15,\"slash_defence_bonus\":-15,"
			+ "\"crush_defence_bonus\":-15,\"standard_range_defence_bonus\":10,"
			+ "\"heavy_range_defence_bonus\":-5,\"light_range_defence_bonus\":-5}");

		assertEquals(List.of(
			"<colHIGHLIGHT>Weakest:<colNORMAL> <img=1>/<img=2>/<img=3> (-15) | <img=5>/<img=6> (-5)"),
			ExamineSummary.format(m, ExamineSummaryMode.WEAKNESSES, ICONS));
	}

	@Test
	public void anElementWeBundleNoRuneForKeepsItsName()
	{
		MonsterData m = monster("{\"elemental_weakness\":\"smoke\",\"elemental_weakness_percent\":30}");

		assertTrue(ExamineSummary.format(m, ExamineSummaryMode.WEAKNESSES, ICONS).get(0)
			.endsWith("| Smoke 30%"));
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
