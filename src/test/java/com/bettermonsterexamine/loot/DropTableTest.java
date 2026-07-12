package com.bettermonsterexamine.loot;

import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

/**
 * Locks {@link DropTable#of}: parsed rows group by group then section in the wiki page's order
 * (first-seen), preserving row order within a section, and a blank section falls back to {@code "Other"}.
 * Crucially, like-named sections under <b>different</b> groups stay distinct rather than merging.
 */
public class DropTableTest
{
	private static DropRow row(String item, String section)
	{
		return row(item, "", section);
	}

	private static DropRow row(String item, String group, String section)
	{
		return new DropRow(item, "1", "1/128", group, section);
	}

	@Test
	public void groupsBySectionInFirstSeenOrderPreservingRowOrder()
	{
		List<DropRow> rows = new ArrayList<>();
		rows.add(row("Grimy ranarr weed", "Herbs"));
		rows.add(row("Uncut sapphire", "Gem drop table"));
		rows.add(row("Grimy avantoe", "Herbs"));
		rows.add(row("Ancient shard", "Catacombs tertiary"));

		List<DropTable.Group> groups = DropTable.of(rows).getGroups();

		// No group headings on the page: one unlabelled group holding every section.
		assertEquals(1, groups.size());
		assertEquals("", groups.get(0).getLabel());

		List<DropTable.Section> sections = groups.get(0).getSections();
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
	public void likeNamedSectionsInDifferentGroupsStayDistinct()
	{
		// The Cyclops shape: both Warriors' Guild locations have a "100%" table, but they are different
		// tables. Merging them by name is what let the basement-only Dragon defender read as a drop from
		// the top-floor cyclopes too.
		List<DropRow> rows = new ArrayList<>();
		rows.add(row("Big bones", "Warriors' Guild Top Floor", "100%"));
		rows.add(row("Rune defender", "Warriors' Guild Top Floor", "Defenders"));
		rows.add(row("Big bones", "Warriors' Guild Basement", "100%"));
		rows.add(row("Dragon defender", "Warriors' Guild Basement", "Pre-roll"));

		List<DropTable.Group> groups = DropTable.of(rows).getGroups();

		assertEquals(2, groups.size());
		assertEquals("Warriors' Guild Top Floor", groups.get(0).getLabel());
		assertEquals("Warriors' Guild Basement", groups.get(1).getLabel());

		// Each location keeps its own "100%" — one row each, not one merged section of two.
		assertEquals(2, groups.get(0).getSections().size());
		assertEquals("100%", groups.get(0).getSections().get(0).getLabel());
		assertEquals(1, groups.get(0).getSections().get(0).getRows().size());
		assertEquals("Defenders", groups.get(0).getSections().get(1).getLabel());

		assertEquals(2, groups.get(1).getSections().size());
		assertEquals("100%", groups.get(1).getSections().get(0).getLabel());
		assertEquals(1, groups.get(1).getSections().get(0).getRows().size());
		assertEquals("Pre-roll", groups.get(1).getSections().get(1).getLabel());
		assertEquals("Dragon defender", groups.get(1).getSections().get(1).getRows().get(0).getItem());
	}

	@Test
	public void blankSectionFallsBackToOther()
	{
		List<DropRow> rows = new ArrayList<>();
		rows.add(row("Coins", ""));

		List<DropTable.Group> groups = DropTable.of(rows).getGroups();

		assertEquals(1, groups.size());
		assertEquals(1, groups.get(0).getSections().size());
		assertEquals("Other", groups.get(0).getSections().get(0).getLabel());
	}
}
