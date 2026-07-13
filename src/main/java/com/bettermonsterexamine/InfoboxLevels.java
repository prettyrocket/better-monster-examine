package com.bettermonsterexamine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Getter;

/**
 * Recovers the monster levels the Bucket API <b>cannot</b> carry, by parsing the wiki page's
 * {@code Infobox Monster} wikitext.
 *
 * <p>The wiki's {@code Module:Infobox Monster} writes each level to Bucket as
 * {@code tonumber_norefs(str)}, so a level that isn't a plain integer becomes {@code nil} and the
 * field is <b>omitted from the row entirely</b> — there is nothing in Bucket to widen or clean.
 * The only monster this currently hits is Vardorvis, whose Strength and Defence are HP-scaling
 * ranges ({@code |str1 = 270-<br />360}), which is why they rendered as a dash. Every other
 * Bucket-missing level really is blank on the wiki, and must keep rendering as a dash.
 *
 * <p>Pure and static, so it stays unit-testable without the network ({@link WikiSanitizer} does the
 * same for Bucket's TEXT fields). {@link MonsterDataService} fetches the handful of affected pages
 * in bulk and feeds their wikitext through here.
 */
final class InfoboxLevels
{
	/** Bucket field name -> the {@code Infobox Monster} wikitext parameter that feeds it. */
	private static final Map<String, String> PARAMS;

	static
	{
		Map<String, String> p = new LinkedHashMap<>();
		p.put("attack_level", "att");
		p.put("strength_level", "str");
		p.put("defence_level", "def");
		p.put("magic_level", "mage");
		p.put("ranged_level", "range");
		PARAMS = Collections.unmodifiableMap(p);
	}

	private static final Pattern INFOBOX = Pattern.compile("(?i)\\{\\{\\s*Infobox[ _]+Monster\\b");
	/** A version parameter, e.g. {@code version2}. */
	private static final Pattern VERSION_PARAM = Pattern.compile("(?i)^version(\\d*)$");
	/** A plain integer level — already in Bucket, so nothing was dropped. */
	private static final Pattern PLAIN_INT = Pattern.compile("-?\\d+");
	private static final Pattern BR = Pattern.compile("(?i)<br\\s*/?>");

	private InfoboxLevels()
	{
	}

	/** One level the wiki carries but Bucket dropped: the value as displayed, plus its footnote. */
	@Getter
	static final class LevelText
	{
		private final String value;
		/** The wiki's own {@code {{efn}}} footnote for this value (why it's a range), or null. */
		private final String note;

		LevelText(String value, String note)
		{
			this.value = value;
			this.note = note;
		}
	}

	/**
	 * Parse a monster page's wikitext into <em>version anchor</em> (lower-case; {@code ""} when the
	 * page has no versions) -> <em>Bucket field name</em> -> the dropped level. Levels that are
	 * plain integers are skipped: Bucket already carries those. A page with nothing dropped yields
	 * an empty map.
	 */
	static Map<String, Map<String, LevelText>> parse(String wikitext)
	{
		Map<String, Map<String, LevelText>> out = new LinkedHashMap<>();
		if (wikitext == null)
		{
			return out;
		}

		// Footnotes are defined once and referenced by name from anywhere on the page — Vardorvis
		// defines the "scales with HP" note on its max hit and re-references it from str/def.
		Map<String, String> footnotes = footnotes(wikitext);

		// A page can host more than one Infobox Monster; merge them all.
		Matcher start = INFOBOX.matcher(wikitext);
		int from = 0;
		while (start.find(from))
		{
			String block = template(wikitext, start.start());
			if (block == null)
			{
				from = start.end();
				continue;
			}
			readInfobox(block, footnotes, out);
			from = start.start() + block.length();
		}
		return out;
	}

	/** Pull one infobox's dropped levels into {@code out}, keyed by the version each belongs to. */
	private static void readInfobox(String block, Map<String, String> footnotes,
		Map<String, Map<String, LevelText>> out)
	{
		Map<String, String> params = params(block);

		// version1 = Post-quest, version2 = Awakened, … — the anchors Bucket keys its rows by. A
		// suffix-less "version" (or no version at all) means the page has a single, unnamed form.
		Map<String, String> anchors = new LinkedHashMap<>();
		for (Map.Entry<String, String> e : params.entrySet())
		{
			Matcher v = VERSION_PARAM.matcher(e.getKey());
			if (v.matches())
			{
				anchors.put(v.group(1), anchor(e.getValue()));
			}
		}
		if (anchors.isEmpty())
		{
			anchors.put("", "");
		}

		for (Map.Entry<String, String> field : PARAMS.entrySet())
		{
			for (Map.Entry<String, String> anchor : anchors.entrySet())
			{
				// "str2" belongs to version2; a suffix-less "str" applies to every version.
				String value = params.get(field.getValue() + anchor.getKey());
				if (value == null)
				{
					value = params.get(field.getValue());
				}
				LevelText level = value == null ? null : clean(value, footnotes);
				if (level == null)
				{
					continue;
				}
				out.computeIfAbsent(anchor.getValue(), k -> new LinkedHashMap<>())
					.put(field.getKey(), level);
			}
		}
	}

