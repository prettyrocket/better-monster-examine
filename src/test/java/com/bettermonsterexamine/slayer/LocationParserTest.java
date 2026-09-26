package com.bettermonsterexamine.slayer;

import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Exercises {@link LocationParser#parse} against the live wiki's Locations table markup: columns found
 * by header, the floor template's UK/US pair reduced to one, abbreviations kept tight, and nothing
 * read from outside the Locations section.
 */
public class LocationParserTest
{
	private static final String MEMBERS = "<span typeof=\"mw:File\"><a href=\"/w/Members\"><img alt=\"Members\" src=\"/images/Member_icon.png\" /></a></span>";
	private static final String F2P = "<span typeof=\"mw:File\"><a href=\"/w/Free-to-play\"><img alt=\"Free-to-play\" src=\"/images/Free-to-play_icon.png\" /></a></span>";

	private static String row(String location, String levels, String members, String spawns)
	{
		return "<tr><td>" + location + "</td><td class=\"\">" + levels + "</td><td>" + members + "</td><td>" + spawns
			+ "</td><td><a class=\"mw-kartographer-maplink\">Show map</a></td><td class=\"leagues-global-flag\">General</td></tr>";
	}

	private static final String HEADER = "<table class=\"wikitable sortable\"><tbody><tr>"
		+ "<th>Location\n</th><th>Levels\n</th><th>Members\n</th><th>Spawns\n</th><th class=\"unsortable\">Map\n</th>"
		+ "<th class=\"leagues-global-flag\"><a href=\"/w/League\">League</a> region\n</th></tr>";

	private static String page(String rows)
	{
		return "<p>Intro</p><div class=\"mw-heading mw-heading2\"><h2 id=\"Locations\">Locations</h2></div>"
			+ HEADER + rows + "</tbody></table>"
			+ "<div class=\"mw-heading mw-heading2\"><h2 id=\"Drops\">Drops</h2></div>"
			+ "<table>" + row("Not a location", "1", MEMBERS, "1") + "</table>";
	}

	@Test
	public void readsEachRowByHeader()
	{
		List<SpawnLocation> l = LocationParser.parse(page(
			row("<a href=\"/w/Catacombs_of_Kourend\">Catacombs of Kourend</a>", "124", MEMBERS, "13")
				+ row("<a href=\"/w/Lumbridge\">Lumbridge</a>", "2", F2P, "4")));
		assertEquals(2, l.size());
		assertEquals("Catacombs of Kourend", l.get(0).getLocation());
		assertEquals("124", l.get(0).getLevels());
		assertTrue(l.get(0).getMembers());
		assertEquals("13", l.get(0).getSpawns());
		assertFalse(l.get(1).getMembers());
	}

	@Test
	public void keepsOnlyTheUkFloorAndTightAbbreviations()
	{
		String tower = "<a href=\"/w/Slayer_Tower\">Slayer Tower</a> (<span class=\"floornumber\"><span class=\"floornumber-gb\">2"
			+ "<sup class=\"floornumber-ordinal-suffix\">nd</sup>&#160;floor<sup class=\"floornumber-help noexcerpt\">&#91;"
			+ "<span class=\"fact-text floor-convention\" title=\"British\">UK</span>&#93;</sup></span>"
			+ "<span class=\"floornumber-us noexcerpt\">3<sup class=\"floornumber-ordinal-suffix\">rd</sup>&#160;floor"
			+ "<sup class=\"floornumber-help noexcerpt\">&#91;<span class=\"fact-text floor-convention\" title=\"US\">US</span>&#93;</sup></span></span>)";
		String abyss = "<a href=\"/w/Abyssal_Area\">Abyssal Area</a> (<abbr title=\"Other Realms\"><span class=\"fairycode\"><b>A</b><b>L</b><b>R</b></span></abbr>)";
		List<SpawnLocation> l = LocationParser.parse(page(row(tower, "124", MEMBERS, "14") + row(abyss, "124", MEMBERS, "13")));
		assertEquals("Slayer Tower (2nd floor)", l.get(0).getLocation());
		assertEquals("Abyssal Area (ALR)", l.get(1).getLocation());
	}

	@Test
	public void matchesAnyListedLevel()
	{
		SpawnLocation guild = LocationParser.parse(page(row("Warriors' Guild", "56, 76", MEMBERS, "12"))).get(0);
		assertTrue(guild.hasLevel(76));
		assertFalse(guild.hasLevel(106));
	}

	@Test
	public void singularHeadingAndMissingSectionAndNoHtml()
	{
		String boss = "<h2 id=\"Location\">Location</h2>" + HEADER + row("Ungael", "392, 732", MEMBERS, "1") + "</tbody></table>";
		assertEquals("Ungael", LocationParser.parse(boss).get(0).getLocation());
		assertTrue(LocationParser.parse("<h2 id=\"Drops\">Drops</h2><table></table>").isEmpty());
		assertNull(LocationParser.parse(null));
	}
}
