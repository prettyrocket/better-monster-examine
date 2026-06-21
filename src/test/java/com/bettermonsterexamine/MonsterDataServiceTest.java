package com.bettermonsterexamine;

import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class MonsterDataServiceTest
{
	private static final List<String> NAMES = Arrays.asList(
		"Goblin", "Cave goblin", "Goblin Champion", "Hobgoblin", "Cow");

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
}
