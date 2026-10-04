package com.bettermonsterexamine;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Locks the shapes the Bucket API leaves in its strings, using the real strings observed for the
 * monsters whose markup leaked into the panel (Tormented Demon, Vardorvis, Stranger), the
 * {@code {{sic}}} and multi-examine rows, plus clean cases (Vorkath, Blue Moon).
 */
public class WikiSanitizerTest
{
	/** Wrap content in the U+007F delimiters of a MediaWiki strip-marker, as Bucket returns it. */
	private static String marker(String inner)
	{
		String del = String.valueOf((char) 0x7f);
		return del + inner + del;
	}

	@Test
	public void plainlistDivUnwrapsToBulletValues()
	{
		// Tormented Demon: one array element wraps a <div class="plainlist"> + * bullets.
		List<String> raw = Collections.singletonList(
			"<div class=\"plainlist \" >\n*31 (auto)\n*45 (special)\n</div>");

		assertEquals(Arrays.asList("31 (auto)", "45 (special)"), WikiSanitizer.lines(raw));
	}

	@Test
	public void stripMarkersDroppedKeepingTheValue()
	{
		// Vardorvis: a <ref> footnote strip-marker trails the first value; second is clean.
		List<String> raw = Arrays.asList(
			"30-37 (Melee)" + marker("UNIQ--ref-00000049-QINU"),
			"?? (axes)");

		assertEquals(Arrays.asList("30-37 (Melee)", "?? (axes)"), WikiSanitizer.lines(raw));
	}

	@Test
	public void brSplitsASingleElementIntoLines()
	{
		List<String> raw = Collections.singletonList("16 (Stab)<br/>50 (Dragonfire)");

		assertEquals(Arrays.asList("16 (Stab)", "50 (Dragonfire)"), WikiSanitizer.lines(raw));
	}

	@Test
	public void cleanMultiValueArrayPassesThrough()
	{
		// Vorkath: already-clean per-style array — unchanged.
		List<String> raw = Arrays.asList("30 (Magic)", "28 (Ranged)", "121 (Dragonfire Bomb/Special)");

		assertEquals(raw, WikiSanitizer.lines(raw));
	}

	@Test
	public void nullAndEmptyElementsAreSkipped()
	{
		List<String> raw = Arrays.asList("32", null, "", "  ");

		assertEquals(Collections.singletonList("32"), WikiSanitizer.lines(raw));
	}

	@Test
	public void textStripsWikilinks()
	{
		// Vorkath poisonous = "Yes ([[venom]])"; piped links keep the label.
		assertEquals("Yes (venom)", WikiSanitizer.text("Yes ([[venom]])"));
		assertEquals("b", WikiSanitizer.text("[[a|b]]"));
	}

	@Test
	public void textDropsStripMarkerFromTextField()
	{
		// Stranger: the max-hit description carries a trailing <ref> strip-marker.
		assertEquals("115% of targeted player's max hit",
			WikiSanitizer.text("115% of targeted player's max hit" + marker("UNIQ--ref-0000001A-QINU")));
	}

	/** The rendered {@code {{sic}}} template, as Bucket stores it. */
	private static final String SIC = "<sup class=\"noprint\">&#91;<span class=\"fact-text\" title=\"The "
		+ "preceding quoted material has been reproduced verbatim from the quoted original and is not a "
		+ "transcription error.\">sic</span>&#93;</sup>";

	@Test
	public void textDropsSicNoteFromName()
	{
		// Zombies Champion: |name = Zombies{{sic}} Champion. The note goes, not just its tags.
		assertEquals("Zombies Champion", WikiSanitizer.text("Zombies" + SIC + " Champion"));
		assertEquals("Bloodthirst rockslug", WikiSanitizer.text("Bloodthirst rockslug" + SIC));
	}

	@Test
	public void textDropsSicNoteFromExamine()
	{
		// Crawling Hand: the wiki quotes the game's typo verbatim.
		assertEquals("I'm glad its just the hand I can see...",
			WikiSanitizer.text("I'm glad its" + SIC + " just the hand I can see..."));
	}

	@Test
	public void textStripsOtherTagsAndDecodesEntities()
	{
		assertEquals("a b", WikiSanitizer.text("<span class=\"x\">a</span> <i>b</i>"));
		assertEquals("[1] & <2>", WikiSanitizer.text("&#91;1&#x5D; &amp; &lt;2&gt;"));
		assertEquals("&unknown;", WikiSanitizer.text("&unknown;"));
	}

