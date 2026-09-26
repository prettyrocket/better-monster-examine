package com.bettermonsterexamine;

import com.google.gson.Gson;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Locks the view-model semantics over the flat Bucket {@link MonsterData} DTO (the regression net
 * for the cutover): max-hit flagging, the affirmative poisonous check, flat-armour/XP-bonus signs,
 * and the immunity flags.
 */
public class MonsterStatsTest
{
	private static final Gson GSON = new Gson();

	private static MonsterData monster(String json)
	{
		return GSON.fromJson(json, MonsterData.class);
	}

	private static MonsterStats stats(MonsterData m)
	{
		return new MonsterStats(m, HighlightMode.STANDARD, 99, 99);
	}

	@Test
	public void maxHitsListsEachValueAndFlagsOverHp()
	{
		MonsterData m = monster("{\"max_hit\":[\"30 (Magic)\",\"28 (Ranged)\",\"121 (Dragonfire Bomb/Special)\"]}");

		List<MonsterStats.MaxHitLine> hits = stats(m).maxHits();

		assertEquals(3, hits.size());
		assertEquals("30 (Magic)", hits.get(0).text());
		assertFalse(hits.get(0).overHp());
		assertTrue("121 exceeds HP 99", hits.get(2).overHp());
	}

	@Test
	public void maxHitsFallsBackToDashWhenAbsent()
	{
		List<MonsterStats.MaxHitLine> hits = stats(monster("{}")).maxHits();

		assertEquals(1, hits.size());
		assertEquals("—", hits.get(0).text());
		assertFalse(hits.get(0).overHp());
	}

	@Test
	public void maxHitsNeverFlaggedWhenHighlightOff()
	{
		MonsterData m = monster("{\"max_hit\":[\"999\"]}");

		MonsterStats s = new MonsterStats(m, HighlightMode.OFF, 99, 99);

		assertFalse(s.maxHits().get(0).overHp());
	}

	@Test
	public void poisonousAffirmativeWithLinkIsSanitisedAndDanger()
	{
		// Bucket carries "Yes ([[venom]])"; the link is stripped and the value flagged danger.
		MonsterStats.StatField pois = stats(monster("{\"poisonous\":\"Yes ([[venom]])\"}")).poisonous();

		assertEquals("Yes (venom)", pois.value());
		assertEquals(ColourRole.DANGER, pois.role());
		assertEquals("Can poison you.", pois.tooltip());
	}

	@Test
	public void poisonousNoIsNeutral()
	{
		MonsterStats.StatField pois = stats(monster("{\"poisonous\":\"No\"}")).poisonous();

		assertEquals(ColourRole.NEUTRAL, pois.role());
		assertNull(pois.tooltip());
	}

	@Test
	public void flatArmourSignDrivesRoleAndIsAbsentWhenZero()
	{
		assertEquals(ColourRole.GOOD, stats(monster("{\"flat_armour\":-4}")).flatArmour().role());
		assertEquals("-4", stats(monster("{\"flat_armour\":-4}")).flatArmour().value());
		assertEquals(ColourRole.DANGER, stats(monster("{\"flat_armour\":10}")).flatArmour().role());
		assertNull(stats(monster("{\"flat_armour\":0}")).flatArmour());
		assertNull(stats(monster("{}")).flatArmour());
	}

	@Test
	public void xpBonusSignsAndOmitsZero()
	{
		assertEquals("+77.5%", stats(monster("{\"experience_bonus\":77.5}")).xpBonus().value());
		assertEquals(ColourRole.GOOD, stats(monster("{\"experience_bonus\":77.5}")).xpBonus().role());
		assertEquals(ColourRole.DANGER, stats(monster("{\"experience_bonus\":-50}")).xpBonus().role());
		assertNull(stats(monster("{\"experience_bonus\":0}")).xpBonus());
		assertNull(stats(monster("{}")).xpBonus());
	}

	@Test
	public void immunitiesResolveFromBucketFlags()
	{
		MonsterData m = monster("{\"cannon_immune\":\"Immune\",\"thrall_immune\":\"Not immune\","
			+ "\"burn_immune\":\"Immune (weak)\"}");

		MonsterStats s = stats(m);

		assertEquals("Immune", s.cannon().value());
		assertEquals(ColourRole.DANGER, s.cannon().role());
		assertNull(s.thrall());
		assertEquals("Immune (weak)", s.burn());
	}

