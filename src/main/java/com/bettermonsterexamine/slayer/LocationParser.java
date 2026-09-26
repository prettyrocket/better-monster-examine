package com.bettermonsterexamine.slayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the Locations table off a rendered monster page. Bucket's {@code locline} rows carry only map
 * coordinates — the {@code {{LocLine}}} template's location name isn't stored — so the page is the
 * only source of the names a player recognises. It's the same {@code action=parse} response the drops
 * come from, so this costs no extra fetch.
 */
public final class LocationParser
{
	private static final Pattern SECTION = Pattern.compile("<h2 id=\"Locations?\"");
	private static final Pattern TABLE = Pattern.compile("<table[^>]*>(.*?)</table>", Pattern.DOTALL);
	private static final Pattern ROW = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL);
	private static final Pattern CELL = Pattern.compile("<t([dh])[^>]*>(.*?)</t[dh]>", Pattern.DOTALL);
	// The floor template renders both conventions ("2nd floor[UK]3rd floor[US]"); keep the UK one,
	// matching the rest of the wiki's prose, and drop its [UK] marker.
	private static final Pattern FLOOR_HELP = Pattern.compile("<sup class=\"floornumber-help[^\"]*\">.*?</sup>", Pattern.DOTALL);
	private static final Pattern FLOOR_US = Pattern.compile("<span class=\"floornumber-us[^\"]*\">.*?</span>", Pattern.DOTALL);
	private static final Pattern BR = Pattern.compile("<br\\s*/?>");
	private static final Pattern TAG = Pattern.compile("<[^>]+>");
	private static final Pattern FOOTNOTE = Pattern.compile("\\[[^\\]]*\\]");
	private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(\\d+);");

	private LocationParser()
	{
	}

	/**
	 * The page's spawn locations in table order; empty when it has no Locations table, null when there's
	 * no HTML. Columns are found by header name, so a page that reorders or omits one still parses.
	 */
	public static List<SpawnLocation> parse(String html)
	{
		if (html == null)
		{
			return null;
		}
		List<SpawnLocation> out = new ArrayList<>();
		Matcher sm = SECTION.matcher(html);
		if (!sm.find())
		{
			return out;
		}
		int end = html.indexOf("<h2", sm.end());
		String region = html.substring(sm.end(), end < 0 ? html.length() : end);

		Matcher tm = TABLE.matcher(region);
		while (tm.find())
		{
			parseTable(tm.group(1), out);
		}
		return out;
	}

	private static void parseTable(String table, List<SpawnLocation> out)
	{
		int loc = -1;
		int levels = -1;
		int members = -1;
		int spawns = -1;
		Matcher rm = ROW.matcher(table);
		while (rm.find())
		{
			List<String> cells = new ArrayList<>();
			boolean header = false;
			Matcher cm = CELL.matcher(rm.group(1));
			while (cm.find())
			{
				header |= cm.group(1).equals("h");
				cells.add(cm.group(2));
			}
			if (header)
			{
				for (int i = 0; i < cells.size(); i++)
				{
					String h = text(cells.get(i)).toLowerCase(Locale.ROOT);
					if (h.startsWith("location"))
					{
						loc = i;
					}
					else if (h.startsWith("level"))
					{
						levels = i;
					}
					else if (h.startsWith("members"))
					{
						members = i;
					}
					else if (h.startsWith("spawns"))
					{
						spawns = i;
					}
				}
				continue;
			}
			if (loc < 0 || loc >= cells.size())
			{
				continue;
			}
			String name = text(cells.get(loc));
			if (name.isEmpty())
			{
				continue;
			}
			out.add(new SpawnLocation(name,
				cell(cells, levels),
				membersOf(cells, members),
				cell(cells, spawns)));
		}
	}

	private static String cell(List<String> cells, int i)
	{
		return i >= 0 && i < cells.size() ? text(cells.get(i)) : "";
	}

	/** The Members column is an icon; its alt text says which. */
	private static Boolean membersOf(List<String> cells, int i)
	{
		if (i < 0 || i >= cells.size())
		{
			return null;
		}
		String c = cells.get(i);
		if (c.contains("alt=\"Members\""))
		{
			return true;
		}
		if (c.contains("alt=\"Free-to-play\""))
		{
			return false;
		}
		return null;
	}

	/** A cell to plain text. Tags are dropped without a space so "(<abbr>ALR</abbr>)" stays "(ALR)". */
	static String text(String html)
	{
		String t = FLOOR_HELP.matcher(html).replaceAll("");
		t = FLOOR_US.matcher(t).replaceAll("");
		t = BR.matcher(t).replaceAll(" ");
		t = TAG.matcher(t).replaceAll("");
		t = t.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
			.replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'");
		Matcher nm = NUMERIC_ENTITY.matcher(t);
		StringBuilder sb = new StringBuilder();
		while (nm.find())
		{
			nm.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(Integer.parseInt(nm.group(1))))));
		}
		nm.appendTail(sb);
		t = FOOTNOTE.matcher(sb.toString()).replaceAll("");
		return t.replace(' ', ' ').replaceAll("\\s+", " ").trim();
	}
}
