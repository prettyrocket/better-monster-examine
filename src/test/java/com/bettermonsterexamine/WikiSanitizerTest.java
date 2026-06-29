package com.bettermonsterexamine;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

/**
 * Locks the four bounded shapes the Bucket API leaves in TEXT/{@code max_hit} fields, using the
 * real strings observed for the issue-#24 monsters (Tormented Demon, Vardorvis, Stranger) plus
 * clean cases (Vorkath, Blue Moon).
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
		// Tormented Demon (#24): one array element wraps a <div class="plainlist"> + * bullets.
		List<String> raw = Collections.singletonList(
			"<div class=\"plainlist \" >\n*31 (auto)\n*45 (special)\n</div>");

		assertEquals(Arrays.asList("31 (auto)", "45 (special)"), WikiSanitizer.maxHitLines(raw));
	}

	@Test
	public void stripMarkersDroppedKeepingTheValue()
	{
		// Vardorvis (#24): a <ref> footnote strip-marker trails the first value; second is clean.
		List<String> raw = Arrays.asList(
			"30-37 (Melee)" + marker("UNIQ--ref-00000049-QINU"),
			"?? (axes)");

		assertEquals(Arrays.asList("30-37 (Melee)", "?? (axes)"), WikiSanitizer.maxHitLines(raw));
	}

	@Test
	public void brSplitsASingleElementIntoLines()
	{
		List<String> raw = Collections.singletonList("16 (Stab)<br/>50 (Dragonfire)");

		assertEquals(Arrays.asList("16 (Stab)", "50 (Dragonfire)"), WikiSanitizer.maxHitLines(raw));
	}

	@Test
	public void cleanMultiValueArrayPassesThrough()
	{
		// Vorkath: already-clean per-style array — unchanged.
		List<String> raw = Arrays.asList("30 (Magic)", "28 (Ranged)", "121 (Dragonfire Bomb/Special)");

		assertEquals(raw, WikiSanitizer.maxHitLines(raw));
	}

	@Test
	public void nullAndEmptyElementsAreSkipped()
	{
		List<String> raw = Arrays.asList("32", null, "", "  ");

		assertEquals(Collections.singletonList("32"), WikiSanitizer.maxHitLines(raw));
		assertEquals(Collections.emptyList(), WikiSanitizer.maxHitLines(null));
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
		// Stranger (#24): the max-hit description carries a trailing <ref> strip-marker.
		assertEquals("115% of targeted player's max hit",
			WikiSanitizer.text("115% of targeted player's max hit" + marker("UNIQ--ref-0000001A-QINU")));
	}

	@Test
	public void textHandlesNullAndPlainValues()
	{
		assertNull(WikiSanitizer.text(null));
		assertEquals("No", WikiSanitizer.text("No"));
	}
}
