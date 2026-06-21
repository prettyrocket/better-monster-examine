package com.bettermonsterexamine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class WikiInfoboxServiceTest
{
	@Test
	public void parsesSimpleInfoboxParams()
	{
		String wikitext = String.join("\n",
			"{{Infobox Monster",
			"|name = Goblin",
			"|aggressive = No",
			"|poisonous = No",
			"|max hit = 1",
			"}}");

		WikiInfo info = WikiInfoboxService.parse(wikitext);

		assertEquals("No", info.get("aggressive", null));
		assertEquals("1", info.get("max hit", null));
	}

	@Test
	public void firstValueWinsForDuplicateKeys()
	{
		// The monster infobox sits ahead of other templates; the first occurrence should win.
		String wikitext = String.join("\n",
			"|aggressive = Yes",
			"|aggressive = No");

		assertEquals("Yes", WikiInfoboxService.parse(wikitext).get("aggressive", null));
	}

	@Test
	public void getResolvesVersionedParamByName()
	{
		String wikitext = String.join("\n",
			"|version1 = Post-quest",
			"|version2 = Dragon Slayer II",
			"|max hit1 = 30",
			"|max hit2 = 32");

		WikiInfo info = WikiInfoboxService.parse(wikitext);

		assertEquals("32", info.get("max hit", "Dragon Slayer II"));
		assertEquals("30", info.get("max hit", "Post-quest"));
	}

	@Test
	public void getIsCaseInsensitiveOnVersionName()
	{
		String wikitext = String.join("\n",
			"|version1 = Post-quest",
			"|max hit1 = 30");

		assertEquals("30", WikiInfoboxService.parse(wikitext).get("max hit", "POST-QUEST"));
	}

	@Test
	public void getFallsBackFromVersionedToBaseThenNull()
	{
		String wikitext = String.join("\n",
			"|max hit1 = 30",
			"|poisonous = No");

		WikiInfo info = WikiInfoboxService.parse(wikitext);

		// Unknown version -> falls back to the "1" suffix.
		assertEquals("30", info.get("max hit", "Nonexistent"));
		// Unsuffixed param resolves with no version.
		assertEquals("No", info.get("poisonous", null));
		// Absent param -> null.
		assertNull(info.get("xpbonus", null));
	}

	@Test
	public void getStripsWikiLinkMarkup()
	{
		String wikitext = "|weakness = [[Magic]]\n|attribute = [[Dragon (attribute)|Dragon]]";

		WikiInfo info = WikiInfoboxService.parse(wikitext);

		assertEquals("Magic", info.get("weakness", null));
		assertEquals("Dragon", info.get("attribute", null));
	}

	@Test
	public void stripMarkupHandlesPipedAndPlainLinks()
	{
		assertEquals("Magic", WikiInfoboxService.stripMarkup("[[Magic]]"));
		assertEquals("Dragon", WikiInfoboxService.stripMarkup("[[Dragon (attribute)|Dragon]]"));
		assertEquals("plain text", WikiInfoboxService.stripMarkup("plain text"));
		assertNull(WikiInfoboxService.stripMarkup(null));
	}

	@Test
	public void emptyInfoReturnsNullForEveryLookup()
	{
		assertNull(WikiInfo.empty().get("max hit", null));
		assertNull(WikiInfo.empty().get("aggressive", "Some version"));
	}
}
