package com.bettermonsterexamine.loot;

import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Exercises {@link DropPageService#parse}, the pure HTML parse of a rendered monster page: each
 * drop-table row (item-col link · quantity · rarity cell) inherits the {@code <h3>/<h4>} section
 * heading above it, and only the Drops section (up to the next {@code <h2>}) is read. The fixture
 * mirrors the live wiki's row layout: [inventory-image][item-col][qty][table-bg rarity][ge][alch].
 */
public class DropPageServiceTest
{
	private static String row(String slug, String name, String qty, String rarityClass, String rarity)
	{
		return "<tr>"
			+ "<td class=\"inventory-image\"><img src=\"x\"></td>"
			+ "<td class=\"item-col\"><a href=\"/w/" + slug + "\">" + name + "</a></td>"
			+ "<td>" + qty + "</td>"
			+ "<td class=\"" + rarityClass + "\">" + rarity + "</td>"
			+ "<td class=\"ge-column\">9,000</td>"
			+ "<td class=\"alch-column\">1</td>"
			+ "</tr>";
	}

	private static final String HTML =
		"<p>Intro paragraph.</p>"
		+ "<h2 id=\"Drops\">Drops</h2>"
		+ "<h4 id=\"Herbs\">Herbs</h4>"
		+ "<table>"
		+ row("Grimy_ranarr_weed", "Grimy ranarr weed", "1", "table-bg-uncommon", "1/43.8")
		+ row("Grimy_avantoe", "Grimy avantoe", "1", "table-bg-uncommon", "1/80.3")
		+ "</table>"
		+ "<h4 id=\"Wilderness_Slayer_Cave_tertiary\">Wilderness Slayer Cave tertiary</h4>"
		+ "<table>"
		+ row("Larran%27s_key", "Larran's key", "1", "table-bg-rare", "1/100&#160;[d 1]")
		+ "</table>"
		+ "<h2 id=\"Store_locations\">Store locations</h2>"
		+ "<table>" + row("Should_not_appear", "x", "1", "table-bg", "1/1") + "</table>";

	@Test
	public void parsesRowsUnderTheirWikiSectionsWithinTheDropsRegion()
	{
		List<DropRow> rows = DropPageService.parse(HTML);

		assertEquals(3, rows.size());

		assertEquals("Grimy ranarr weed", rows.get(0).getItem());
		assertEquals("1", rows.get(0).getQuantity());
		assertEquals("1/43.8", rows.get(0).getRarity());
		assertEquals("Herbs", rows.get(0).getSection());

		assertEquals("Grimy avantoe", rows.get(1).getItem());
		assertEquals("Herbs", rows.get(1).getSection());

		// URL-decoded name, region section, and a footnote marker stripped off the rarity.
		assertEquals("Larran's key", rows.get(2).getItem());
		assertEquals("Wilderness Slayer Cave tertiary", rows.get(2).getSection());
		assertEquals("1/100", rows.get(2).getRarity());
	}

	@Test
	public void stopsAtTheNextH2SoNonDropTablesAreExcluded()
	{
		List<DropRow> rows = DropPageService.parse(HTML);
		assertTrue(rows.stream().noneMatch(r -> "x".equals(r.getItem())));
	}

	@Test
	public void rowsBeforeAnySectionHeadingAreSkipped()
	{
		String html = "<h2 id=\"Drops\">Drops</h2>"
			+ "<table>" + row("Orphan", "Orphan", "1", "table-bg", "1/1") + "</table>";
		assertTrue(DropPageService.parse(html).isEmpty());
	}

	@Test
	public void nullHtmlYieldsNull()
	{
		assertNull(DropPageService.parse(null));
	}

	@Test
	public void cleanStripsTagsAndUnescapesEntities()
	{
		assertEquals("A & B", DropPageService.clean("<b>A</b> &amp; B"));
		assertEquals("2–3", DropPageService.clean("2&#8211;3"));
	}
}
