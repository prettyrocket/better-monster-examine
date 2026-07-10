package com.bettermonsterexamine.loot;

import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

/**
 * Locks {@link DropTable#of}: parsed rows group into sections in the wiki page's order (first-seen),
 * preserving row order within a section, and a blank section falls back to {@code "Other"}.
 */
public class DropTableTest
{
	private static DropRow row(String item, String section)
	{
		return new DropRow(item, "1", "1/128", section);
	}

	@Test
	public void groupsBySectionInFirstSeenOrderPreservingRowOrder()
	{
		List<DropRow> rows = new ArrayList<>();
		rows.add(row("Grimy ranarr weed", "Herbs"));
		rows.add(row("Uncut sapphire", "Gem drop table"));
		rows.add(row("Grimy avantoe", "Herbs"));
		rows.add(row("Ancient shard", "Catacombs tertiary"));

		List<DropTable.Section> sections = DropTable.of(rows).getSections();

		assertEquals(3, sections.size());
		// Section order follows first appearance: Herbs, then Gem drop table, then Catacombs.
		assertEquals("Herbs", sections.get(0).getLabel());
		assertEquals("Gem drop table", sections.get(1).getLabel());
		assertEquals("Catacombs tertiary", sections.get(2).getLabel());

		// Rows keep their page order within the section.
		assertEquals(2, sections.get(0).getRows().size());
		assertEquals("Grimy ranarr weed", sections.get(0).getRows().get(0).getItem());
		assertEquals("Grimy avantoe", sections.get(0).getRows().get(1).getItem());
	}

	@Test
	public void blankSectionFallsBackToOther()
	{
		List<DropRow> rows = new ArrayList<>();
		rows.add(row("Coins", ""));

		List<DropTable.Section> sections = DropTable.of(rows).getSections();

		assertEquals(1, sections.size());
		assertEquals("Other", sections.get(0).getLabel());
	}
}
