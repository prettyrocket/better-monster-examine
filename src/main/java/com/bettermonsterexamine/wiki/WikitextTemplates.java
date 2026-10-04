package com.bettermonsterexamine.wiki;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reading templates out of raw wikitext, by matching brackets rather than with one regex over the
 * page: templates nest ({@code {{efn}}} inside a parameter inside an infobox), and a {@code |} inside
 * a {@code [[link|label]]} or a nested template belongs to that, not to the template around it.
 *
 * <p>Values come back raw, markup and all; cleaning them is the caller's business. Pure and static.
 */
public final class WikitextTemplates
{
	private WikitextTemplates()
	{
	}

	/**
	 * The whole {@code {{…}}} template starting at {@code from}, brace-matched so nested templates
	 * don't end it early. Null when it never closes.
	 */
	public static String template(String s, int from)
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

	/** A template's parts: its name first, then each top-level parameter, raw. */
	public static List<String> parts(String template)
	{
		return split(template.substring(2, template.length() - 2));
	}

	/** Split on the <b>top-level</b> {@code |} separators, leaving those inside links and templates alone. */
	public static List<String> split(String body)
	{
		List<String> out = new ArrayList<>();
		int from = 0;
		for (int bar = indexOfBar(body, 0); bar >= 0; bar = indexOfBar(body, from))
		{
			out.add(body.substring(from, bar));
			from = bar + 1;
		}
		out.add(body.substring(from));
		return out;
	}

	/** The first top-level {@code |} at or after {@code from}, or -1. */
	public static int indexOfBar(String s, int from)
	{
		int depth = 0;
		for (int i = from; i < s.length(); i++)
		{
			if (s.startsWith("{{", i) || s.startsWith("[[", i))
			{
				depth++;
				i++;
			}
			else if (s.startsWith("}}", i) || s.startsWith("]]", i))
			{
				depth--;
				i++;
			}
			else if (depth == 0 && s.charAt(i) == '|')
			{
				return i;
			}
		}
		return -1;
	}

	/** A template's named {@code |name = value} parameters, keyed by lower-case name, values raw. */
	public static Map<String, String> params(String template)
	{
		Map<String, String> out = new LinkedHashMap<>();
		List<String> parts = parts(template);
		for (String part : parts.subList(Math.min(1, parts.size()), parts.size()))
		{
			int eq = part.indexOf('=');
			if (eq >= 0)
			{
				out.put(part.substring(0, eq).trim().toLowerCase(Locale.ROOT), part.substring(eq + 1));
			}
		}
		return out;
	}

	/** True when {@code parts} (from {@link #parts}) belong to a template called {@code name}, ignoring case. */
	public static boolean isNamed(List<String> parts, String name)
	{
		return !parts.isEmpty() && parts.get(0).trim().equalsIgnoreCase(name);
	}

	/**
	 * An {@code {{efn}}} footnote: either a definition ({@code {{efn|name=def|Scales with HP.}}}) or a
	 * bare reference back to one ({@code {{efn|name=def}}}). The wiki defines a note once and refers
	 * to it from anywhere on the page, so resolving a reference needs {@link #footnotes} of the page.
	 */
	public static final class Efn
	{
		/** Lower-case {@code name=}, or null. */
		public final String name;
		/** The note text, raw, or null for a bare reference. */
		public final String text;

		private Efn(String name, String text)
		{
			this.name = name;
			this.text = text;
		}

		/** The footnote these {@link #parts} describe, or null when they aren't an {@code {{efn}}}. */
		public static Efn of(List<String> parts)
		{
			if (!isNamed(parts, "efn"))
			{
				return null;
			}
			String name = null;
			String text = null;
			for (String part : parts.subList(1, parts.size()))
			{
				String p = part.trim();
				if (p.toLowerCase(Locale.ROOT).startsWith("name="))
				{
					name = p.substring("name=".length()).trim().toLowerCase(Locale.ROOT);
				}
				else if (!p.isEmpty() && p.indexOf('=') < 0)
				{
					text = p;
				}
			}
			return new Efn(name, text);
		}

		/** This note's text, or the text of the definition it refers to. Null when it resolves to neither. */
		public String resolve(Map<String, String> footnotes)
		{
			if (text != null)
			{
				return text;
			}
			return name == null ? null : footnotes.get(name);
		}
	}

	/** Every named {@code {{efn|name=x|text}}} definition on the page, raw text keyed by lower-case name. */
	public static Map<String, String> footnotes(String wikitext)
	{
		if (wikitext == null)
		{
			return Collections.emptyMap();
		}
		Map<String, String> out = new HashMap<>();
		for (int i = wikitext.indexOf("{{"); i >= 0; i = wikitext.indexOf("{{", i + 2))
		{
			String tpl = template(wikitext, i);
			Efn efn = tpl == null ? null : Efn.of(parts(tpl));
			if (efn != null && efn.name != null && efn.text != null)
			{
				out.put(efn.name, efn.text);
			}
		}
		return out;
	}
}
