package com.bettermonsterexamine;

import java.awt.Color;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;

/** Formatter for the compact combat block appended to a normal NPC Examine response. */
final class ExamineSummary
{
	// Each element in its rune's colour, twice over: the opaque chatbox is light parchment and the
	// transparent one is dark, and no single shade reads on both.
	private static final Color FIRE_OPAQUE = new Color(0xB22800);
	private static final Color FIRE_TRANSPARENT = new Color(0xFF7A3D);
	private static final Color WATER_OPAQUE = new Color(0x0038B8);
	private static final Color WATER_TRANSPARENT = new Color(0x5CA8FF);
	private static final Color EARTH_OPAQUE = new Color(0x6E3B0E);
	private static final Color EARTH_TRANSPARENT = new Color(0xD09A5E);
	private static final Color AIR_OPAQUE = new Color(0x4F5B66);
	private static final Color AIR_TRANSPARENT = new Color(0xB9E6F2);

	// The panel's highlights, with a darker shade for the parchment chatbox. The colour-blind
	// shade is Okabe-Ito blue; yellow is already colour-blind safe, so one shade serves both modes.
	private static final Color GOOD_OPAQUE = new Color(0x006600);
	private static final Color CB_GOOD_OPAQUE = new Color(0x0072B2);
	private static final Color NEXT_OPAQUE = new Color(0x7A5C00);

	private static final String GAP = "  ";

	private ExamineSummary()
	{
	}

	/**
	 * @param transparentChat whether the chatbox is transparent, which picks each colour's shade
	 * @param rolls show defence rolls rather than bonuses, when the monster has every roll input
	 * @param highlight the palette for the styles the Weakness line would name
	 */
	static List<String> format(MonsterData monster, ExamineSummaryMode mode, boolean transparentChat,
		boolean rolls, HighlightMode highlight)
	{
		if (monster == null || mode == null)
		{
			return Collections.emptyList();
		}
		if (mode == ExamineSummaryMode.ALL_DEFENCES)
		{
			return allDefences(monster, transparentChat, rolls && monster.hasDefenceRollInputs(), highlight);
		}

		String weakness = weakness(monster, transparentChat);
		return weakness == null ? Collections.emptyList() : List.of(weakness);
	}

	/**
	 * Every defence in the panel's order, melee then magic and ranged, in place of the Weakness
	 * line: the styles it would name are coloured instead, so it would only repeat them.
	 */
	private static List<String> allDefences(MonsterData monster, boolean transparentChat, boolean rolls,
		HighlightMode highlight)
	{
		Map<DefenceRolls.Style, Integer> values = rolls ? DefenceRolls.rolls(monster) : null;
		Map<DefenceRolls.Style, ColourRole> roles = DefenceRolls.roles(monster);
		Function<DefenceRolls.Style, String> cell = style ->
		{
			String value = rolls ? StatFormat.roll(values.get(style)) : StatFormat.bonus(DefenceRolls.bonus(monster, style));
			String text = shortLabel(style) + ' ' + value;
			Color color = roleColor(roles.get(style), highlight, transparentChat);
			return color == null ? text : ColorUtil.wrapWithColorTag(text, color);
		};

		String magic = cell.apply(DefenceRolls.Style.MAGIC);
		String element = element(monster);
		if (element != null)
		{
			magic += " (" + colored(element + ' ' + monster.getWeaknessPercent() + '%', element, transparentChat) + ')';
		}
		return List.of(
			String.join(GAP, cell.apply(DefenceRolls.Style.STAB), cell.apply(DefenceRolls.Style.SLASH),
				cell.apply(DefenceRolls.Style.CRUSH)),
			String.join(GAP, magic, cell.apply(DefenceRolls.Style.LIGHT), cell.apply(DefenceRolls.Style.STANDARD),
				cell.apply(DefenceRolls.Style.HEAVY)));
	}

	/** "Std" keeps the magic and ranged line from wrapping in a fixed-size chatbox. */
	private static String shortLabel(DefenceRolls.Style style)
	{
		return style == DefenceRolls.Style.STANDARD ? "Std" : style.getLabel();
	}

	/** A highlight in a shade that reads on this chatbox, or null to leave the text plain. */
	private static Color roleColor(ColourRole role, HighlightMode highlight, boolean transparentChat)
	{
		if (role == null || highlight == HighlightMode.OFF)
		{
			return null;
		}
		if (role == ColourRole.NEXT)
		{
			return transparentChat ? StatColors.NEXT_YELLOW : NEXT_OPAQUE;
		}
		if (highlight == HighlightMode.COLOUR_BLIND)
		{
			return transparentChat ? StatColors.CB_GOOD : CB_GOOD_OPAQUE;
		}
		return transparentChat ? StatColors.resolve(ColourRole.GOOD, highlight) : GOOD_OPAQUE;
	}

	/**
	 * The one line that answers "what do I hit this with", ranked by defence roll rather than by
	 * raw bonus — see {@link DefenceRolls}. A comma means "then" and a slash means "tied":
	 * {@code Magic (Water), Ranged} is magic first and ranged as the best style that costs no
	 * runes. Null when the wiki carries no defensive numbers, which drops the line rather than
	 * printing a seven-way tie of zeroes.
	 *
	 * <p>The elemental weakness rides in the magic label and nowhere else: it is a damage
	 * multiplier, not accuracy, so naming it beside a melee or ranged answer would read as an
	 * endorsement of casting on a monster that resists it. It is the only thing coloured.
	 */
	private static String weakness(MonsterData monster, boolean transparentChat)
	{
		DefenceRolls.Result rolls = DefenceRolls.of(monster);
		switch (rolls.getBand())
		{
			case NO_DATA:
				return null;
			case CANNOT_MISS:
				return "Weakness: anything";
			case EVEN:
				return "Weakness: none";
			default:
				break;
		}

		String line = "Weakness: " + styles(rolls.getWeakest(), rolls.getElement(), transparentChat);
		if (!rolls.getFreeWeakest().isEmpty())
		{
			line += ", " + DefenceRolls.describe(rolls.getFreeWeakest());
		}
		return line;
	}

	private static String styles(List<DefenceRolls.Style> styles, String element, boolean transparentChat)
	{
		String described = DefenceRolls.describe(styles);
		if (element == null || !styles.contains(DefenceRolls.Style.MAGIC))
		{
			return described;
		}
		return described.replace("Magic", "Magic (" + colored(Text.escapeJagex(element), element, transparentChat) + ')');
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

	/** {@code text} in {@code element}'s colour, or left plain for an element with none. */
	private static String colored(String text, String element, boolean transparentChat)
	{
		Color color = elementColor(element, transparentChat);
		return color == null ? text : ColorUtil.wrapWithColorTag(text, color);
	}

	private static Color elementColor(String element, boolean transparentChat)
	{
		switch (element.toLowerCase(Locale.ROOT))
		{
			case "fire":
				return transparentChat ? FIRE_TRANSPARENT : FIRE_OPAQUE;
			case "water":
				return transparentChat ? WATER_TRANSPARENT : WATER_OPAQUE;
			case "earth":
				return transparentChat ? EARTH_TRANSPARENT : EARTH_OPAQUE;
			case "air":
				return transparentChat ? AIR_TRANSPARENT : AIR_OPAQUE;
			default:
				return null;
		}
	}

	private static String element(MonsterData monster)
	{
		String element = monster.getWeaknessElement();
		return element == null || element.trim().isEmpty()
			? null
			: Text.escapeJagex(StatFormat.cap(element.trim()));
	}
}
