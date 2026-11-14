package com.bettermonsterexamine.loot;

import com.google.gson.Gson;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
	public void aDoseAnswersToItsInGameName()
	{
		// Vespula's drop table links "Xeric's aid (+)(4)"; the bucket keys it as a sub-entry.
		Map<String, Integer> map = ItemIdService.index(response(
			"{\"bucket\":[{\"page_name\":\"Xeric's aid (+)\",\"page_name_sub\":\"Xeric's aid (+)#(3)\",\"id\":[\"20983\"]},"
			+ "{\"page_name\":\"Xeric's aid (+)\",\"page_name_sub\":\"Xeric's aid (+)#(4)\",\"id\":[\"20984\"]}]}"));

		assertEquals(Integer.valueOf(20984), map.get("Xeric's aid (+)(4)"));
		assertEquals(Integer.valueOf(20983), map.get("Xeric's aid (+)(3)"));
		assertEquals(Integer.valueOf(20983), map.get("Xeric's aid (+)"));
	}

	@Test
	public void onlyDoseSubEntriesGetAName()
	{
		// "Iron dagger(p)" isn't how the wiki names that variant, so a non-dose sub-entry adds nothing.
		Map<String, Integer> map = ItemIdService.index(response(
			"{\"bucket\":[{\"page_name\":\"Iron dagger\",\"page_name_sub\":\"Iron dagger#(p)\",\"id\":[\"1219\"]},"
			+ "{\"page_name\":\"Bronze defender\",\"page_name_sub\":\"Bronze defender#Broken\",\"id\":[\"20449\"]}]}"));

		assertEquals(2, map.size());
	}

	@Test
	public void aPageNameWinsOverADoseName()
	{
		Map<String, Integer> map = ItemIdService.index(response(
			"{\"bucket\":[{\"page_name\":\"Potion\",\"page_name_sub\":\"Potion#(1)\",\"id\":[\"1\"]},"
			+ "{\"page_name\":\"Potion(1)\",\"page_name_sub\":\"Potion(1)\",\"id\":[\"2\"]}]}"));

		assertEquals(Integer.valueOf(2), map.get("Potion(1)"));
	}

	@Test
	public void aCacheWithoutSubEntriesIsNotCurrent()
	{
		assertTrue(ItemIdService.hasCurrentFields(response(
			"{\"bucket\":[{\"page_name\":\"Coins\",\"page_name_sub\":\"Coins\",\"id\":[\"995\"]}]}")));
		assertFalse(ItemIdService.hasCurrentFields(response(
			"{\"bucket\":[{\"page_name\":\"Coins\",\"id\":[\"995\"]}]}")));
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
