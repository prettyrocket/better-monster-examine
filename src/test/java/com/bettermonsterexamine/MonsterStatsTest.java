package com.bettermonsterexamine;

import com.google.gson.Gson;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class MonsterStatsTest
{
	private static final Gson GSON = new Gson();

	private static MonsterData monster(String json)
	{
		return GSON.fromJson(json, MonsterData.class);
	}

	private static WikiInfo wiki(String... lines)
	{
		return WikiInfoboxService.parse(String.join("\n", lines));
	}

	private static MonsterStats stats(MonsterData m, WikiInfo wi)
	{
		return new MonsterStats(m, wi, HighlightMode.STANDARD, 99);
	}

	@Test
	public void maxHitsSplitsWikiListAndFlagsOverHp()
	{
		MonsterData m = monster("{\"version\":\"\",\"max_hit\":\"32\"}");
		WikiInfo wi = wiki("|max hit = 30 (Magic), 28 (Ranged), 121 (Dragonfire Bomb/Special)");

		List<MonsterStats.MaxHitLine> hits = stats(m, wi).maxHits();

		assertEquals(3, hits.size());
		assertEquals("30 (Magic)", hits.get(0).text());
		assertFalse(hits.get(0).overHp());
		assertTrue("121 exceeds HP 99", hits.get(2).overHp());
	}

	@Test
	public void maxHitsFallsBackToDatasetWhenNoWiki()
	{
		MonsterData m = monster("{\"version\":\"\",\"max_hit\":\"10\"}");

		List<MonsterStats.MaxHitLine> hits = new MonsterStats(m, null, HighlightMode.STANDARD, 99).maxHits();

		assertEquals(1, hits.size());
		assertEquals("10", hits.get(0).text());
		assertFalse(hits.get(0).overHp());
	}

	@Test
	public void maxHitsSplitsOnCommaSpaceNotBareComma()
	{
		// R2: canonical split is ", " — a comma with no following space stays on one line.
		MonsterData m = monster("{\"version\":\"\"}");
		WikiInfo wi = wiki("|max hit = 5,10 (special)");

		List<MonsterStats.MaxHitLine> hits = stats(m, wi).maxHits();

		assertEquals(1, hits.size());
		assertEquals("5,10 (special)", hits.get(0).text());
	}

	@Test
	public void maxHitsNeverFlaggedWhenHighlightOff()
	{
		MonsterData m = monster("{\"version\":\"\"}");
		WikiInfo wi = wiki("|max hit = 999");

		MonsterStats s = new MonsterStats(m, wi, HighlightMode.OFF, 99);

		assertFalse(s.maxHits().get(0).overHp());
	}

	@Test
	public void poisonousAffirmativeWithQualifierIsDanger()
	{
		// R1: "Yes (venom)" is affirmative — danger — even though it isn't exactly "yes".
		MonsterData m = monster("{\"version\":\"\"}");
		MonsterStats.StatField pois = stats(m, wiki("|poisonous = Yes (venom)")).poisonous();

		assertEquals("Yes (venom)", pois.value());
		assertEquals(ColourRole.DANGER, pois.role());
		assertEquals("Can poison you.", pois.tooltip());
	}

	@Test
	public void poisonousNoIsNeutral()
	{
		MonsterData m = monster("{\"version\":\"\"}");
		MonsterStats.StatField pois = stats(m, wiki("|poisonous = No")).poisonous();

		assertEquals(ColourRole.NEUTRAL, pois.role());
		assertNull(pois.tooltip());
	}

	@Test
	public void aggressiveAbsentIsNullPresentYesIsDanger()
	{
		MonsterData m = monster("{\"version\":\"\"}");

		assertNull(stats(m, wiki("|poisonous = No")).aggressive());
		assertEquals(ColourRole.DANGER, stats(m, wiki("|aggressive = Yes")).aggressive().role());
	}

	@Test
	public void flatArmourSignDrivesRoleAndIsAbsentWhenZero()
	{
		MonsterData neg = monster("{\"version\":\"\",\"defensive\":{\"flat_armour\":-4}}");
		MonsterData pos = monster("{\"version\":\"\",\"defensive\":{\"flat_armour\":10}}");
		MonsterData zero = monster("{\"version\":\"\",\"defensive\":{\"flat_armour\":0}}");

		assertEquals(ColourRole.GOOD, stats(neg, null).flatArmour().role());
		assertEquals("-4", stats(neg, null).flatArmour().value());
		assertEquals(ColourRole.DANGER, stats(pos, null).flatArmour().role());
		assertNull(stats(zero, null).flatArmour());
	}

	@Test
	public void xpBonusSignsAndOmitsZero()
	{
		MonsterData m = monster("{\"version\":\"\"}");

		assertEquals("+77.5%", stats(m, wiki("|xpbonus = 77.5")).xpBonus().value());
		assertEquals(ColourRole.GOOD, stats(m, wiki("|xpbonus = 77.5")).xpBonus().role());
		assertEquals(ColourRole.DANGER, stats(m, wiki("|xpbonus = -50")).xpBonus().role());
		assertNull(stats(m, wiki("|xpbonus = 0")).xpBonus());
		assertNull(stats(m, wiki("|poisonous = No")).xpBonus());
	}

	@Test
	public void immunitiesResolveFromWiki()
	{
		MonsterData m = monster("{\"version\":\"\"}");
		WikiInfo wi = wiki(
			"|poisonresistance = 100",
			"|venomresistance = 50",
			"|immunecannon = Yes",
			"|immunethrall = No");

		MonsterStats s = stats(m, wi);

		assertEquals("Immune", s.poison().value());
		assertEquals("50% resistance", s.venom().value());
		assertEquals(ColourRole.DANGER, s.cannon().role());
		assertNull(s.thrall());
	}

	@Test
	public void wikiAbsentLeavesDatasetFieldsButNoWikiFields()
	{
		MonsterData m = monster("{\"version\":\"\",\"size\":7,\"attributes\":[\"dragon\"],"
			+ "\"skills\":{\"hp\":600,\"atk\":255,\"str\":255,\"def\":150,\"magic\":255,\"ranged\":255}}");

		MonsterStats s = new MonsterStats(m, null, HighlightMode.STANDARD, 99);

		assertFalse(s.wikiLoaded());
		assertEquals("7x7, Draconic", s.sizeAttr());
		assertEquals(6, s.combatLevels().size());
		assertEquals("600", s.combatLevels().get(0));
		assertNull(s.aggressive());
		assertNull(s.poisonous());
		assertNull(s.xpBonus());
		assertNull(s.poison());
	}
}
