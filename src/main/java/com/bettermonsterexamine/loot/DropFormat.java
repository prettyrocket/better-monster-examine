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

	/**
	 * The rarity tier for colour-coding, from the drop probability. {@code Always} (100%) and any
	 * unknown / unparseable rarity are {@code COMMON}; thresholds: uncommon past 1/50, rare past
	 * 1/500, ultra-rare past 1/5000.
	 */
	static RarityTier tierOf(String rarity)
	{
		double p = probability(rarity);
		if (p <= 0 || p >= 1.0 / 50)
		{
			return RarityTier.COMMON;
		}
		if (p >= 1.0 / 500)
		{
			return RarityTier.UNCOMMON;
		}
		if (p >= 1.0 / 5000)
		{
			return RarityTier.RARE;
		}
		return RarityTier.ULTRA_RARE;
	}

	/**
	 * Rarity as a probability in {@code (0, 1]}: {@code "Always"} → 1, an {@code "a/b"} fraction → a/b,
	 * a multi-roll {@code "N × 1/M"} → N/M, or {@code -1} when unparseable. The wiki's compound cells
	 * ({@code "2 × 1/24,576 ; 1/12,480 [d 2]"}) reduce to their combined per-kill rate first.
	 */
	static double probability(String rarity)
	{
		if (rarity == null)
		{
			return -1;
		}
		String r = effective(rarity);
		if (r.equalsIgnoreCase("Always"))
		{
			return 1.0;
		}
		if (r.startsWith("~"))
		{
			r = r.substring(1).trim();
		}
		// "N × 1/M" (N rolls) — approximate the per-kill rate as N × the per-roll fraction.
		int mult = r.indexOf('×');
		if (mult >= 0)
		{
			try
			{
				double rolls = Double.parseDouble(r.substring(0, mult).replace(",", "").trim());
				return rolls * fraction(r.substring(mult + 1));
			}
			catch (NumberFormatException e)
			{
				return -1;
			}
		}
		return fraction(r);
	}

	/** Parse a plain {@code "a/b"} fraction (or a bare number), tolerating commas; {@code -1} on failure. */
	private static double fraction(String s)
	{
		String r = s.replace(",", "").trim();
		int slash = r.indexOf('/');
		try
		{
			if (slash < 0)
			{
				return Double.parseDouble(r);
			}
			double num = Double.parseDouble(r.substring(0, slash).trim());
			double den = Double.parseDouble(r.substring(slash + 1).trim());
			return den == 0 ? -1 : num / den;
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
	}

	/**
	 * Reduce a wiki rarity cell to its effective per-kill value: strip any footnote ({@code "[d 2]"})
	 * and, when the cell states a combined rate after a {@code ';'} ({@code "2 × 1/24,576 ; 1/12,480"}),
	 * keep that combined part. Otherwise the cell is returned trimmed.
	 */
	static String effective(String rarity)
	{
		String r = rarity.replaceAll("\\[[^\\]]*\\]", "").trim();
		int semi = r.lastIndexOf(';');
		return semi >= 0 ? r.substring(semi + 1).trim() : r;
	}

	/**
	 * Rarity as the wiki shows it, reduced to its effective per-kill value: {@code "Always"}, a
	 * fraction, or a plain dash when unknown.
	 */
	static String rarity(DropRow row)
	{
		if (row.isAlways())
		{
			return "Always";
		}
		String r = row.getRarity();
		return r == null || r.trim().isEmpty() ? "-" : display(effective(r));
	}

	/**
	 * Quantity as the wiki rendered it ({@code "1"}, {@code "35-55"}, {@code "60 (noted)"}), trimmed.
	 * Empty when the row carries none — the caller drops the "×N" suffix rather than showing a bare "0".
	 */
	static String quantity(DropRow row)
	{
		String q = row.getQuantity();
		return q == null ? "" : display(q.trim());
	}

	/** Compact coin value: plain digits under a million, else one-decimal {@code M}/{@code B}; blank for ≤0. */
	static String price(int n)
	{
		if (n <= 0)
		{
			return "";
		}
		if (n < 1_000_000)
		{
			return Integer.toString(n);
		}
		if (n < 1_000_000_000)
		{
			return trimDecimal(n / 1_000_000.0) + "M";
		}
		return trimDecimal(n / 1_000_000_000.0) + "B";
	}

	/**
	 * Display cleanup for the numbers the wiki hands us: drop thousands commas ({@code "1,000"} →
	 * {@code "1000"}) and turn en/em dashes into a plain hyphen (the RuneScape font renders "-", not
	 * "–"/"—").
	 */
	private static String display(String s)
	{
		return s.replace(",", "").replace('–', '-').replace('—', '-');
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
