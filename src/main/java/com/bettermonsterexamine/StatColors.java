package com.bettermonsterexamine;

import java.awt.Color;
import net.runelite.client.ui.ColorScheme;

/**
 * Player-relevant stat colours, shared by the side panel and the in-game overlay so both
 * honour the same {@link HighlightMode} palette. "Danger" marks values that are bad for the
 * player (aggressive, positive flat armour, over-HP max hits); "good" marks values in the
 * player's favour (negative flat armour, an XP bonus); the level colour mirrors the in-game
 * monster-hover gradient.
 */
final class StatColors
{
	/** Red for player-dangerous values. */
	static final Color DANGER_RED = new Color(0xFF4040);

	/** Okabe-Ito orange/blue: colour-blind-distinguishable stand-ins for the red/green cues. */
	static final Color CB_DANGER = new Color(0xE69F00);
	static final Color CB_GOOD = new Color(0x56B4E9);

	private StatColors()
	{
	}

	/** Resolve a semantic {@link ColourRole} to a concrete colour for the active mode. */
	static Color resolve(ColourRole role, HighlightMode mode)
	{
		switch (role)
		{
			case DANGER:
				return danger(mode);
			case GOOD:
				return good(mode);
			default:
				return Color.WHITE;
		}
	}

	/** The "danger" highlight colour for the active mode; white when highlighting is off. */
	private static Color danger(HighlightMode mode)
	{
		switch (mode)
		{
			case OFF:
				return Color.WHITE;
			case COLOUR_BLIND:
				return CB_DANGER;
			default:
				return DANGER_RED;
		}
	}

	/** The "good for the player" highlight colour for the active mode; white when off. */
	private static Color good(HighlightMode mode)
	{
		switch (mode)
		{
			case OFF:
				return Color.WHITE;
			case COLOUR_BLIND:
				return CB_GOOD;
			default:
				return ColorScheme.PROGRESS_COMPLETE_COLOR;
		}
	}

	/** Combat-level colour for the active mode: the in-game gradient (Standard) or orange/blue (CB). */
	static Color levelColor(HighlightMode mode, int playerLevel, int npcLevel)
	{
		switch (mode)
		{
			case OFF:
				return Color.WHITE;
			case COLOUR_BLIND:
				if (playerLevel <= 0 || npcLevel == playerLevel)
				{
					return Color.WHITE;
				}
				return npcLevel > playerLevel ? CB_DANGER : CB_GOOD;
			default:
				return combatLevelColor(playerLevel, npcLevel);
		}
	}

	/**
	 * The in-game combat-level colour for the monster hover: green when it's well below the
	 * player, yellow at parity, orange→red as it climbs above, matching RuneScape's bands by
	 * the (player − monster) level difference. White when the player level is unknown.
	 */
	private static Color combatLevelColor(int playerLevel, int npcLevel)
	{
		if (playerLevel <= 0)
		{
			return Color.WHITE;
		}
		int d = playerLevel - npcLevel;
		if (d < -9)
		{
			return new Color(0xFF0000);
		}
		if (d < -6)
		{
			return new Color(0xFF3000);
		}
		if (d < -3)
		{
			return new Color(0xFF7000);
		}
		if (d < 0)
		{
			return new Color(0xFFB000);
		}
		if (d > 9)
		{
			return new Color(0x00FF00);
		}
		if (d > 6)
		{
			return new Color(0x40FF00);
		}
		if (d > 3)
		{
			return new Color(0x80FF00);
		}
		if (d > 0)
		{
			return new Color(0xC0FF00);
		}
		return new Color(0xFFFF00);
	}
}
