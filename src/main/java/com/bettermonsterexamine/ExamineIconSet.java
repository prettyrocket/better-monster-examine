package com.bettermonsterexamine;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The chat {@code <img=N>} indices for the attack styles and elements the Examine summary names,
 * resolved once at start-up by registering the bundled icons with RuneLite's chat icon manager.
 *
 * <p>The summary takes one of these instead of reading the manager itself so it stays a pure
 * formatter. A null set means registration didn't happen (icons missing, or too early), and the
 * summary falls back to spelling the styles out — an icon that never resolves would otherwise
 * leave the line unreadable.
 */
final class ExamineIconSet
{
	private final String[] melee;
	private final String[] ranged;
	private final Map<String, String> elements = new HashMap<>(4);

	ExamineIconSet(int stab, int slash, int crush, int standard, int heavy, int light,
		int air, int water, int earth, int fire)
	{
		melee = new String[]{img(stab), img(slash), img(crush)};
		ranged = new String[]{img(standard), img(heavy), img(light)};
		elements.put("air", img(air));
		elements.put("water", img(water));
		elements.put("earth", img(earth));
		elements.put("fire", img(fire));
	}

	/** Stab, slash, crush — same order as {@code ExamineSummary}'s melee bonuses. */
	String[] melee()
	{
		return melee.clone();
	}

	/** Standard, heavy, light — same order as the ranged bonuses. */
	String[] ranged()
	{
		return ranged.clone();
	}

	/** The icon for an elemental weakness, or null for one we don't bundle a rune for. */
	String element(String name)
	{
		return name == null ? null : elements.get(name.trim().toLowerCase(Locale.ROOT));
	}

	private static String img(int index)
	{
		return "<img=" + index + '>';
	}
}
