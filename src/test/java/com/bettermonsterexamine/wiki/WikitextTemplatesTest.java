package com.bettermonsterexamine.wiki;

import java.util.Arrays;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class WikitextTemplatesTest
{
	// Vardorvis' infobox, trimmed: a footnote defined on max hit and referenced by name from str1.
	private static final String PAGE = "intro {{Infobox Monster\n"
		+ "|max hit1 = 32-43 ([[Melee]]){{efn|name=def|Scales linearly with HP.}}\n"
		+ "|str1 = 270-<br />360{{efn|name=def}}\n"
		+ "|attack style = [[Slash|slash]]\n"
		+ "}} body";

	@Test
	public void aTemplateEndsAtItsOwnClosingBraces()
	{
		String tpl = WikitextTemplates.template(PAGE, PAGE.indexOf("{{Infobox"));

		assertEquals(PAGE.substring(PAGE.indexOf("{{Infobox"), PAGE.lastIndexOf("}}") + 2), tpl);
		assertNull(WikitextTemplates.template("{{never closes", 0));
	}

	@Test
	public void barsInsideLinksAndTemplatesDoNotSplit()
	{
		assertEquals(Arrays.asList("a ", " [[x|y]] ", " {{t|u}}"), WikitextTemplates.split("a | [[x|y]] | {{t|u}}"));
		assertEquals(18, WikitextTemplates.indexOfBar("style=\"x\" [[a|b]] | cell", 0));
	}

	@Test
	public void readsNamedParametersRaw()
	{
		Map<String, String> p = WikitextTemplates.params(WikitextTemplates.template(PAGE, PAGE.indexOf("{{Infobox")));

		assertEquals(" 270-<br />360{{efn|name=def}}\n", p.get("str1"));
		assertEquals(" [[Slash|slash]]\n", p.get("attack style"));
	}

	@Test
	public void aFootnoteReferenceResolvesToItsDefinition()
	{
		Map<String, String> notes = WikitextTemplates.footnotes(PAGE);
		WikitextTemplates.Efn ref = WikitextTemplates.Efn.of(WikitextTemplates.parts("{{efn|name=def}}"));

		assertEquals("Scales linearly with HP.", notes.get("def"));
		assertEquals("Scales linearly with HP.", ref.resolve(notes));
		assertNull(WikitextTemplates.Efn.of(WikitextTemplates.parts("{{sic}}")));
	}
}
