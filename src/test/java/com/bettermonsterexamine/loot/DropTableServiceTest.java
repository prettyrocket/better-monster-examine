package com.bettermonsterexamine.loot;

import com.google.gson.Gson;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Exercises the two-stage parse {@link DropTableService#parseRows} does over a Bucket
 * {@code dropsline} response: each row's nested {@code drop_json} string is parsed into a
 * {@link DropRow}, and the enclosing row's {@code page_name_sub} and {@code rare_drop_table} sentinel
 * are stamped on. The wire shape mirrors the live API (drop_json is a JSON string, not an object).
 */
public class DropTableServiceTest
{
	private static final Gson GSON = new Gson();

	private static DropTableService.BucketResponse response(String json)
	{
		return GSON.fromJson(json, DropTableService.BucketResponse.class);
	}

	/** A real-shaped two-row response: an always drop, and a fractional rare-drop-table drop. */
	private static final String TWO_ROWS =
		"{\"bucket\":[{"
		+ "\"page_name_sub\":\"Vorkath#Post-quest\","
		+ "\"drop_json\":\"{\\\"Rarity\\\":\\\"Always\\\",\\\"Drop Quantity\\\":\\\"2\\\","
		+ "\\\"Quantity Low\\\":2,\\\"Quantity High\\\":2,\\\"Rolls\\\":1,\\\"Drop type\\\":\\\"combat\\\","
		+ "\\\"Dropped item\\\":\\\"Superior dragon bones\\\"}\""
		+ "},{"
		+ "\"page_name_sub\":\"Vorkath#Post-quest\",\"rare_drop_table\":\"\","
		+ "\"drop_json\":\"{\\\"Rarity\\\":\\\"5/150\\\",\\\"Drop Quantity\\\":\\\"2–3\\\","
		+ "\\\"Quantity Low\\\":2,\\\"Quantity High\\\":3,\\\"Rolls\\\":2,\\\"Drop type\\\":\\\"combat\\\","
		+ "\\\"Dropped item\\\":\\\"Rune longsword\\\"}\""
		+ "}]}";

	@Test
	public void parsesNestedDropJsonAndStampsRowFields()
	{
		List<DropRow> rows = DropTableService.parseRows(response(TWO_ROWS));

		assertEquals(2, rows.size());

		DropRow always = rows.get(0);
		assertEquals("Superior dragon bones", always.getItem());
		assertEquals("Vorkath#Post-quest", always.getPageNameSub());
		assertTrue(always.isAlways());
		// rare_drop_table absent on this row -> flag off.
		assertFalse(always.isRareDropTable());

		DropRow rdt = rows.get(1);
		assertEquals("Rune longsword", rdt.getItem());
		assertEquals(5.0 / 150.0, rdt.getRarityFraction(), 1e-9);
		assertEquals(3, rdt.getQuantityHigh());
		// rare_drop_table present as the empty-string sentinel -> flag on.
		assertTrue(rdt.isRareDropTable());
	}

	@Test
	public void skipsRowsWithMissingOrMalformedDropJson()
	{
		String json = "{\"bucket\":[{\"page_name_sub\":\"A#x\"},"
			+ "{\"page_name_sub\":\"A#x\",\"drop_json\":\"not json\"},"
			+ "{\"page_name_sub\":\"A#x\",\"drop_json\":\"{\\\"Dropped item\\\":\\\"Bones\\\"}\"}]}";

		List<DropRow> rows = DropTableService.parseRows(response(json));

		assertEquals(1, rows.size());
		assertEquals("Bones", rows.get(0).getItem());
	}

	@Test
	public void nullOrBucketlessResponseYieldsNull()
	{
		assertNull(DropTableService.parseRows(null));
		assertNull(DropTableService.parseRows(response("{\"bucketQuery\":\"…\"}")));
	}

	@Test
	public void fallsBackToItemNameAndReadsVariantFromDropJson()
	{
		// A row whose drop_json has no "Dropped item" falls back to the enclosing row's item_name;
		// the variant is read from the drop_json "Dropped from" field.
		String json = "{\"bucket\":[{"
			+ "\"item_name\":\"Coins\",\"page_name_sub\":\"Goblin\","
			+ "\"drop_json\":\"{\\\"Rarity\\\":\\\"1/2\\\",\\\"Dropped from\\\":\\\"Goblin\\\"}\""
			+ "}]}";

		List<DropRow> rows = DropTableService.parseRows(response(json));

		assertEquals(1, rows.size());
		assertEquals("Coins", rows.get(0).getItem());
		assertEquals("", rows.get(0).variant());
	}
}