	@Test
	public void slayerMonsterOnlyWhenCategoryPresent()
	{
		assertTrue(stats(monster("{\"slayer_category\":[\"Abyssal demons\"]}")).slayerMonster());
		assertFalse(stats(monster("{\"slayer_level\":85}")).slayerMonster());
		assertFalse(stats(monster("{}")).slayerMonster());
	}

	@Test
	public void slayerRequirementFlagsDangerOnlyWhenPlayerBelow()
	{
		MonsterData m = monster("{\"slayer_category\":[\"Abyssal demons\"],\"slayer_level\":85}");

		// Player at 84 can't damage it yet → danger; at 85 or with unknown level → neutral.
		assertEquals("85", new MonsterStats(m, HighlightMode.STANDARD, 99, 84).slayerRequirement().value());
		assertEquals(ColourRole.DANGER, new MonsterStats(m, HighlightMode.STANDARD, 99, 84).slayerRequirement().role());
		assertEquals(ColourRole.NEUTRAL, new MonsterStats(m, HighlightMode.STANDARD, 99, 85).slayerRequirement().role());
		assertEquals(ColourRole.NEUTRAL, new MonsterStats(m, HighlightMode.STANDARD, 99, -1).slayerRequirement().role());
	}

	@Test
	public void slayerRequirementFloorsAtOneAndStaysNeutral()
	{
		// No listed requirement: the wiki shows level 1, which every player meets.
		MonsterStats.StatField req = new MonsterStats(monster("{\"slayer_category\":[\"Zombies\"]}"),
			HighlightMode.STANDARD, 99, 1).slayerRequirement();

		assertEquals("1", req.value());
		assertEquals(ColourRole.NEUTRAL, req.role());
		assertNull(req.tooltip());
	}

	@Test
	public void slayerXpFormatsAndOmitsZero()
	{
		assertEquals("150", stats(monster("{\"slayer_experience\":150.0}")).slayerXp());
		assertEquals("18.5", stats(monster("{\"slayer_experience\":18.5}")).slayerXp());
		assertNull(stats(monster("{\"slayer_experience\":0}")).slayerXp());
		assertNull(stats(monster("{}")).slayerXp());
	}

	@Test
	public void slayerCategoriesAndMastersAsLists()
	{
		MonsterData m = monster("{\"slayer_category\":[\"Blue dragons\",\"Bosses\"],"
			+ "\"assigned_by\":[\"duradel\",\"nieve\"]}");

		assertEquals(List.of("Blue dragons", "Bosses"), stats(m).slayerCategories());
		assertEquals(List.of("duradel", "nieve"), stats(m).slayerMasters());
		assertTrue(stats(monster("{}")).slayerCategories().isEmpty());
		assertTrue(stats(monster("{}")).slayerMasters().isEmpty());
	}

	@Test
	public void slayerCategoriesCleanWikiMarkupDropJunkAndDedupe()
	{
		// Wikilinks are unwrapped, "No"/"None" placeholders dropped, and duplicates removed in order.
		MonsterData m = monster("{\"slayer_category\":[\"[[Blue dragon|Blue dragons]]\",\"No\",\"None\",\"Bosses\",\"Bosses\"]}");

		assertEquals(List.of("Blue dragons", "Bosses"), stats(m).slayerCategories());
	}

	@Test
	public void slayerMastersNormaliseCaseFoldKonarAndDropJunk()
	{
		// Bucket carries mixed casing, Konar's full name, duplicates and "No"/"None" placeholders.
		MonsterData m = monster("{\"assigned_by\":[\"Duradel\",\"duradel\",\"Konar quo Maten\",\"No\",\"None\"]}");

		assertEquals(List.of("duradel", "konar"), stats(m).slayerMasters());
	}

	@Test
	public void sizeAttributesAndCombatLevelsRender()
	{
		MonsterData m = monster("{\"size\":7,\"attribute\":[\"dragon\",\"undead\"],"
			+ "\"hitpoints\":600,\"attack_level\":255,\"strength_level\":255,\"defence_level\":150,"
			+ "\"magic_level\":255,\"ranged_level\":255}");

		MonsterStats s = stats(m);

		assertEquals("7x7, Draconic, Undead", s.sizeAttr());
		assertEquals(6, s.combatLevels().size());
		assertEquals("600", s.combatLevels().get(0).value());
		assertEquals("255", s.combatLevels().get(2).value());
		assertNull(s.poisonous());
		assertNull(s.xpBonus());
		assertNull(s.cannon());
	}