	@Test
	public void thinSpaceBeforeFootnoteDropped()
	{
		// TzKal-Zuk: a thin space separates the value from its <ref> footnote.
		List<String> raw = Collections.singletonList("148&thinsp;" + marker("'\"`UNIQ--ref-000000E6-QINU`\"'"));

		assertEquals(Collections.singletonList("148"), WikiSanitizer.lines(raw));
	}

	@Test
	public void multipleExaminesSplitOntoLines()
	{
		// Cyclops: several examines, each behind a bold bullet span, separated by <br/>.
		String bullet = "<span style=\"user-select:none;\">'''&bull;'''</span> ";
		assertEquals("A one-eyed man eater.\nA one-eyed woman eater.",
			WikiSanitizer.text(bullet + "A one-eyed man eater.<br/>" + bullet + "A one-eyed woman eater."));
	}

	@Test
	public void wikitextBoldLabelUnwrapped()
	{
		// Icefiend: a bold location label on the second examine.
		assertEquals("A small ice demon.\nIn the Chambers of Xeric: Servant of the Ice Demon.",
			WikiSanitizer.text("A small ice demon.<br>'''In the Chambers of Xeric:''' Servant of the Ice Demon."));
	}

	@Test
	public void attackStyleElementSplitOnBr()
	{
		// Vespula: two styles packed into one array element.
		assertEquals(Arrays.asList("Ranged", "Typeless"),
			WikiSanitizer.lines(Collections.singletonList("Ranged <br/> Typeless")));
	}

	@Test
	public void textHandlesNullAndPlainValues()
	{
		assertNull(WikiSanitizer.text(null));
		assertEquals("No", WikiSanitizer.text("No"));
	}

	@Test
	public void bucketGsonCleansEveryStringOnParse()
	{
		// One raw row carrying each shape, parsed the way the service parses the dataset.
		JsonObject row = new JsonObject();
		row.addProperty("name", "Zombies" + SIC + " Champion");
		row.addProperty("examine", "A small ice demon.<br>'''In the Chambers of Xeric:''' Servant.");
		row.addProperty("default_version", "");
		JsonArray styles = new JsonArray();
		styles.add("Ranged <br/> Typeless");
		row.add("attack_style", styles);
		JsonArray maxHit = new JsonArray();
		maxHit.add("<div class=\"plainlist \" >\n*31 (auto)\n*45 (special)\n</div>");
		row.add("max_hit", maxHit);

		MonsterData m = WikiSanitizer.bucketGson(new Gson()).fromJson(row, MonsterData.class);

		assertEquals("Zombies Champion", m.getName());
		assertEquals("A small ice demon.\nIn the Chambers of Xeric: Servant.", m.getExamine());
		assertEquals(Arrays.asList("Ranged", "Typeless"), m.getAttackStyles());
		assertEquals(Arrays.asList("31 (auto)", "45 (special)"), m.getMaxHitLines());
		// An empty string is Bucket's "true" for a flag field, so it must survive as non-null.
		assertTrue(m.isDefaultVersion());
		assertFalse(m.isMembersOnly());
	}

	@Test
	public void placeholdersParseAsAbsent()
	{
		// Kraken's weakness, an impling's style / max hit / poisonous: the wiki's "no value".
		MonsterData m = WikiSanitizer.bucketGson(new Gson()).fromJson("{\"elemental_weakness\":\"None\","
			+ "\"attack_style\":[\"None\"],\"max_hit\":[\"N/A\"],\"poisonous\":\"n/a\","
			+ "\"slayer_category\":[\"None\",\"Bosses\"]}", MonsterData.class);

		assertNull(m.getWeaknessElement());
		assertNull(m.getPoisonous());
		assertEquals(Collections.emptyList(), m.getAttackStyles());
		assertEquals(Collections.emptyList(), m.getMaxHitLines());
		assertEquals(Collections.singletonList("Bosses"), m.getSlayerCategory());
	}

	@Test
	public void noIsARealAnswerNotAPlaceholder()
	{
		MonsterData m = WikiSanitizer.bucketGson(new Gson()).fromJson("{\"poisonous\":\"No\"}", MonsterData.class);

		assertEquals("No", m.getPoisonous());
	}

	@Test
	public void enDashNormalisedForTheRuneScapeFont()
	{
		// Fever spider: the fonts carry no en dash glyph, but do carry the multiplication sign.
		assertEquals("1-12 (without Slayer gloves)", WikiSanitizer.text("1–12 (without Slayer gloves)"));
		assertEquals("12×2 (Ranged)", WikiSanitizer.text("12×2 (Ranged)"));
	}
}
