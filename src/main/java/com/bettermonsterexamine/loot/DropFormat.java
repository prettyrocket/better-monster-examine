package com.bettermonsterexamine.loot;

import java.util.Locale;

/**
 * Pure display helpers for a {@link DropRow} — the string shaping the drops panel needs, with no
 * Swing dependency so it stays trivially unit-testable (mirrors {@code StatFormat} for stats).
 * Rarity shows as the wiki's compound value, quantity as its display range, and coin values compact.
 */
final class DropFormat
{
	private DropFormat()
	{
	}

	/** Rarity as the wiki shows it: {@code "Always"}, or the raw fraction; an em dash when unknown. */
	static String rarity(DropRow row)
	{
		if (row.isAlways())
		{
			return "Always";
		}
		String r = row.getRarity();
		return r == null || r.trim().isEmpty() ? "—" : r.trim();
	}

	/**
	 * Quantity as the wiki shows it: the row's display string ({@code "2"}, {@code "2–3"},
	 * {@code "60 (noted)"}) when present, otherwise derived from the low/high bounds. Empty when the
	 * row carries neither — the caller drops the "×N" suffix rather than showing a bare "0".
	 */
	static String quantity(DropRow row)
	{
		String display = row.getQuantityDisplay();
		if (display != null && !display.trim().isEmpty())
		{
			return display.trim();
		}
		int lo = row.getQuantityLow();
		int hi = row.getQuantityHigh();
		if (lo <= 0 && hi <= 0)
		{
			return "";
		}
		return lo == hi ? String.valueOf(lo) : lo + "–" + hi;
	}

	/** Compact coin value: grouped under a million, else one-decimal {@code M}/{@code B}; blank for ≤0. */
	static String price(int n)
	{
		if (n <= 0)
		{
			return "";
		}
		if (n < 1_000_000)
		{
			return String.format(Locale.US, "%,d", n);
		}
		if (n < 1_000_000_000)
		{
			return trimDecimal(n / 1_000_000.0) + "M";
		}
		return trimDecimal(n / 1_000_000_000.0) + "B";
	}

	/**
	 * The GE / High-Alch caption line under an item, showing only the parts the client priced
	 * ({@code "GE 3.9M · Alch 450K"}), or empty when the item is both untradeable and non-alchable.
	 */
	static String priceLine(int ge, int alch)
	{
		StringBuilder sb = new StringBuilder();
		if (ge > 0)
		{
			sb.append("GE ").append(price(ge));
		}
		if (alch > 0)
		{
			if (sb.length() > 0)
			{
				sb.append(" · ");
			}
			sb.append("Alch ").append(price(alch));
		}
		return sb.toString();
	}

	private static String trimDecimal(double v)
	{
		String s = String.format(Locale.US, "%.1f", v);
		return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
	}
}
