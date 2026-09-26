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

	private static String defences(int defence, int magic, int stab, int slash, int crush,
		int standard, int heavy, int light, int magicBonus)
	{
		return defences(defence, magic, stab, slash, crush, standard, heavy, light, magicBonus, null);
	}

	private static String defences(int defence, int magic, int stab, int slash, int crush,
		int standard, int heavy, int light, int magicBonus, String element)
	{
		return "{\"defence_level\":" + defence + ",\"magic_level\":" + magic
			+ ",\"stab_defence_bonus\":" + stab + ",\"slash_defence_bonus\":" + slash
			+ ",\"crush_defence_bonus\":" + crush + ",\"standard_range_defence_bonus\":" + standard
			+ ",\"heavy_range_defence_bonus\":" + heavy + ",\"light_range_defence_bonus\":" + light
			+ ",\"magic_defence_bonus\":" + magicBonus
			+ (element == null ? "" : ",\"elemental_weakness\":\"" + element + "\",\"elemental_weakness_percent\":50")
			+ "}";
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
			"Weakness: Magic (<col=56b4e9>Fire</col>), Crush",
			"Melee: Stab +20 | Slash +20 | Crush +0",
			"Ranged: Standard +40 | Heavy +20 | Light +60",
			"Elemental weakness: <col=56b4e9>Fire 50%</col>"),
			ExamineSummary.format(monster(ICE_GIANT), ExamineSummaryMode.ALL_DEFENCES));
	}

	@Test
	public void summaryNoLongerCarriesItsOwnNameHeader()
	{
		// The name moved onto the game's own Examine line, so the block starts with the stats.
		List<String> lines = ExamineSummary.format(monster(ICE_GIANT), ExamineSummaryMode.WEAKNESSES);

		assertEquals(1, lines.size());
		assertTrue(lines.get(0).startsWith("Weakness:"));
	}

	/** Magic wins, then the comma names the best style that costs no runes. */
	@Test
	public void magicIsFollowedByTheBestFreeStyle()
	{
		assertEquals(List.of("Weakness: Magic (<col=56b4e9>Fire</col>), Crush"),
			ExamineSummary.format(monster(ICE_GIANT), ExamineSummaryMode.WEAKNESSES));
	}

	/** When every free style is even there is no second answer to give. */
	@Test
	public void magicStandsAloneWhenFreeStylesAreEven()
	{
		MonsterData m = monster(defences(80, 1, 0, 0, 0, 0, 0, 0, 0));

		assertEquals(List.of("Weakness: Magic"), ExamineSummary.format(m, ExamineSummaryMode.WEAKNESSES));
	}

	/**
	 * A melee answer carries no elemental weakness: the element is a damage multiplier, and
	 * printing it beside a melee recommendation reads as an endorsement of casting.
	 */
	@Test
	public void aNonMagicWinnerDropsTheElement()
	{
		MonsterData m = monster(defences(100, 100, -15, -15, -15, 60, 60, 60, 100, "air"));

		List<String> lines = ExamineSummary.format(m, ExamineSummaryMode.WEAKNESSES);

		assertEquals(List.of("Weakness: Melee"), lines);
		assertFalse(lines.get(0).contains("Air"));
	}

	/** Styles within the tie factor are joined with a slash, and the element still stays off. */
	@Test
	public void aNarrowLeadIsATie()
	{
		MonsterData m = monster(defences(120, 100, 10, 20, 40, 60, 60, 60, 100, "air"));

		assertEquals(List.of("Weakness: Stab/Slash"), ExamineSummary.format(m, ExamineSummaryMode.WEAKNESSES));
	}

	@Test
	public void evenStylesSayNone()
	{
		MonsterData m = monster(defences(50, 50, 0, 0, 0, 0, 0, 0, 0));

		assertEquals(List.of("Weakness: none"), ExamineSummary.format(m, ExamineSummaryMode.WEAKNESSES));
	}

	/** Every roll at zero means nothing can miss, the opposite of "none". */
	@Test
	public void unmissableSaysAnything()
	{
		MonsterData m = monster(defences(0, 1, -100, -100, -100, -100, -100, -100, -100));

		assertEquals(List.of("Weakness: anything"), ExamineSummary.format(m, ExamineSummaryMode.WEAKNESSES));
	}

	/** No defensive data drops the line rather than printing a seven-way tie of zeroes. */
	@Test
	public void aBlankRowGetsNoWeaknessLine()
	{
		List<String> lines = ExamineSummary.format(monster("{}"), ExamineSummaryMode.ALL_DEFENCES);

		assertEquals(2, lines.size());
		assertEquals("Melee: Stab +0 | Slash +0 | Crush +0", lines.get(0));
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
