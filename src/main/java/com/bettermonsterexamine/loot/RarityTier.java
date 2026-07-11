package com.bettermonsterexamine.loot;

/**
 * How rare a drop is, used to colour-code its odds. Derived purely from the drop probability
 * ({@link DropFormat#tierOf}): {@code Always} and unknown rarities are {@code COMMON}.
 */
enum RarityTier
{
	COMMON,
	UNCOMMON,
	RARE,
	ULTRA_RARE
}
