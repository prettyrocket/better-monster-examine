package com.bettermonsterexamine.loot;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

/**
 * Locks the deterministic sectioning engine {@link DropTable} implements: rows split into one table
 * per variant (first-seen order), each table's rows grouped into the fixed section order
 * (100% → Other → Rare drop table) and sorted most-common first within a section.
 */
public class DropTableTest
{
	private static final Gson GSON = new Gson();

	/** Build a row from its {@code drop_json} fields, optionally flagged onto the rare drop table. */
	private static DropRow row(String dropJson, boolean rdt)
	{
		DropRow r = GSON.fromJson(dropJson, DropRow.class);
		if (rdt)
		{
			r.setRareDropTable("");
		}
		return r;
	}

	@Test
	public void groupsByVariantInFirstSeenOrder()
	{
		List<DropRow> rows = new ArrayList<>();
		rows.add(row("{\"Rarity\":\"1/10\",\"Dropped item\":\"A\",\"Dropped from\":\"M#Two\"}", false));
		rows.add(row("{\"Rarity\":\"1/10\",\"Dropped item\":\"B\",\"Dropped from\":\"M#One\"}", false));
		rows.add(row("{\"Rarity\":\"1/10\",\"Dropped item\":\"C\",\"Dropped from\":\"M#Two\"}", false));

		List<DropTable> tables = DropTable.group(rows);

		assertEquals(2, tables.size());
		// First-seen order: "Two" appeared before "One".
		assertEquals("Two", tables.get(0).getVariant());
		assertEquals("One", tables.get(1).getVariant());
	}

	@Test
	public void singleVariantPageLabelsAsAllDrops()
	{
		List<DropRow> rows = new ArrayList<>();
		rows.add(row("{\"Rarity\":\"Always\",\"Dropped item\":\"Bones\",\"Dropped from\":\"Goblin\"}", false));

		List<DropTable> tables = DropTable.group(rows);

		assertEquals(1, tables.size());
		assertEquals("", tables.get(0).getVariant());
		assertEquals("All drops", tables.get(0).displayName());
	}

	@Test
	public void sectionsFollowFixedOrderAndOmitEmpties()
	{
		List<DropRow> rows = new ArrayList<>();
		// Deliberately out of display order: Other, then a 100%, then a rare-drop-table roll.
		rows.add(row("{\"Rarity\":\"1/50\",\"Dropped item\":\"Coins\"}", false));
		rows.add(row("{\"Rarity\":\"Always\",\"Dropped item\":\"Big bones\"}", false));
		rows.add(row("{\"Rarity\":\"1/512\",\"Dropped item\":\"Rune spear\"}", true));

		DropTable table = DropTable.group(rows).get(0);
		List<DropTable.Section> sections = table.getSections();

		assertEquals(3, sections.size());
		assertEquals(DropTable.SECTION_HUNDRED, sections.get(0).getLabel());
		assertEquals(DropTable.SECTION_OTHER, sections.get(1).getLabel());
		assertEquals(DropTable.SECTION_RARE_DROP_TABLE, sections.get(2).getLabel());
	}

	@Test
	public void rowsWithinASectionSortMostCommonFirst()
	{
		List<DropRow> rows = new ArrayList<>();
		rows.add(row("{\"Rarity\":\"1/100\",\"Dropped item\":\"Rare\"}", false));
		rows.add(row("{\"Rarity\":\"1/5\",\"Dropped item\":\"Common\"}", false));
		rows.add(row("{\"Rarity\":\"1/20\",\"Dropped item\":\"Mid\"}", false));

		DropTable.Section other = DropTable.group(rows).get(0).getSections().get(0);

		assertEquals(DropTable.SECTION_OTHER, other.getLabel());
		assertEquals("Common", other.getRows().get(0).getItem());
		assertEquals("Mid", other.getRows().get(1).getItem());
		assertEquals("Rare", other.getRows().get(2).getItem());
	}
}
