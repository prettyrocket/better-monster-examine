package com.bettermonsterexamine;

import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class MonsterDataServiceTest
{
	private static final List<String> NAMES = Arrays.asList(
		"Goblin", "Cave goblin", "Goblin Champion", "Hobgoblin", "Cow");

	private static final Gson GSON = new Gson();

	private static MonsterData monster(String json)
	{
		return GSON.fromJson(json, MonsterData.class);
	}

	@Test
	public void exactLevelFallbackDoesNotMapACombatlessPetToItsNamesakeMonster()
	{
		MonsterData level13 = monster("{\"name\":\"Rock Golem\",\"combat_level\":13}");
		MonsterData level27 = monster("{\"name\":\"Rock Golem\",\"combat_level\":27}");
		List<MonsterData> variants = Arrays.asList(level13, level27);

		assertNull(MonsterDataService.variantForLevel(variants, -1));
		assertSame(level27, MonsterDataService.variantForLevel(variants, 27));
	}

	@Test
	public void filtersBySubstringCaseInsensitively()
	{
		List<String> result = MonsterDataService.matchNames(NAMES, "GOBLIN", 40);

		assertEquals(Arrays.asList("Goblin", "Cave goblin", "Goblin Champion", "Hobgoblin"), result);
		assertTrue(result.stream().noneMatch(n -> n.equals("Cow")));
	}

	@Test
	public void floatsExactMatchToTop()
	{
		// "Goblin" is an exact match and must rank ahead of alphabetically-earlier matches.
		List<String> result = MonsterDataService.matchNames(NAMES, "goblin", 40);

		assertEquals("Goblin", result.get(0));
	}

	@Test
	public void nonExactMatchesStayAlphabetical()
	{
		List<String> result = MonsterDataService.matchNames(NAMES, "gob", 40);

		assertEquals(Arrays.asList("Cave goblin", "Goblin", "Goblin Champion", "Hobgoblin"), result);
	}

	@Test
	public void respectsLimit()
	{
		assertEquals(2, MonsterDataService.matchNames(NAMES, "goblin", 2).size());
	}

	@Test
	public void emptyQueryReturnsEverythingUpToLimit()
	{
		assertEquals(NAMES.size(), MonsterDataService.matchNames(NAMES, "   ", 40).size());
	}

	@Test
	public void noMatchesReturnsEmpty()
	{
		assertTrue(MonsterDataService.matchNames(NAMES, "dragon", 40).isEmpty());
	}

	// ---- variant labelling / default pick across pages (#60) ------------------
	//
	// Shellbane gryphon: the boss article and its quest fight both emit rows named "Shellbane
	// gryphon" with a blank anchor and the same combat level, so neither the anchor nor the level
	// can separate them.

	private static final String BOSS =
		"{\"name\":\"Shellbane gryphon\",\"page_name\":\"Shellbane gryphon\","
			+ "\"combat_level\":235,\"hitpoints\":400,\"max_hit\":[\"22 <br/> 64 (whirlwinds)\"]}";
	private static final String QUEST =
		"{\"name\":\"Shellbane gryphon\",\"page_name\":\"Shellbane gryphon (Troubled Tortugans)\","
			+ "\"combat_level\":235,\"hitpoints\":400,\"max_hit\":[\"22\"]}";

	@Test
	public void labelsAForeignPageByItsQualifierNotAHashSuffix()
	{
		MonsterData boss = monster(BOSS);
		MonsterData quest = monster(QUEST);

		// Quest row first, as Bucket returns it.
		MonsterDataService.assignVersions(Arrays.asList(quest, boss));

		assertEquals("Troubled Tortugans", quest.getVersion());
		assertEquals("The monster's own article keeps the plain form", "", boss.getVersion());
	}

	@Test
	public void defaultsToTheMonstersOwnArticleNotAPageReusingItsName()
	{
		MonsterData boss = monster(BOSS);
		MonsterData quest = monster(QUEST);
		List<MonsterData> group = Arrays.asList(quest, boss);
		MonsterDataService.assignVersions(group);

		assertSame(boss, MonsterDataService.defaultVariant(group));
	}

	@Test
	public void aForeignPageCannotWinOnCombatLevel()
	{
		// The article's own rows are lower-level than the page reusing the name, so the old
		// highest-level tie-break would have handed the default to the foreign row.
		MonsterData weak = monster("{\"name\":\"Gryphon\",\"page_name\":\"Gryphon\","
			+ "\"combat_level\":100,\"hitpoints\":100}");
		MonsterData strong = monster("{\"name\":\"Gryphon\",\"page_name\":\"Gryphon\","
			+ "\"combat_level\":120,\"hitpoints\":120}");
		MonsterData foreign = monster("{\"name\":\"Gryphon\",\"page_name\":\"Gryphon (quest)\","
			+ "\"combat_level\":235,\"hitpoints\":400}");
		List<MonsterData> group = Arrays.asList(foreign, weak, strong);
		MonsterDataService.assignVersions(group);

		assertSame(strong, MonsterDataService.defaultVariant(group));
	}

	@Test
	public void severalRowsFromTheOwnArticleStayDistinct()
	{
		// "" bypasses the uniqueness guard, so the own-page rows must fall back to their level.
		MonsterData weak = monster("{\"name\":\"Gryphon\",\"page_name\":\"Gryphon\","
			+ "\"combat_level\":100,\"hitpoints\":100}");
		MonsterData strong = monster("{\"name\":\"Gryphon\",\"page_name\":\"Gryphon\","
			+ "\"combat_level\":120,\"hitpoints\":120}");
		MonsterData foreign = monster("{\"name\":\"Gryphon\",\"page_name\":\"Gryphon (quest)\","
			+ "\"combat_level\":235,\"hitpoints\":400}");

		MonsterDataService.assignVersions(Arrays.asList(foreign, weak, strong));

		assertEquals("Level 100", weak.getVersion());
		assertEquals("Level 120", strong.getVersion());
		assertEquals("quest", foreign.getVersion());
	}

	@Test
	public void anchoredVariantsOnOnePageAreUnaffected()
	{
		MonsterData quest = monster("{\"name\":\"Vardorvis\",\"page_name\":\"Vardorvis\","
			+ "\"version_anchor\":\"Quest\",\"combat_level\":572,\"hitpoints\":500}");
		MonsterData awakened = monster("{\"name\":\"Vardorvis\",\"page_name\":\"Vardorvis\","
			+ "\"version_anchor\":\"Awakened\",\"combat_level\":1136,\"hitpoints\":1400}");

		MonsterDataService.assignVersions(Arrays.asList(quest, awakened));

		assertEquals("Quest", quest.getVersion());
		assertEquals("Awakened", awakened.getVersion());
		// No row is flagged default and none is "standard", so the highest level still wins.
		assertSame(awakened, MonsterDataService.defaultVariant(Arrays.asList(quest, awakened)));
	}

	@Test
	public void aLoneRowWithNoPageStillGetsThePlainForm()
	{
		MonsterData only = monster("{\"name\":\"Cow\",\"combat_level\":2,\"hitpoints\":8}");

		MonsterDataService.assignVersions(Arrays.asList(only));

		assertEquals("", only.getVersion());
	}

	@Test
	public void collidingAnchorsAreSplitByPageNotByLevel()
	{
		// The wiki names Goblin's variants "Level 13" on three different pages, so the old
		// anchor-plus-level fallback restated the level and fell through to "#2"/"#3".
		MonsterData main = monster("{\"name\":\"Goblin\",\"page_name\":\"Goblin\","
			+ "\"version_anchor\":\"Level 13\",\"combat_level\":13,\"hitpoints\":20}");
		MonsterData vault = monster("{\"name\":\"Goblin\",\"page_name\":\"Goblin (Vault of War)\","
			+ "\"version_anchor\":\"Level 13\",\"combat_level\":13,\"hitpoints\":20}");
		MonsterData gwd = monster("{\"name\":\"Goblin\",\"page_name\":\"Goblin (God Wars Dungeon)\","
			+ "\"version_anchor\":\"Level 13\",\"combat_level\":13,\"hitpoints\":20}");

		MonsterDataService.assignVersions(Arrays.asList(main, vault, gwd));

		assertEquals("Level 13", main.getVersion());
		assertEquals("Level 13 (Vault of War)", vault.getVersion());
		assertEquals("Level 13 (God Wars Dungeon)", gwd.getVersion());
	}

	@Test
	public void collidingAnchorsOnOnePageStillFallBackToTheLevel()
	{
		// No page to distinguish them, so the pre-existing level fallback must stay.
		MonsterData a = monster("{\"name\":\"Guard\",\"page_name\":\"Guard\","
			+ "\"version_anchor\":\"Falador\",\"combat_level\":21,\"hitpoints\":22}");
		MonsterData b = monster("{\"name\":\"Guard\",\"page_name\":\"Guard\","
			+ "\"version_anchor\":\"Falador\",\"combat_level\":22,\"hitpoints\":22}");

		MonsterDataService.assignVersions(Arrays.asList(a, b));

		assertEquals("Falador (lvl 21)", a.getVersion());
		assertEquals("Falador (lvl 22)", b.getVersion());
	}

	// ---- relevant variants: appearance duplicates and dead content (#62 / #63) -----

	private static String giant(String anchor, String page, String id)
	{
		return "{\"name\":\"Hill Giant\",\"page_name\":\"" + page + "\",\"version_anchor\":\"" + anchor
			+ "\",\"id\":[\"" + id + "\"],\"combat_level\":28,\"hitpoints\":35,\"attack_bonus\":18}";
	}

	@Test
	public void appearanceOnlyVariantsCollapseToOne()
	{
		List<MonsterData> kept = MonsterDataService.relevantVariants(Arrays.asList(
			monster(giant("1", "Hill Giant", "3059")),
			monster(giant("Helmetless", "Hill Giant", "3060")),
			monster(giant("Shirtless", "Hill Giant", "3061")),
			monster(giant("Brassard", "Hill Giant", "3062"))));

		assertEquals(1, kept.size());
	}

	@Test
	public void collapsingCarriesEverySpawnIdOntoTheSurvivor()
	{
		// Right-click resolves by id first, so a dropped row's ids must not be stranded.
		List<MonsterData> kept = MonsterDataService.relevantVariants(Arrays.asList(
			monster(giant("1", "Hill Giant", "3059")),
			monster(giant("Helmetless", "Hill Giant", "3060")),
			monster(giant("Shirtless", "Hill Giant", "3061"))));

		assertEquals(1, kept.size());
		assertEquals(Arrays.asList("3059", "3060", "3061"), kept.get(0).getIds());
	}

	@Test
	public void genuinelyDifferentStatsAreNotCollapsed()
	{
		MonsterData weak = monster("{\"name\":\"Goblin\",\"page_name\":\"Goblin\","
			+ "\"version_anchor\":\"Level 2\",\"combat_level\":2,\"hitpoints\":12}");
		MonsterData strong = monster("{\"name\":\"Goblin\",\"page_name\":\"Goblin\","
			+ "\"version_anchor\":\"Level 13\",\"combat_level\":13,\"hitpoints\":20}");

		assertEquals(2, MonsterDataService.relevantVariants(Arrays.asList(weak, strong)).size());
	}

	@Test
	public void removedContentIsDroppedWhenALiveVariantRemains()
	{
		MonsterData live = monster("{\"name\":\"Goblin\",\"page_name\":\"Goblin\","
			+ "\"version_anchor\":\"Level 5\",\"combat_level\":5,\"hitpoints\":15}");
		MonsterData old = monster("{\"name\":\"Goblin\",\"page_name\":\"Goblin (historical)\","
			+ "\"version_anchor\":\"Green (original)\",\"combat_level\":5,\"hitpoints\":99}");

		List<MonsterData> kept = MonsterDataService.relevantVariants(Arrays.asList(live, old));

		assertEquals(1, kept.size());
		assertSame(live, kept.get(0));
	}

	@Test
	public void questContentIsKeptEvenThoughItsPageLooksLikeAFlashback()
	{
		// Realm of Memories reads like removed content but is live and fightable: real hitpoints,
		// real spawn ids. Dropping it would strand those ids and break right-click on them.
		MonsterData live = monster("{\"name\":\"Hill Giant\",\"page_name\":\"Hill Giant\","
			+ "\"combat_level\":28,\"hitpoints\":35,\"id\":[\"3059\"]}");
		MonsterData quest = monster("{\"name\":\"Hill Giant\",\"page_name\":\"Hill Giant (Realm of Memories)\","
			+ "\"hitpoints\":35,\"id\":[\"11225\"]}");

		List<MonsterData> kept = MonsterDataService.relevantVariants(Arrays.asList(live, quest));

		assertEquals(2, kept.size());
	}

	@Test
	public void aMonsterThatIsEntirelyRemovedContentSurvives()
	{
		// Barbarian woman has no live row at all; dropping it would erase the monster from search,
		// which is worse than showing an outdated form.
		MonsterData only = monster("{\"name\":\"Barbarian woman\","
			+ "\"page_name\":\"Barbarian woman (historical)\",\"combat_level\":8,\"hitpoints\":0}");

		assertEquals(1, MonsterDataService.relevantVariants(Arrays.asList(only)).size());
	}

	@Test
	public void aLiveVariantOutranksAForeignPageAsTheSurvivor()
	{
		MonsterData foreign = monster(giant("1", "Hill Giant (Nightmare Zone)", "3059"));
		MonsterData own = monster(giant("2", "Hill Giant", "3060"));

		List<MonsterData> kept = MonsterDataService.relevantVariants(Arrays.asList(foreign, own));

		assertEquals(1, kept.size());
		assertSame("the monster's own article represents the pair", own, kept.get(0));
		assertTrue(kept.get(0).getIds().contains("3059"));
	}

	@Test
	public void aCachePredatingPageNameCountsAsStale()
	{
		// Parses fine, but carries none of the data the current build reasons over — serving it
		// would make the fix look broken until MAX_AGE elapsed.
		List<MonsterData> old = Arrays.asList(
			monster("{\"name\":\"Cow\",\"combat_level\":2}"),
			monster("{\"name\":\"Goblin\",\"combat_level\":2}"));

		assertFalse(MonsterDataService.hasCurrentFields(old));
	}

	@Test
	public void aCacheCarryingPageNameIsUsable()
	{
		assertTrue(MonsterDataService.hasCurrentFields(Arrays.asList(monster(BOSS), monster(QUEST))));
	}

	@Test
	public void anEmptyOrNullCacheIsNotMistakenForCurrent()
	{
		assertFalse(MonsterDataService.hasCurrentFields(null));
		assertFalse(MonsterDataService.hasCurrentFields(Collections.emptyList()));
	}
}
