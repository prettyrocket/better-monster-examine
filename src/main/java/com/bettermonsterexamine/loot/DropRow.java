package com.bettermonsterexamine.loot;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * One drop row parsed from a monster's rendered OSRS Wiki page: the item, its quantity as the wiki
 * shows it, the rarity text, and the two heading levels it sits under — an optional <b>group</b>
 * (e.g. {@code "Warriors' Guild Basement"}, {@code "Wilderness Slayer Cave"}, {@code "Level 99 drops"})
 * and the <b>section</b> within it (e.g. {@code "Herbs"}, {@code "Gem drop table"}, {@code "Pre-roll"}).
 *
 * <p>The headings are the whole point: they're the wiki's own editorial grouping, which the structured
 * drop data can't express (it carries no section field). Keeping the group <b>distinct</b> from the
 * section is what stops a monster whose tables are split by location from having them merged — a
 * Cyclops' top-floor and basement {@code "100%"} / {@code "Herbs"} tables are different tables with
 * different rates, and only the basement's {@code "Pre-roll"} drops the Dragon defender. Price,
 * high-alch and icon aren't stored here — they come from the RuneLite client by item id at render time.
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
	/**
	 * The heading above this row's section, scoping it to a location or combat level — or {@code ""}
	 * when the page's drops aren't split that way (the common case).
	 */
	private final String group;
	/** The wiki section heading this row sits under — the drop table it belongs to. */
	private final String section;

	/** True when this drop always occurs (rarity {@code "Always"}), rather than rolling a chance. */
	public boolean isAlways()
	{
		return rarity != null && rarity.trim().equalsIgnoreCase("Always");
	}
}
