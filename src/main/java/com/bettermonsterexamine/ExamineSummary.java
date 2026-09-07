package com.bettermonsterexamine;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;

/** Formatter for the compact combat block appended to a normal NPC Examine response. */
final class ExamineSummary
{
	private static final Color MELEE_COLOR = new Color(0xFF4040);
	private static final Color RANGED_COLOR = new Color(0x5FC96B);
	private static final Color ELEMENT_COLOR = new Color(0x56B4E9);

	private ExamineSummary()
	{
	}

	static List<String> format(MonsterData monster, ExamineSummaryMode mode)
	{
		if (monster == null || mode == null)
		{
			return Collections.emptyList();
		}

		List<String> lines = new ArrayList<>(4);
		String weakness = weakness(monster);
		if (weakness != null)
		{
			lines.add(weakness);
		}
		if (mode == ExamineSummaryMode.WEAKNESSES)
		{
			return lines;
		}

		lines.add(colored("Melee:", MELEE_COLOR) + " Stab " + StatFormat.bonus(monster.getStabDefenceBonus())
			+ " | Slash " + StatFormat.bonus(monster.getSlashDefenceBonus())
			+ " | Crush " + StatFormat.bonus(monster.getCrushDefenceBonus()));
		lines.add(colored("Ranged:", RANGED_COLOR) + " Standard " + StatFormat.bonus(monster.getStandardRangeDefenceBonus())
			+ " | Heavy " + StatFormat.bonus(monster.getHeavyRangeDefenceBonus())
			+ " | Light " + StatFormat.bonus(monster.getLightRangeDefenceBonus()));

		String element = element(monster);
		if (element != null)
		{
			lines.add(colored("Elemental weakness:", ELEMENT_COLOR) + ' ' + element + ' '
				+ monster.getWeaknessPercent() + '%');
		}
		return lines;
	}

	/**
	 * The one line that answers "what do I hit this with", ranked by defence roll rather than by
	 * raw bonus — see {@link DefenceRolls}. Null when the wiki carries no defensive numbers, which
	 * drops the line rather than printing a seven-way tie of zeroes.
	 *
	 * <p>The elemental weakness rides in the magic label ("Magic (Fire)") and nowhere else: it is a
	 * damage multiplier, not accuracy, so naming it beside a melee or ranged answer would read as
	 * an endorsement of casting on a monster that resists it.
	 */
	private static String weakness(MonsterData monster)
	{
		DefenceRolls.Result rolls = DefenceRolls.of(monster);
		switch (rolls.getBand())
		{
			case NO_DATA:
				return null;
			case ALWAYS_HITS:
				return label(rolls) + (rolls.getWeakest().size() == DefenceRolls.allStyles().size()
					? "anything (cannot miss)"
					: DefenceRolls.describe(rolls.getWeakest()) + " (cannot miss)");
			case FLAT:
			case MARGINAL:
				return label(rolls) + "nothing in particular";
			default:
				break;
		}

		String weakest = DefenceRolls.describe(rolls.getWeakest());
		if (rolls.isMagicOnly())
		{
			String element = rolls.getElement();
			if (element != null)
			{
				weakest += " (" + Text.escapeJagex(element) + ')';
			}
			return label(rolls) + weakest + ", or " + DefenceRolls.describe(rolls.getBestNonMagic());
		}
		return label(rolls) + weakest + " ("
			+ String.format("%.1f", rolls.getMargin()) + "x over "
			+ DefenceRolls.describe(rolls.getRunnerUp()) + ')';
	}

	/**
	 * The label carries the colour, so the winning family reads at a glance without colouring the
	 * values themselves. A tie spanning families gets no colour rather than an arbitrary one.
	 */
	private static String label(DefenceRolls.Result rolls)
	{
		Color color = familyColor(rolls.getWeakest());
		String text = "Weakest to:";
		return (color == null ? text : colored(text, color)) + ' ';
	}

	private static Color familyColor(List<DefenceRolls.Style> styles)
	{
		if (styles.isEmpty())
		{
			return null;
		}
		DefenceRolls.Family family = styles.get(0).getFamily();
		for (DefenceRolls.Style style : styles)
		{
			if (style.getFamily() != family)
			{
				return null;
			}
		}
		switch (family)
		{
			case MELEE:
				return MELEE_COLOR;
			case RANGED:
				return RANGED_COLOR;
			default:
				return ELEMENT_COLOR;
		}
	}

	/**
	 * The monster's name as it can safely go on a chat line: Jagex formatting escaped so a name
	 * containing tags can't recolour the row, and line breaks flattened so it stays one line.
	 * Null when there's nothing usable left, which tells the caller to leave the line alone.
	 */
	static String chatName(String name)
	{
		if (name == null)
		{
			return null;
		}
		String cleaned = name.replace('\r', ' ').replace('\n', ' ').trim();
		return cleaned.isEmpty() ? null : Text.escapeJagex(cleaned);
	}

	private static String colored(String text, Color color)
	{
		return ColorUtil.wrapWithColorTag(text, color);
	}

	private static String element(MonsterData monster)
	{
		String element = monster.getWeaknessElement();
		return element == null || element.trim().isEmpty()
			? null
			: Text.escapeJagex(StatFormat.cap(element.trim()));
	}
}
