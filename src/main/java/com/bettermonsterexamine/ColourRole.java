package com.bettermonsterexamine;

/**
 * The player-relevant meaning of a stat value, independent of any concrete colour. The
 * {@link MonsterStats} view-model decides the role (e.g. positive flat armour is bad for the
 * player → {@link #DANGER}); each renderer maps it to a colour via
 * {@link StatColors#resolve(ColourRole, HighlightMode)}, so the colour-blind palette stays in
 * one place.
 */
enum ColourRole
{
	/** No player-relevant meaning — plain white text. */
	NEUTRAL,
	/** Bad for the player (aggressive, positive flat armour, over-HP max hit, immunities). */
	DANGER,
	/** In the player's favour (negative flat armour, an XP bonus). */
	GOOD
}
