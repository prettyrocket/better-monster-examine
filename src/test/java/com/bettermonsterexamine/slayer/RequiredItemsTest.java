package com.bettermonsterexamine.slayer;

import com.google.gson.Gson;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import org.junit.Test;

/** The bundled required-items table loads, and is keyed by monster name rather than category. */
public class RequiredItemsTest
{
	private final RequiredItems items = new RequiredItems(new Gson());

	@Test
	public void bundledTableLoads()
	{
		List<RequiredItems.Requirement> table = RequiredItems.read(new Gson());
		assertFalse(table.isEmpty());
	}

	@Test
	public void matchesByNameIgnoringCase()
	{
		assertEquals("Rock hammer", items.forMonster("gargoyle").getItems().get(0));
		assertEquals("Nose peg", items.forMonster("Aberrant spectre").getItems().get(0));
		assertEquals("In the Karuulm Slayer Dungeon", items.forMonster("Wyrm").getNote());
	}

	@Test
	public void categoryMatesThatNeedNothingAreLeftOut()
	{
		// Dawn shares Dusk's Gargoyles category, but only Dusk needs the hammer.
		assertNull(items.forMonster("Dawn"));
		assertNull(items.forMonster("Mountain troll"));
		assertNull(items.forMonster(null));
	}
}
