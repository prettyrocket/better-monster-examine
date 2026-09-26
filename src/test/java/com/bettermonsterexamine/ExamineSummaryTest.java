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

	/** Bonuses in the standard palette, which is all the Weakness line tests need. */
	private static List<String> format(MonsterData m, ExamineSummaryMode mode, boolean transparentChat)
	{
		return ExamineSummary.format(m, mode, transparentChat, false, HighlightMode.STANDARD);
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
		assertTrue(format(null, ExamineSummaryMode.ALL_DEFENCES, false).isEmpty());
		assertTrue(format(monster("{}"), null, false).isEmpty());
	}

	@Test
	public void allDefencesColoursWhatTheWeaknessLineWouldName()
	{
		// Magic wins alone, so it's green and the easiest free style (Crush) yellow; the element
		// rides beside magic, so the Weakness line has nothing left to say and is dropped.
		assertEquals(List.of(
			"Stab +20  Slash +20  <col=7a5c00>Crush +0</col>",
			"<col=006600>Magic +0</col> (<col=b22800>Fire 50%</col>)  Light +60  Std +40  Heavy +20"),
			format(monster(ICE_GIANT), ExamineSummaryMode.ALL_DEFENCES, false));
	}

	@Test
	public void allDefencesShowsRollsWhenAsked()
	{
		assertEquals(List.of(
			"Stab 4,116  Slash 4,116  <col=7a5c00>Crush 3,136</col>",
			"<col=006600>Magic 640</col> (<col=b22800>Fire 50%</col>)  Light 6,076  Std 5,096  Heavy 4,116"),
			ExamineSummary.format(monster(ICE_GIANT), ExamineSummaryMode.ALL_DEFENCES, false, true,
				HighlightMode.STANDARD));
	}

	@Test
	public void allDefencesHighlightsFollowThePaletteAndChatbox()
	{
		List<String> cb = ExamineSummary.format(monster(ICE_GIANT), ExamineSummaryMode.ALL_DEFENCES, true, false,
			HighlightMode.COLOUR_BLIND);
		assertTrue(cb.get(0).endsWith("<col=f0e442>Crush +0</col>"));
		assertTrue(cb.get(1).startsWith("<col=56b4e9>Magic +0</col>"));

		List<String> off = ExamineSummary.format(monster(ICE_GIANT), ExamineSummaryMode.ALL_DEFENCES, false, false,
			HighlightMode.OFF);
		assertEquals("Stab +20  Slash +20  Crush +0", off.get(0));
		assertTrue("The element keeps its own colour", off.get(1).startsWith("Magic +0 (<col=b22800>Fire 50%</col>)"));
	}

	@Test
	public void allDefencesFallsBackToBonusesWithoutEveryRollInput()
	{
		MonsterData m = monster("{\"stab_defence_bonus\":5}");

		assertEquals("Stab +5  Slash +0  Crush +0",
			ExamineSummary.format(m, ExamineSummaryMode.ALL_DEFENCES, false, true, HighlightMode.STANDARD).get(0));
	}

	@Test
	public void summaryNoLongerCarriesItsOwnNameHeader()
	{
		// The name moved onto the game's own Examine line, so the block starts with the stats.
		List<String> lines = format(monster(ICE_GIANT), ExamineSummaryMode.WEAKNESSES, false);

		assertEquals(1, lines.size());
		assertTrue(lines.get(0).startsWith("Weakness:"));
	}

	/** Magic wins, then the comma names the best style that costs no runes. */
	@Test
	public void magicIsFollowedByTheBestFreeStyle()
	{
		assertEquals(List.of("Weakness: Magic (<col=b22800>Fire</col>), Crush"),
			format(monster(ICE_GIANT), ExamineSummaryMode.WEAKNESSES, false));
	}

	/** The transparent chatbox is dark, so the element takes its lighter shade there. */
	@Test
	public void theElementShadeFollowsTheChatbox()
	{
		assertEquals(List.of("Weakness: Magic (<col=ff7a3d>Fire</col>), Crush"),
			format(monster(ICE_GIANT), ExamineSummaryMode.WEAKNESSES, true));
	}

	/** Each element has its own colour, rather than one colour standing in for all four. */
	@Test
	public void elementsAreColouredApart()
	{
		String water = defences(120, 1, 20, 10, 10, -10, -10, -10, 50, "water");
		String earth = defences(120, 1, 20, 10, 10, -10, -10, -10, 50, "earth");

		assertEquals(List.of("Weakness: Magic (<col=0038b8>Water</col>), Ranged"),
			format(monster(water), ExamineSummaryMode.WEAKNESSES, false));
		assertEquals(List.of("Weakness: Magic (<col=6e3b0e>Earth</col>), Ranged"),
			format(monster(earth), ExamineSummaryMode.WEAKNESSES, false));
	}

	/** When every free style is even there is no second answer to give. */
	@Test
	public void magicStandsAloneWhenFreeStylesAreEven()
	{
		MonsterData m = monster(defences(80, 1, 0, 0, 0, 0, 0, 0, 0));

		assertEquals(List.of("Weakness: Magic"), format(m, ExamineSummaryMode.WEAKNESSES, false));
	}

	/**
	 * A melee answer carries no elemental weakness: the element is a damage multiplier, and
	 * printing it beside a melee recommendation reads as an endorsement of casting.
	 */
	@Test
	public void aNonMagicWinnerDropsTheElement()
	{
		MonsterData m = monster(defences(100, 100, -15, -15, -15, 60, 60, 60, 100, "air"));

		List<String> lines = format(m, ExamineSummaryMode.WEAKNESSES, false);

		assertEquals(List.of("Weakness: Melee"), lines);
		assertFalse(lines.get(0).contains("Air"));
	}

	/** Styles within the tie factor are joined with a slash, and the element still stays off. */
	@Test
	public void aNarrowLeadIsATie()
	{
		MonsterData m = monster(defences(120, 100, 10, 20, 40, 60, 60, 60, 100, "air"));

		assertEquals(List.of("Weakness: Stab/Slash"), format(m, ExamineSummaryMode.WEAKNESSES, false));
	}

	@Test
	public void evenStylesSayNone()
	{
		MonsterData m = monster(defences(50, 50, 0, 0, 0, 0, 0, 0, 0));

		assertEquals(List.of("Weakness: none"), format(m, ExamineSummaryMode.WEAKNESSES, false));
	}

	/** Every roll at zero means nothing can miss, the opposite of "none". */
	@Test
	public void unmissableSaysAnything()
	{
		MonsterData m = monster(defences(0, 1, -100, -100, -100, -100, -100, -100, -100));

		assertEquals(List.of("Weakness: anything"), format(m, ExamineSummaryMode.WEAKNESSES, false));
	}

	/** No defensive data drops the line rather than printing a seven-way tie of zeroes. */
	@Test
	public void aBlankRowGetsNoWeaknessLine()
	{
		List<String> lines = format(monster("{}"), ExamineSummaryMode.ALL_DEFENCES, false);

		assertEquals(List.of("Stab +0  Slash +0  Crush +0", "Magic +0  Light +0  Std +0  Heavy +0"), lines);
		assertTrue(format(monster("{}"), ExamineSummaryMode.WEAKNESSES, false).isEmpty());
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
