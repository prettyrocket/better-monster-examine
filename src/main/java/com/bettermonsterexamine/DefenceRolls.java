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
 * <p>Pure and unit-tested; the renderers shape the {@link Result} into text.
 */
final class DefenceRolls
{
	/** Wiki pages whose magic defence rolls off Defence level rather than Magic level. */
	private static final Set<String> MAGIC_OFF_DEFENCE = Set.of(
		"verzik vitur", "ice demon", "fragment of seren", "baboon brawler", "rabbit (prifddinas)");

	/** Below this the winner is not worth naming — the styles are close enough to be a wash. */
	private static final double MARGINAL = 1.25d;
	/** At or above this the winner is decisively ahead. */
	private static final double DECISIVE = 2.0d;

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
	 * How much the ranking is worth saying out loud. {@link #NO_DATA} means the wiki carries no
	 * defensive numbers at all — distinct from every bonus genuinely being zero.
	 */
	enum Band
	{
		NO_DATA, ALWAYS_HITS, FLAT, MARGINAL, CLEAR, DECISIVE
	}

	@Getter
	static final class Result
	{
		private final Band band;
		/** The easiest styles to land, tied. Empty when {@link Band#NO_DATA}. */
		private final List<Style> weakest;
		/** The styles at the next roll up — what {@link #getMargin()} is measured against. */
		private final List<Style> runnerUp;
		/** The easiest style that costs no runes — magic wins on ~70% of the bestiary. */
		private final List<Style> bestNonMagic;
		/** How much harder the next-best style is; 0 when there is no next. */
		private final double margin;
		/** How much accuracy you give up by not casting; 1 when a free style already ties. */
		private final double nonMagicCost;
		/** Capitalised elemental weakness (a damage multiplier, not accuracy), or null. */
		private final String element;
		private final int elementPercent;

		private Result(Band band, List<Style> weakest, List<Style> runnerUp, List<Style> bestNonMagic,
			double margin, double nonMagicCost, String element, int elementPercent)
		{
			this.band = band;
			this.weakest = weakest;
			this.runnerUp = runnerUp;
			this.bestNonMagic = bestNonMagic;
			this.margin = margin;
			this.nonMagicCost = nonMagicCost;
			this.element = element;
			this.elementPercent = elementPercent;
		}

		/** True when magic alone is the easiest style — the case where the element is worth naming. */
		boolean isMagicOnly()
		{
			return weakest.size() == 1 && weakest.get(0) == Style.MAGIC;
		}

		/** True when the ranking is worth showing at all. */
		boolean isActionable()
		{
			return band == Band.CLEAR || band == Band.DECISIVE;
		}
	}

	private DefenceRolls()
	{
	}

	static Result of(MonsterData monster)
	{
		if (monster == null || !monster.hasDefenceRollInputs())
		{
			return new Result(Band.NO_DATA, List.of(), List.of(), List.of(), 0d, 0d, null, 0);
		}

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

		int best = rolls.values().stream().mapToInt(Integer::intValue).min().orElse(0);
		List<Style> weakest = stylesAt(rolls, best);

		int altBest = rolls.entrySet().stream()
			.filter(e -> e.getKey() != Style.MAGIC)
			.mapToInt(Map.Entry::getValue)
			.min().orElse(0);
		List<Style> bestNonMagic = new ArrayList<>();
		for (Style style : Style.values())
		{
			if (style != Style.MAGIC && rolls.get(style) == altBest)
			{
				bestNonMagic.add(style);
			}
		}

		String element = element(monster);
		int percent = element == null ? 0 : monster.getWeaknessPercent();

		if (best == 0)
		{
			return new Result(Band.ALWAYS_HITS, weakest, List.of(), bestNonMagic, 0d, 0d, element, percent);
		}

		int next = rolls.values().stream().mapToInt(Integer::intValue)
			.filter(v -> v > best).min().orElse(0);
		if (next == 0)
		{
			return new Result(Band.FLAT, weakest, List.of(), bestNonMagic, 0d, 1d, element, percent);
		}

		double margin = (double) next / best;
		Band band = margin >= DECISIVE ? Band.DECISIVE : margin >= MARGINAL ? Band.CLEAR : Band.MARGINAL;
		return new Result(band, weakest, stylesAt(rolls, next), bestNonMagic,
			margin, (double) altBest / best, element, percent);
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

	private static List<Style> stylesAt(Map<Style, Integer> rolls, int value)
	{
		List<Style> out = new ArrayList<>();
		for (Style style : Style.values())
		{
			if (rolls.get(style) == value)
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
