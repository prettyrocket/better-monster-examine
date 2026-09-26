package com.bettermonsterexamine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.Getter;

/**
 * Ranks a monster's attack styles by how hard it is to <em>hit</em>, using the OSRS defence roll
 * {@code (level + 9) x (style defence bonus + 64)} — lower is easier.
 *
 * <p>Melee and ranged both roll off the Defence level, so the {@code (level + 9)} term cancels and
 * ranking those six by raw defence bonus alone gives exactly the same order as the roll. Magic is
 * the exception: it rolls off the monster's <b>Magic</b> level. That is why reading the bonuses
 * alone gets the answer wrong on roughly one monster in seven — a Fire giant's {@code +50} magic
 * bonus is the highest defence bonus it has, yet magic is 6x easier to land than anything else
 * because its Magic level is 1.
 *
 * <p>Most of the bestiary is easiest to hit with magic, and magic costs runes, so the result also
 * names the easiest free style whenever magic wins alone.
 *
 * <p>Pure and unit-tested; the renderers shape the {@link Result} into text.
 */
final class DefenceRolls
{
	/** Wiki pages whose magic defence rolls off Defence level rather than Magic level. */
	private static final Set<String> MAGIC_OFF_DEFENCE = Set.of(
		"verzik vitur", "ice demon", "fragment of seren", "baboon brawler", "rabbit (prifddinas)");

	/**
	 * Styles within this factor of the easiest roll count as tied with it. The ratio decides the
	 * answer but is never shown — a bare "1.9x" needs explaining that a chat line can't give.
	 */
	private static final double TIE = 1.25d;

	enum Family
	{
		MELEE, RANGED, MAGIC
	}

	enum Style
	{
		STAB("Stab", Family.MELEE),
		SLASH("Slash", Family.MELEE),
		CRUSH("Crush", Family.MELEE),
		STANDARD("Standard", Family.RANGED),
		HEAVY("Heavy", Family.RANGED),
		LIGHT("Light", Family.RANGED),
		MAGIC("Magic", Family.MAGIC);

		@Getter
		private final String label;
		@Getter
		private final Family family;

		Style(String label, Family family)
		{
			this.label = label;
			this.family = family;
		}
	}

	/**
	 * What the ranking amounts to. {@link #NO_DATA} means the wiki carries no defensive numbers at
	 * all — distinct from every bonus genuinely being zero, which is {@link #CANNOT_MISS}.
	 */
	enum Band
	{
		NO_DATA, CANNOT_MISS, EVEN, RANKED
	}

	@Getter
	static final class Result
	{
		private final Band band;
		/** The easiest styles to land, ties included. Empty unless {@link Band#RANKED}. */
		private final List<Style> weakest;
		/**
		 * The easiest styles that cost no runes, when magic alone is easiest and one free style
		 * stands out from the rest. Empty otherwise — including when every free style is even.
		 */
		private final List<Style> freeWeakest;
		/** Capitalised elemental weakness (a damage multiplier, not accuracy), or null. */
		private final String element;
		private final int elementPercent;

		private Result(Band band, List<Style> weakest, List<Style> freeWeakest, String element, int elementPercent)
		{
			this.band = band;
			this.weakest = weakest;
			this.freeWeakest = freeWeakest;
			this.element = element;
			this.elementPercent = elementPercent;
		}
	}

	private DefenceRolls()
	{
	}

	static Result of(MonsterData monster)
	{
		if (monster == null || !monster.hasDefenceRollInputs())
		{
			return new Result(Band.NO_DATA, List.of(), List.of(), null, 0);
		}

		String element = element(monster);
		int percent = element == null ? 0 : monster.getWeaknessPercent();
		Map<Style, Integer> rolls = rolls(monster);

		if (rolls.values().stream().allMatch(v -> v == 0))
		{
			return new Result(Band.CANNOT_MISS, List.of(), List.of(), element, percent);
		}

		List<Style> weakest = nearest(rolls, allStyles());
		if (weakest.size() == Style.values().length)
		{
			return new Result(Band.EVEN, List.of(), List.of(), element, percent);
		}

		List<Style> freeWeakest = List.of();
		if (weakest.equals(List.of(Style.MAGIC)))
		{
			List<Style> free = new ArrayList<>(allStyles());
			free.remove(Style.MAGIC);
			List<Style> nearestFree = nearest(rolls, free);
			if (nearestFree.size() < free.size())
			{
				freeWeakest = nearestFree;
			}
		}
		return new Result(Band.RANKED, weakest, freeWeakest, element, percent);
	}

