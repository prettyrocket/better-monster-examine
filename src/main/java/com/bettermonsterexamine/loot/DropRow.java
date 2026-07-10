package com.bettermonsterexamine.loot;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * One drop row parsed from a monster's rendered OSRS Wiki page: the item, its quantity as the wiki
 * shows it, the rarity text, and the <b>section</b> heading it sits under (e.g. {@code "Herbs"},
 * {@code "Gem drop table"}, {@code "Catacombs tertiary"}, {@code "Wilderness Slayer Cave tertiary"}).
 *
 * <p>The section is the whole point: it's the wiki's own editorial grouping, which the structured
 * {@code dropsline} Bucket can't express (it can't even see the region tables). Price, high-alch and
 * icon aren't stored here — they come from the RuneLite client by item id at render time.
 */
@Getter
@RequiredArgsConstructor
public class DropRow
{
	private final String item;
	/** Quantity as the wiki renders it, e.g. {@code "1"}, {@code "35–55"}, {@code "60 (noted)"}. */
	private final String quantity;
	/** Rarity as the wiki renders it, e.g. {@code "Always"}, {@code "1/128"}, {@code "1/4,096"}. */
	private final String rarity;
	/** The wiki section heading this row sits under — the drop table it belongs to. */
	private final String section;

	/** True when this drop always occurs (rarity {@code "Always"}), rather than rolling a chance. */
	public boolean isAlways()
	{
		return rarity != null && rarity.trim().equalsIgnoreCase("Always");
	}
}
