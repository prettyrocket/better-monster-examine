package com.bettermonsterexamine;

import com.google.gson.Gson;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the markup the OSRS Wiki Bucket API leaves in its strings into plain text, so the rest of
 * the data layer never sees any. Bucket stores a field <i>after</i> its templates expand, so any
 * template an editor drops into an infobox arrives as rendered HTML — {@code {{sic}}} as a
 * {@code <sup>} note, a thin space as {@code &thinsp;}, a footnote as a MediaWiki strip-marker.
 * Rather than chase each template, the rules below are generic: every tag goes, entities decode,
 * and line breaks survive as {@code \n}, since a field can list several values.
 *
 * <p>{@link #bucketGson} applies this to every string of every Bucket row as it is parsed, so
 * nothing downstream has to remember to.
 */
final class WikiSanitizer
{
	private static final String DEL = String.valueOf((char) 0x7f);
	/** Applied in order: each regex to its replacement. */
	private static final Map<Pattern, String> RULES = new LinkedHashMap<>();
	private static final Pattern ENTITY = Pattern.compile("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);");
	private static final Pattern SPACES = Pattern.compile("[ \\t]+");
	/**
	 * How the wiki writes "no value" in any field (an attack style or weakness of {@code None}, a max
	 * hit of {@code N/A}). Read as absent, so each renderer shows its own blank rather than the word.
	 * Not {@code "No"}: that is a real answer ("Poisonous: No").
	 */
	private static final Pattern PLACEHOLDER = Pattern.compile("(?i)n/a|none");
	private static final Map<String, String> NAMED = new HashMap<>();
	private static final Type STRING_LIST = new TypeToken<List<String>>()
	{
	}.getType();

	static
	{
		// MediaWiki strip-marker (a <ref> footnote), bounded by U+007F on both ends.
		rule(DEL + "[^" + DEL + "]*" + DEL, "");
		// Editorial notes kept out of print, e.g. {{sic}}'s "[sic]". Dropped whole, not just
		// untagged: "Zombies[sic] Champion" is still not the monster's name.
		rule("(?is)<sup[^>]*\\bnoprint\\b.*?</sup>", "");
		// [[Magic]] -> Magic, [[a|b]] -> b; then any unbalanced brackets.
		rule("\\[\\[(?:[^\\]|]*\\|)?([^\\]]*)\\]\\]", "$1");
		rule("\\[\\[|\\]\\]", "");
		// <br> and a plainlist's <div> wrapper separate values.
		rule("(?i)<br\\s*/?>|</?div[^>]*>", "\n");
		rule("</?[a-zA-Z][^>]*>", "");
		// Unrendered wikitext bold/italic ('''In the Chambers of Xeric:''') and list bullets.
		rule("'{2,}|(?m)^[ \\t]*\\*+", "");
		// The RuneScape fonts have no en dash glyph (Fever spider's "1–12").
		rule("–", "-");

		NAMED.put("amp", "&");
		NAMED.put("lt", "<");
		NAMED.put("gt", ">");
		NAMED.put("quot", "\"");
		NAMED.put("nbsp", " ");
		NAMED.put("thinsp", " ");
		// A list marker before each of several examines; the line breaks already separate them.
		NAMED.put("bull", "");
	}

	private WikiSanitizer()
	{
	}

	private static void rule(String regex, String replacement)
	{
		RULES.put(Pattern.compile(regex), replacement);
	}

	/**
	 * A copy of {@code base} that cleans every string as a Bucket row is parsed: a scalar through
	 * {@link #text}, a list through {@link #lines} (so one element packing several values splits),
	 * and a placeholder value dropped as absent.
	 */
	static Gson bucketGson(Gson base)
	{
		return base.newBuilder()
			.registerTypeAdapter(String.class, (JsonDeserializer<String>) (json, t, c) ->
			{
				String s = text(json.getAsString());
				return PLACEHOLDER.matcher(s).matches() ? null : s;
			})
			.registerTypeAdapter(STRING_LIST, (JsonDeserializer<List<String>>) (json, t, c) ->
			{
				List<String> raw = new ArrayList<>();
				for (JsonElement e : json.isJsonArray() ? json.getAsJsonArray() : singleton(json))
				{
					if (!e.isJsonNull())
					{
						raw.add(e.getAsString());
					}
				}
				List<String> out = lines(raw);
				out.removeIf(s -> PLACEHOLDER.matcher(s).matches());
				return out;
			})
			.create();
	}

	private static List<JsonElement> singleton(JsonElement e)
	{
		List<JsonElement> l = new ArrayList<>();
		l.add(e);
		return l;
	}

	/** Clean one value to plain text; several values stay on separate lines, blank lines dropped. */
	static String text(String s)
	{
		if (s == null)
		{
			return null;
		}
		String out = s;
		for (Map.Entry<Pattern, String> r : RULES.entrySet())
		{
			out = r.getKey().matcher(out).replaceAll(r.getValue());
		}
		// Decoded after the tag pass, so an escaped "&lt;" survives as text rather than read as a tag.
		out = decodeEntities(out);

		StringBuilder sb = new StringBuilder(out.length());
		for (String line : out.split("\n"))
		{
			String l = SPACES.matcher(line).replaceAll(" ").trim();
			if (!l.isEmpty())
			{
				sb.append(sb.length() > 0 ? "\n" : "").append(l);
			}
		}
		return sb.toString();
	}

	/** Clean a list, one value per line: {@code max_hit}'s plainlist, Vespula's two-style element. */
	static List<String> lines(List<String> raw)
	{
		List<String> out = new ArrayList<>();
		for (String element : raw)
		{
			String clean = text(element);
			if (clean != null && !clean.isEmpty())
			{
				for (String line : clean.split("\n"))
				{
					out.add(line);
				}
			}
		}
		return out;
	}

	/** Decode numeric and common named HTML entities; anything else is left as written. */
	private static String decodeEntities(String s)
	{
		Matcher m = ENTITY.matcher(s);
		StringBuffer sb = new StringBuffer();
		while (m.find())
		{
			String e = m.group(1);
			String rep = NAMED.getOrDefault(e, m.group());
			if (e.charAt(0) == '#')
			{
				boolean hex = e.length() > 1 && (e.charAt(1) == 'x' || e.charAt(1) == 'X');
				try
				{
					int cp = Integer.parseInt(e.substring(hex ? 2 : 1), hex ? 16 : 10);
					rep = Character.isSpaceChar(cp) ? " " : new String(Character.toChars(cp));
				}
				catch (IllegalArgumentException ex)
				{
					// Out of range: leave it as written.
				}
			}
			m.appendReplacement(sb, Matcher.quoteReplacement(rep));
		}
		m.appendTail(sb);
		return sb.toString();
	}
}