	/** Each style's defence roll. Lower is easier to hit. */
	static Map<Style, Integer> rolls(MonsterData monster)
	{
		int defence = monster.getDefenceLevel();
		int magic = rollsMagicOffDefence(monster.getPageName()) ? defence : monster.getMagicLevel();

		Map<Style, Integer> rolls = new EnumMap<>(Style.class);
		for (Style style : Style.values())
		{
			int level = style.getFamily() == Family.MAGIC ? magic : defence;
			// A bonus below -64 drives the roll negative, which does not mean "even easier" — it
			// means the attacker cannot miss. Clamp, or the ranking inverts.
			rolls.put(style, Math.max(0, (level + 9) * (bonus(monster, style) + 64)));
		}
		return rolls;
	}

	/** The styles among {@code among} whose roll is within {@link #TIE} of the easiest one. */
	private static List<Style> nearest(Map<Style, Integer> rolls, List<Style> among)
	{
		int best = among.stream().mapToInt(rolls::get).min().orElse(0);
		List<Style> out = new ArrayList<>();
		for (Style style : among)
		{
			if (rolls.get(style) <= best * TIE)
			{
				out.add(style);
			}
		}
		return out;
	}

	/**
	 * Collapses a tied set into the shortest honest labels: a whole family that ties becomes
	 * "Melee" or "Ranged" rather than naming all three of its styles.
	 */
	static String describe(List<Style> styles)
	{
		List<String> parts = new ArrayList<>();
		boolean melee = styles.containsAll(family(Family.MELEE));
		boolean ranged = styles.containsAll(family(Family.RANGED));

		if (melee)
		{
			parts.add("Melee");
		}
		if (ranged)
		{
			parts.add("Ranged");
		}
		for (Style style : styles)
		{
			boolean folded = (melee && style.getFamily() == Family.MELEE)
				|| (ranged && style.getFamily() == Family.RANGED);
			if (!folded)
			{
				parts.add(style.getLabel());
			}
		}
		return String.join("/", parts);
	}

	private static List<Style> family(Family family)
	{
		List<Style> out = new ArrayList<>();
		for (Style style : Style.values())
		{
			if (style.getFamily() == family)
			{
				out.add(style);
			}
		}
		return out;
	}

	private static boolean rollsMagicOffDefence(String pageName)
	{
		if (pageName == null)
		{
			return false;
		}
		String page = pageName.toLowerCase(Locale.ROOT);
		return MAGIC_OFF_DEFENCE.stream().anyMatch(page::startsWith);
	}

	private static String element(MonsterData monster)
	{
		String element = monster.getWeaknessElement();
		return element == null || element.trim().isEmpty()
			? null
			: StatFormat.cap(element.trim());
	}

	private static int bonus(MonsterData monster, Style style)
	{
		switch (style)
		{
			case STAB:
				return monster.getStabDefenceBonus();
			case SLASH:
				return monster.getSlashDefenceBonus();
			case CRUSH:
				return monster.getCrushDefenceBonus();
			case STANDARD:
				return monster.getStandardRangeDefenceBonus();
			case HEAVY:
				return monster.getHeavyRangeDefenceBonus();
			case LIGHT:
				return monster.getLightRangeDefenceBonus();
			default:
				return monster.getMagicDefenceBonus();
		}
	}

	/** Kept so the styles list reads in a fixed order regardless of map iteration. */
	static List<Style> allStyles()
	{
		return Arrays.asList(Style.values());
	}
}