	/**
	 * Clean one raw level parameter to what the wiki displays, and resolve any footnote it
	 * references: {@code "270-<br />360{{efn|name=def}}"} -> value {@code "270-360"} + the "def"
	 * note. Null when the parameter is blank (genuinely unknown on the wiki — it must stay a dash)
	 * or a plain integer (Bucket already carries it, so nothing was dropped).
	 *
	 * <p>The {@code <br>} the wiki uses to wrap a range across a narrow infobox cell is dropped
	 * rather than kept as a line break, and en/em dashes become a plain hyphen — they look bad at
	 * panel size, the same reason {@code DropFormat} normalises them.
	 */
	private static LevelText clean(String raw, Map<String, String> footnotes)
	{
		String note = null;
		StringBuilder text = new StringBuilder();
		int i = 0;
		while (i < raw.length())
		{
			if (raw.startsWith("{{", i))
			{
				String tpl = template(raw, i);
				if (tpl != null)
				{
					List<String> parts = split(tpl.substring(2, tpl.length() - 2));
					if (!parts.isEmpty() && parts.get(0).trim().equalsIgnoreCase("efn"))
					{
						String resolved = footnote(parts, footnotes);
						if (resolved != null && note == null)
						{
							note = resolved;
						}
					}
					i += tpl.length();
					continue;
				}
			}
			text.append(raw.charAt(i++));
		}

		String value = plain(text.toString()).replaceAll("\\s+", "");
		// Test before dropping thousands commas: "1,000" is a value Bucket dropped (Lua's tonumber
		// rejects the comma), so it has to survive the plain-integer check that "280" is caught by.
		if (value.isEmpty() || PLAIN_INT.matcher(value).matches())
		{
			return null;
		}
		return new LevelText(value.replace(",", ""), note);
	}

	/** A version anchor as Bucket keys it, e.g. {@code "Post-quest"} -> {@code "post-quest"}. */
	private static String anchor(String raw)
	{
		return plain(raw).replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
	}

	/** Shared text cleanup: drop {@code <br>}s and wiki markup, and normalise en/em dashes. */
	private static String plain(String raw)
	{
		String out = BR.matcher(raw).replaceAll("");
		out = WikiSanitizer.text(out);
		return out.replace('–', '-').replace('—', '-');
	}

	/**
	 * The text an {@code {{efn}}} carries, or — when it's a bare {@code {{efn|name=def}}} reference —
	 * the text of the definition it points at. Null when it resolves to neither.
	 */
	private static String footnote(List<String> parts, Map<String, String> footnotes)
	{
		Efn efn = new Efn(parts);
		if (efn.text != null)
		{
			return efn.text;
		}
		return efn.name == null ? null : footnotes.get(efn.name);
	}

	/** Every named {@code {{efn|name=x|text}}} definition on the page, keyed by lower-case name. */
	private static Map<String, String> footnotes(String wikitext)
	{
		Map<String, String> out = new HashMap<>();
		for (int i = wikitext.indexOf("{{"); i >= 0; i = wikitext.indexOf("{{", i + 2))
		{
			String tpl = template(wikitext, i);
			if (tpl == null)
			{
				continue;
			}
			List<String> parts = split(tpl.substring(2, tpl.length() - 2));
			if (parts.isEmpty() || !parts.get(0).trim().equalsIgnoreCase("efn"))
			{
				continue;
			}
			Efn efn = new Efn(parts);
			if (efn.name != null && efn.text != null)
			{
				out.put(efn.name, efn.text);
			}
		}
		return out;
	}

	/** An {@code {{efn}}}'s two parts of interest: its {@code name=}, and its positional text. */
	private static final class Efn
	{
		private String name;
		private String text;

		Efn(List<String> parts)
		{
			for (String part : parts.subList(1, parts.size()))
			{
				String p = part.trim();
				if (p.toLowerCase(Locale.ROOT).startsWith("name="))
				{
					name = p.substring("name=".length()).trim().toLowerCase(Locale.ROOT);
				}
				else if (!p.isEmpty() && p.indexOf('=') < 0)
				{
					text = WikiSanitizer.text(p);
				}
			}
		}
	}

	/** A template's top-level {@code |name = value} parameters, keyed by lower-case name. */
	private static Map<String, String> params(String block)
	{
		Map<String, String> out = new LinkedHashMap<>();
		List<String> parts = split(block.substring(2, block.length() - 2));
		for (String part : parts.subList(Math.min(1, parts.size()), parts.size()))
		{
			int eq = part.indexOf('=');
			if (eq < 0)
			{
				continue;
			}
			out.put(part.substring(0, eq).trim().toLowerCase(Locale.ROOT), part.substring(eq + 1));
		}
		return out;
	}

	/**
	 * Split a template body on its <b>top-level</b> {@code |} separators — a {@code |} nested inside
	 * a wikilink or another template (the {@code {{efn|name=def}}} hanging off a level) belongs to
	 * that nested thing, not to the infobox.
	 */
	private static List<String> split(String body)
	{
		List<String> out = new ArrayList<>();
		int depth = 0;
		int from = 0;
		for (int i = 0; i < body.length(); i++)
		{
			if (body.startsWith("{{", i) || body.startsWith("[[", i))
			{
				depth++;
				i++;
			}
			else if (body.startsWith("}}", i) || body.startsWith("]]", i))
			{
				depth--;
				i++;
			}
			else if (depth == 0 && body.charAt(i) == '|')
			{
				out.add(body.substring(from, i));
				from = i + 1;
			}
		}
		out.add(body.substring(from));
		return out;
	}

	/**
	 * The whole {@code {{…}}} template starting at {@code from}, brace-matched so nested templates
	 * don't end it early. Null when it never closes.
	 */
	private static String template(String s, int from)
	{
		int depth = 0;
		for (int i = from; i < s.length() - 1; i++)
		{
			if (s.startsWith("{{", i))
			{
				depth++;
				i++;
			}
			else if (s.startsWith("}}", i))
			{
				if (--depth == 0)
				{
					return s.substring(from, i + 2);
				}
				i++;
			}
		}
		return null;
	}
}
