package com.bettermonsterexamine.loot;

import java.util.Collections;
import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The drop rows for a single monster variant, keyed by its {@code page_name#version_anchor}
 * ({@link #pageNameSub}). {@link DropTableService} fetches drops a whole page at a time and groups
 * them into one {@code DropTable} per variant, so the UI can show only the table for the variant
 * currently being viewed. The row order is the wiki's (drops arrive section-ordered by drop type).
 */
@Getter
@RequiredArgsConstructor
public class DropTable
{
	private final String pageNameSub;
	private final List<DropRow> rows;

	/** True when this variant has no drop rows (a valid outcome — e.g. Vorkath's pre-quest form). */
	public boolean isEmpty()
	{
		return rows.isEmpty();
	}

	/** An empty table for a variant with no drops, so callers never juggle nulls. */
	static DropTable empty(String pageNameSub)
	{
		return new DropTable(pageNameSub, Collections.emptyList());
	}
}
