package com.bettermonsterexamine.loot;

import com.google.gson.Gson;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Exercises {@link ItemIdService}'s parse of the Bucket {@code item_id} response: {@code id} arrives as
 * a string array (take the first, parse to int), the map keys on the item page name, and the first row
 * wins for a repeated name. Mirrors the wire shape of the live API.
 */
public class ItemIdServiceTest
{
	private static final Gson GSON = new Gson();

	private static ItemIdService.BucketResponse response(String json)
	{
		return GSON.fromJson(json, ItemIdService.BucketResponse.class);
	}

	@Test
	public void indexesPageNameToFirstArrayId()
	{
		// Ancient shard (an untradeable ItemManager.search misses) and Coins.
		Map<String, Integer> map = ItemIdService.index(response(
			"{\"bucket\":[{\"page_name\":\"Ancient shard\",\"id\":[\"19677\"]},"
			+ "{\"page_name\":\"Coins\",\"id\":[\"995\"]}]}"));

		assertEquals(Integer.valueOf(19677), map.get("Ancient shard"));
		assertEquals(Integer.valueOf(995), map.get("Coins"));
	}

	@Test
	public void firstRowWinsForARepeatedName()
	{
		Map<String, Integer> map = ItemIdService.index(response(
			"{\"bucket\":[{\"page_name\":\"Dragon dagger\",\"id\":[\"1215\"]},"
			+ "{\"page_name\":\"Dragon dagger\",\"id\":[\"5680\"]}]}"));

		assertEquals(Integer.valueOf(1215), map.get("Dragon dagger"));
	}

	@Test
	public void skipsRowsWithNoUsableId()
	{
		Map<String, Integer> map = ItemIdService.index(response(
			"{\"bucket\":[{\"page_name\":\"NoId\"},"
			+ "{\"page_name\":\"EmptyId\",\"id\":[]},"
			+ "{\"page_name\":\"BadId\",\"id\":[\"nope\"]},"
			+ "{\"id\":[\"1\"]}]}"));

		assertTrue(map.isEmpty());
	}

	@Test
	public void nullOrBucketlessResponseYieldsEmptyMap()
	{
		assertTrue(ItemIdService.index(null).isEmpty());
		assertTrue(ItemIdService.index(response("{\"bucketQuery\":\"…\"}")).isEmpty());
	}

	@Test
	public void parseIdToleratesWhitespaceAndRejectsNonNumbers()
	{
		assertEquals(Integer.valueOf(383), ItemIdService.parseId(" 383 "));
		assertNull(ItemIdService.parseId(null));
		assertNull(ItemIdService.parseId("12a"));
	}
}
