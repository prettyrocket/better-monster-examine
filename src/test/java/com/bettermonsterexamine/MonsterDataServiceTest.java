package com.bettermonsterexamine;

import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
