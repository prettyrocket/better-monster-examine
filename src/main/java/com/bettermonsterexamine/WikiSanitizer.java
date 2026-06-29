package com.bettermonsterexamine;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Cleans the few non-uniform shapes the OSRS Wiki Bucket API leaves in TEXT fields, so the rest
 * of the data layer sees plain strings. Bucket regularises the old wikitext-template garbage
 * (issue #24) into four bounded forms: MediaWiki strip-markers (bounded by the U+007F control
 * char), {@code <div class="plainlist">} bullet wrappers, {@code <br>} line breaks, and
 * {@code [[wikilinks]]}. Pure and static, so it stays trivially unit-testable without the network.
 */
final class WikiSanitizer
{
	/** The U+007F control char that delimits a MediaWiki strip-marker on both ends. */
	private static final String DEL = String.valueOf((char) 0x7f);
	/** MediaWiki strip-marker (e.g. a {@code <ref>} footnote), e.g. {@code UNIQ--ref-…-QINU}. */
	private static final Pattern STRIP_MARKER = Pattern.compile(DEL + "[^" + DEL + "]*" + DEL);
	/** {@code [[Magic]]} -> Magic, {@code [[a|b]]} -> b. */
	private static final Pattern LINK = Pattern.compile("\\[\\[(?:[^\\]|]*\\|)?([^\\]]*)\\]\\]");
	private static final Pattern BR = Pattern.compile("(?i)<br\\s*/?>");
	private static final Pattern DIV = Pattern.compile("(?i)</?div[^>]*>");

	private WikiSanitizer()
	{
	}

	/** Clean a single TEXT value: drop strip-markers, unwrap wikilinks, collapse outer whitespace. */
	static String text(String s)
	{
		if (s == null)
		{
			return null;
		}
		String out = STRIP_MARKER.matcher(s).replaceAll("");
		out = LINK.matcher(out).replaceAll("$1");
		return out.replace("[[", "").replace("]]", "").trim();
	}

	/**
	 * Expand a Bucket {@code max_hit} array into clean per-value lines: unwrap plainlist
	 * {@code <div>} + {@code *} bullet wrappers, split on {@code <br>}, drop strip-markers and
	 * wikilinks, and discard empties. Each remaining line is one max-hit value, e.g.
	 * {@code "45 (special)"}.
	 */
	static List<String> maxHitLines(List<String> raw)
	{
		List<String> out = new ArrayList<>();
		if (raw == null)
		{
			return out;
		}
		for (String element : raw)
		{
			if (element == null)
			{
				continue;
			}
			String expanded = DIV.matcher(element).replaceAll("\n");
			expanded = BR.matcher(expanded).replaceAll("\n");
			for (String part : expanded.split("\n"))
			{
				String line = part.trim();
				if (line.startsWith("*"))
				{
					line = line.substring(1).trim();
				}
				line = text(line);
				if (!line.isEmpty())
				{
					out.add(line);
				}
			}
		}
		return out;
	}
}
