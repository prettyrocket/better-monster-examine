package com.bettermonsterexamine;

import com.google.gson.Gson;
import java.util.List;
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
	public void slayerCategoriesAndMastersJoinAndCapitalise()
	{
		MonsterData m = monster("{\"slayer_category\":[\"Blue dragons\",\"Bosses\"],"
			+ "\"assigned_by\":[\"duradel\",\"nieve\"]}");

		assertEquals("Blue dragons, Bosses", stats(m).slayerCategories());
		assertEquals("Duradel, Nieve", stats(m).slayerMasters());
		assertNull(stats(monster("{}")).slayerCategories());
		assertNull(stats(monster("{}")).slayerMasters());
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
		assertEquals("600", s.combatLevels().get(0));
		assertNull(s.poisonous());
		assertNull(s.xpBonus());
		assertNull(s.cannon());
	}
}
