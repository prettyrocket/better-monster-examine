package com.bettermonsterexamine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cleans the few non-uniform shapes the OSRS Wiki Bucket API leaves in TEXT fields, so the rest
 * of the data layer sees plain strings. Bucket regularises the old wikitext-template garbage
 * (issue #24) into a few forms: MediaWiki strip-markers (bounded by the U+007F control char),
 * {@code <div class="plainlist">} bullet wrappers, {@code <br>} line breaks, and
 * {@code [[wikilinks]]}. Pure and static, so it stays trivially unit-testable without the network.
 *
 * <p>Bucket stores a field <i>after</i> its templates expand, so any template an editor drops into
 * an infobox arrives as rendered HTML — {@code {{sic}}} as a {@code <sup>} note, a thin space as
 * {@code &thinsp;}. Rather than chase each template, every tag is stripped and entities decoded;
 * {@code <br>} becomes a real line break, since a field such as examine can list several.
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
	/**
	 * Editorial notes the wiki keeps out of print, e.g. {@code {{sic}}}'s "[sic]". Dropped whole, not
	 * just untagged: "Zombies[sic] Champion" is still not the monster's name.
	 */
	private static final Pattern NOPRINT = Pattern.compile(
		"(?is)<sup[^>]*class=\"[^\"]*\\bnoprint\\b[^\"]*\"[^>]*>.*?</sup>");
	private static final Pattern TAG = Pattern.compile("</?[a-zA-Z][^>]*>");
	private static final Pattern ENTITY = Pattern.compile("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);");
	/** Unrendered wikitext bold/italic, e.g. {@code '''In the Chambers of Xeric:'''}. */
	private static final Pattern QUOTES = Pattern.compile("'{2,}");
	private static final Pattern SPACES = Pattern.compile("[ \\t]+");
	private static final Map<String, String> NAMED = new HashMap<>();

	static
	{
		NAMED.put("amp", "&");
		NAMED.put("lt", "<");
		NAMED.put("gt", ">");
		NAMED.put("quot", "\"");
		NAMED.put("apos", "'");
		NAMED.put("nbsp", " ");
		NAMED.put("thinsp", " ");
		NAMED.put("ensp", " ");
		NAMED.put("emsp", " ");
		NAMED.put("ndash", "-");
		NAMED.put("mdash", "-");
		// A list marker before each of several examines; they're split onto lines instead.
		NAMED.put("bull", "");
	}

	private WikiSanitizer()
	{
	}

	/**
	 * Clean a single TEXT value: drop strip-markers and editorial notes, unwrap wikilinks, turn
	 * {@code <br>} into {@code \n}, strip remaining tags and wikitext quotes, decode entities, and
	 * tidy each line's whitespace (dropping blank lines).
	 */
	static String text(String s)
	{
		if (s == null)
		{
			return null;
		}
		String out = STRIP_MARKER.matcher(s).replaceAll("");
		out = NOPRINT.matcher(out).replaceAll("");
		out = LINK.matcher(out).replaceAll("$1");
		out = out.replace("[[", "").replace("]]", "");
		out = BR.matcher(out).replaceAll("\n");
		out = TAG.matcher(out).replaceAll("");
		out = QUOTES.matcher(out).replaceAll("");
		// Decoded after the tag pass, so an escaped "&lt;" survives as text rather than read as a tag.
		out = decodeEntities(out);

		StringBuilder sb = new StringBuilder(out.length());
		for (String line : out.split("\n"))
		{
			String l = SPACES.matcher(line).replaceAll(" ").trim();
			if (!l.isEmpty())
			{
				if (sb.length() > 0)
				{
					sb.append('\n');
				}
				sb.append(l);
			}
		}
		return sb.toString();
	}

	/** Decode numeric and common named HTML entities; an unknown name is left as written. */
	private static String decodeEntities(String s)
	{
		if (s.indexOf('&') < 0)
		{
			return s;
		}
		Matcher m = ENTITY.matcher(s);
		StringBuffer sb = new StringBuffer();
		while (m.find())
		{
			String e = m.group(1);
			String rep;
			if (e.charAt(0) == '#')
			{
				boolean hex = e.length() > 1 && (e.charAt(1) == 'x' || e.charAt(1) == 'X');
				try
				{
					int cp = Integer.parseInt(e.substring(hex ? 2 : 1), hex ? 16 : 10);
					rep = cp == 0xa0 || cp == 0x2009 ? " " : new String(Character.toChars(cp));
				}
				catch (IllegalArgumentException ex)
				{
					rep = m.group();
				}
			}
			else
			{
				rep = NAMED.getOrDefault(e, m.group());
			}
			m.appendReplacement(sb, Matcher.quoteReplacement(rep));
		}
		m.appendTail(sb);
		return sb.toString();
	}

	/**
	 * Expand a Bucket list field into clean per-value lines: unwrap plainlist {@code <div>} +
	 * {@code *} bullet wrappers, split on {@code <br>}, clean each via {@link #text}, and discard
	 * empties. For {@code max_hit} each line is one value, e.g. {@code "45 (special)"}; an attack
	 * style element can likewise pack two ({@code "Ranged <br/> Typeless"}).
	 */
	static List<String> lines(List<String> raw)
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
