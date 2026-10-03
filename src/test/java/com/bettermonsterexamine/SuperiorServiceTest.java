package com.bettermonsterexamine;

import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The superior table parse, against rows lifted from the wiki's <i>Superior slayer monster</i> page:
 * plain pairs, piped links, and the rowspans that let several monsters share one superior.
 */
public class SuperiorServiceTest
{
	private static final String TABLE = String.join("\n",
		"Superior slayer monsters are stronger variants.",
		"{| class=\"wikitable sortable\"",
		"!{{SCP|Slayer}}<br/>Level",
		"!Normal variant",
		"!Superior variant",
		"!class=\"unsortable\"|Image",
		"|-",
		"|5",
		"|[[Crawling Hand|Crawling hand]]",
		"|[[Crushing hand]]",
		"|[[File:Crushing hand.png|100x100px|centre]]",
		"|45",
		"|-",
		"|rowspan=\"2\" |25",
		"|[[Cockatrice]]",
		"|rowspan=\"2\" |[[Cockathrice]]",
		"|rowspan=\"2\" |[[File:Cockathrice.png|100x100px|centre]]",
		"|{{No|rowspan=2}}",
		"|-",
		"|[[Moonlight Cockatrice|Moonlight cockatrice]]",
		"|-",
		"|rowspan=\"2\" |80",
		"|[[Nechryael]]",
		"| rowspan=\"2\" |[[Nechryarch]]",
		"|{{No}}",
		"|-",
		"|[[Greater Nechryael|Greater nechryael]]",
		"|{{Yes}}",
		"|-",
		"|85",
		"|[[Abyssal demon]]",
		"|[[Greater abyssal demon]]",
		"|}",
		"",
		"==Changes==",
		"{| class=\"wikitable\"",
		"|[[Not a monster]]",
		"|[[Also not]]",
		"|}");

	@Test
	public void readsAPlainPairByPageTitle()
	{
		Map<String, String> s = SuperiorService.parse(TABLE);
		assertEquals("Crushing hand", s.get("Crawling Hand"));
		assertEquals("Greater abyssal demon", s.get("Abyssal demon"));
	}

	@Test
	public void aSuperiorSpanningRowsCoversEveryMonsterUnderIt()
	{
		Map<String, String> s = SuperiorService.parse(TABLE);
		assertEquals("Cockathrice", s.get("Cockatrice"));
		assertEquals("Cockathrice", s.get("Moonlight Cockatrice"));
		assertEquals("Nechryarch", s.get("Nechryael"));
		assertEquals("Nechryarch", s.get("Greater Nechryael"));
	}

	@Test
	public void onlyTheSuperiorTableIsRead()
	{
		Map<String, String> s = SuperiorService.parse(TABLE);
		assertNull(s.get("Not a monster"));
		assertNull(s.get("Cockathrice"));
		assertEquals(6, s.size());
	}

	@Test
	public void aPageWithoutTheTableYieldsNothing()
	{
		assertTrue(SuperiorService.parse("No table here.").isEmpty());
	}
}
