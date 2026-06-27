package com.bettermonsterexamine;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure formatting and parsing helpers for monster stats — the string/number shaping shared by
 * the side panel ({@link MonsterCard}) and the in-game {@link MonsterCardOverlay}. No Swing or
 * drawing dependencies, so it stays trivially unit-testable.
 */
final class StatFormat
{
	private static final Pattern DIGITS = Pattern.compile("\\d+");

	private StatFormat()
	{
	}

	/** Signed bonus, e.g. {@code +5} / {@code -3}. */
	static String bonus(int v)
	{
		return (v >= 0 ? "+" : "") + v;
	}

	/** A skill level, or an em dash when absent/zero. */
	static String num(int v)
	{
		return v <= 0 ? "—" : String.valueOf(v);
	}

	static String cap(String s)
	{
		return s == null || s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
	}

	/** "5 ticks (3.0 seconds)". */
	static String attackSpeed(MonsterData m)
	{
		int t = m.getSpeed();
		return t + (t == 1 ? " tick" : " ticks") + " (" + String.format("%.1f", t * 0.6) + " seconds)";
	}

	static String burnImmunity(MonsterData m)
	{
		return m.getImmunities() != null ? m.getImmunities().getBurn() : null;
	}

	/** Wiki poison/venom resistance: 100 → "Immune", &gt;0 → "N% resistance", else null. */
	static String resistanceLabel(String resistance)
	{
		if (resistance == null)
		{
			return null;
		}
		try
		{
			int r = Integer.parseInt(resistance.trim().replace("%", ""));
			if (r >= 100)
			{
				return "Immune";
			}
			return r > 0 ? r + "% resistance" : null;
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	static boolean yes(String v)
	{
		return v != null && v.trim().equalsIgnoreCase("yes");
	}

	/** Map dataset attribute keys to their wiki display names (e.g. dragon → Draconic). */
	static String attributeNames(List<String> attrs)
	{
		StringBuilder sb = new StringBuilder();
		for (String a : attrs)
		{
			if (sb.length() > 0)
			{
				sb.append(", ");
			}
			sb.append(attributeName(a));
		}
		return sb.toString();
	}

	private static String attributeName(String a)
	{
		switch (a)
		{
			case "dragon":
				return "Draconic";
			case "vampyre1":
				return "Vampyre (tier 1)";
			case "vampyre2":
				return "Vampyre (tier 2)";
			case "vampyre3":
				return "Vampyre (tier 3)";
			default:
				return cap(a);
		}
	}

	/** Em dash for a null/empty string, else the string itself. */
	static String nz(String s)
	{
		return s == null || s.isEmpty() ? "—" : s;
	}

	/** Join the non-null, non-empty parts with {@code sep}; null when nothing remains. */
	static String join(String sep, String... parts)
	{
		StringBuilder sb = new StringBuilder();
		for (String p : parts)
		{
			if (p == null || p.isEmpty())
			{
				continue;
			}
			if (sb.length() > 0)
			{
				sb.append(sep);
			}
			sb.append(p);
		}
		return sb.length() == 0 ? null : sb.toString();
	}

	/** HTML-escape for the panel's wrapping labels. */
	static String esc(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	/** The largest number in a max-hit line's value (the part before any "(label)"), or -1. */
	static int maxValue(String line)
	{
		int paren = line.indexOf('(');
		String value = paren >= 0 ? line.substring(0, paren) : line;
		Matcher m = DIGITS.matcher(value);
		int max = -1;
		while (m.find())
		{
			max = Math.max(max, Integer.parseInt(m.group()));
		}
		return max;
	}

	/** True when a numeric wiki value (e.g. XP bonus) is zero, so it can be omitted. */
	static boolean isZero(String v)
	{
		try
		{
			return Double.parseDouble(v.trim()) == 0;
		}
		catch (NumberFormatException e)
		{
			return false;
		}
	}
}
