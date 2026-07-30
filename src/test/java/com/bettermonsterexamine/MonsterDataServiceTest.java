package com.bettermonsterexamine;

import com.google.gson.Gson;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.assertEquals;
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
}
