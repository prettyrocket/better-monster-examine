package com.bettermonsterexamine;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.StringJoiner;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;

/** Formatter for the compact combat block appended to a normal NPC Examine response. */
final class ExamineSummary
{
	private static final Color MELEE_COLOR = new Color(0xFF4040);
	private static final Color RANGED_COLOR = new Color(0x5FC96B);
	private static final Color ELEMENT_COLOR = new Color(0x56B4E9);
	private static final String[] MELEE_NAMES = {"Stab", "Slash", "Crush"};
	private static final String[] RANGED_NAMES = {"Standard", "Heavy", "Light"};

	private ExamineSummary()
	{
	}

	static List<String> format(MonsterData monster, ExamineSummaryMode mode)
	{
		if (monster == null || mode == null)
		{
			return Collections.emptyList();
		}

		List<String> lines = new ArrayList<>(3);
		if (mode == ExamineSummaryMode.WEAKNESSES)
		{
			lines.add(weaknesses(monster));
			return lines;
		}

		lines.add(colored("Melee:", MELEE_COLOR) + " Stab " + StatFormat.bonus(monster.getStabDefenceBonus())
			+ " | Slash " + StatFormat.bonus(monster.getSlashDefenceBonus())
			+ " | Crush " + StatFormat.bonus(monster.getCrushDefenceBonus()));
		lines.add(colored("Ranged:", RANGED_COLOR) + " Standard " + StatFormat.bonus(monster.getStandardRangeDefenceBonus())
			+ " | Heavy " + StatFormat.bonus(monster.getHeavyRangeDefenceBonus())
			+ " | Light " + StatFormat.bonus(monster.getLightRangeDefenceBonus()));

		String element = weaknessElement(monster);
		if (element != null)
		{
			lines.add(colored("Elemental weakness:", ELEMENT_COLOR) + ' ' + element + ' '
				+ monster.getWeaknessPercent() + '%');
		}
		return lines;
	}

	private static String weaknesses(MonsterData monster)
	{
		StringBuilder line = new StringBuilder(colored("Weakest melee:", MELEE_COLOR)).append(' ')
			.append(weakest(MELEE_NAMES, new int[]{
				monster.getStabDefenceBonus(),
				monster.getSlashDefenceBonus(),
				monster.getCrushDefenceBonus()
			}))
			.append(" | ").append(colored("Ranged:", RANGED_COLOR)).append(' ')
			.append(weakest(RANGED_NAMES, new int[]{
				monster.getStandardRangeDefenceBonus(),
				monster.getHeavyRangeDefenceBonus(),
				monster.getLightRangeDefenceBonus()
			}));

		String element = weaknessElement(monster);
		if (element != null)
		{
			line.append(" | ").append(colored("Elemental weakness:", ELEMENT_COLOR)).append(' ')
				.append(element).append(' ').append(monster.getWeaknessPercent()).append('%');
		}
		return line.toString();
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

	/** Lowest defence bonus wins, and ties are retained instead of choosing one arbitrarily. */
	private static String weakest(String[] names, int[] bonuses)
	{
		int minimum = bonuses[0];
		for (int bonus : bonuses)
		{
			minimum = Math.min(minimum, bonus);
		}

		StringJoiner styles = new StringJoiner("/");
		for (int i = 0; i < bonuses.length; i++)
		{
			if (bonuses[i] == minimum)
			{
				styles.add(names[i]);
			}
		}
		return styles + " (" + StatFormat.bonus(minimum) + ')';
	}

	private static String weaknessElement(MonsterData monster)
	{
		String element = monster.getWeaknessElement();
		return element == null || element.trim().isEmpty()
			? null
			: Text.escapeJagex(StatFormat.cap(element.trim()));
	}
}