	/**
	 * Vardorvis: Bucket omits a level it can't type as an integer, so the row arrives with no
	 * strength_level at all and the level came out as a dash. The range the service recovers from
	 * the page takes its place, and the wiki's footnote explains why Defence counts *down*.
	 */
	@Test
	public void aLevelBucketDroppedRendersTheWikiRangeAndItsFootnote()
	{
		MonsterData m = monster("{\"hitpoints\":700,\"attack_level\":280,\"magic_level\":215}");
		String note = "Scales linearly with Vardorvis' remaining HP.";
		m.setLevelRanges(Map.of(
			"strength_level", new InfoboxLevels.LevelText("270-360", note),
			"defence_level", new InfoboxLevels.LevelText("215-145", note)));

		MonsterStats s = stats(m);

		assertEquals("270-360", s.combatLevels().get(2).value());
		assertEquals("215-145", s.combatLevels().get(3).value());
		assertEquals(note, s.combatLevels().get(3).tooltip());
		// Levels Bucket does carry are untouched, and a genuinely absent one still reads as a dash.
		assertEquals("280", s.combatLevels().get(1).value());
		assertEquals("—", s.combatLevels().get(5).value());
	}

	@Test
	public void defenceRollsUseTheMatchingLevel()
	{
		MonsterStats s = stats(monster("{\"defence_level\":20,\"magic_level\":1,"
			+ "\"stab_defence_bonus\":10,\"slash_defence_bonus\":20,\"crush_defence_bonus\":30,"
			+ "\"magic_defence_bonus\":50,\"light_range_defence_bonus\":0,"
			+ "\"standard_range_defence_bonus\":5,\"heavy_range_defence_bonus\":-70}"));

		assertEquals(List.of("2,146", "2,436", "2,726"), s.meleeDefenceRoll());
		assertEquals("Magic rolls off Magic level 1, not Defence", "1,140", s.magicDefenceRoll());
		assertEquals("A bonus below -64 clamps to 0", List.of("1,856", "2,001", "0"), s.rangedDefenceRoll());
	}

	@Test
	public void defenceRollsAbsentWithoutEveryInput()
	{
		MonsterStats s = stats(monster("{\"defence_level\":20,\"stab_defence_bonus\":10}"));

		assertNull(s.meleeDefenceRoll());
		assertNull(s.magicDefenceRoll());
		assertNull(s.rangedDefenceRoll());
	}

	@Test
	public void defenceRollRolesFollowTheChatRanking()
	{
		// Magic wins alone (Magic level 1); Light is the easiest free style, Standard ties with it.
		Map<DefenceRolls.Style, ColourRole> roles = stats(monster("{\"defence_level\":20,\"magic_level\":1,"
			+ "\"stab_defence_bonus\":60,\"slash_defence_bonus\":60,\"crush_defence_bonus\":60,"
			+ "\"magic_defence_bonus\":50,\"light_range_defence_bonus\":0,"
			+ "\"standard_range_defence_bonus\":5,\"heavy_range_defence_bonus\":60}")).defenceRollRoles();

		assertEquals(ColourRole.GOOD, roles.get(DefenceRolls.Style.MAGIC));
		assertEquals(ColourRole.NEXT, roles.get(DefenceRolls.Style.LIGHT));
		assertEquals("Within the tie band, so the same colour", ColourRole.NEXT, roles.get(DefenceRolls.Style.STANDARD));
		assertNull(roles.get(DefenceRolls.Style.STAB));
		assertNull(roles.get(DefenceRolls.Style.HEAVY));
	}

	@Test
	public void defenceRollRolesEmptyWhenNoStyleStandsOut()
	{
		assertTrue(stats(monster("{\"defence_level\":1,\"magic_level\":1,"
			+ "\"stab_defence_bonus\":0,\"slash_defence_bonus\":0,\"crush_defence_bonus\":0,"
			+ "\"magic_defence_bonus\":0,\"light_range_defence_bonus\":0,"
			+ "\"standard_range_defence_bonus\":0,\"heavy_range_defence_bonus\":0}")).defenceRollRoles().isEmpty());
	}
}
