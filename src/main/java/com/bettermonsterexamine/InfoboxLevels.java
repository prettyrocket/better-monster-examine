package com.bettermonsterexamine;

import com.bettermonsterexamine.wiki.WikitextTemplates;
import java.util.Collections;
import java.util.LinkedHashMap;
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
 * <p>Attack speed has the same hole with a different symptom: Bucket stores a non-numeric speed
 * as {@code 0} rather than omitting it, so Basilisk Knight's {@code Varies} rendered as "0 ticks".
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
		p.put("attack_speed", "attack speed");
		PARAMS = Collections.unmodifiableMap(p);
	}

	private static final Pattern INFOBOX = Pattern.compile("(?i)\\{\\{\\s*Infobox[ _]+Monster\\b");
	/** A version parameter, e.g. {@code version2}. */
	private static final Pattern VERSION_PARAM = Pattern.compile("(?i)^version(\\d*)$");
	/** A plain integer level — already in Bucket, so nothing was dropped. */
	private static final Pattern PLAIN_INT = Pattern.compile("-?\\d+");
	/** The wiki's "no value" ({@code N/A} on an impling's attack speed): a dash, not a word. */
	private static final Pattern PLACEHOLDER = Pattern.compile("(?i)n/a|none|no");
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
		Map<String, String> footnotes = WikitextTemplates.footnotes(wikitext);

		// A page can host more than one Infobox Monster; merge them all.
		Matcher start = INFOBOX.matcher(wikitext);
		int from = 0;
		while (start.find(from))
		{
			String block = WikitextTemplates.template(wikitext, start.start());
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
		Map<String, String> params = WikitextTemplates.params(block);

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
				String tpl = WikitextTemplates.template(raw, i);
				if (tpl != null)
				{
					WikitextTemplates.Efn efn = WikitextTemplates.Efn.of(WikitextTemplates.parts(tpl));
					String resolved = efn == null ? null : efn.resolve(footnotes);
					if (resolved != null && note == null)
					{
						note = WikiSanitizer.text(resolved);
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
		if (value.isEmpty() || PLAIN_INT.matcher(value).matches() || PLACEHOLDER.matcher(value).matches())
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

	/** Shared text cleanup: drop {@code <br>}s and wiki markup (which covers en dashes), and em dashes. */
	private static String plain(String raw)
	{
		String out = BR.matcher(raw).replaceAll("");
		return WikiSanitizer.text(out).replace('—', '-');
	}
}
