package com.bettermonsterexamine;

import com.google.gson.Gson;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class LookupHistoryTest
{
	private static final Gson GSON = new Gson();

	@Test
	public void recordMovesExistingToFrontWithoutDuplicating()
	{
		LookupHistory h = new LookupHistory();
		h.record("Goblin", "");
		h.record("Cow", "");
		h.record("Goblin", "");

		List<LookupHistory.Entry> recent = h.recent();
		assertEquals(2, recent.size());
		assertEquals("Goblin", recent.get(0).name);
		assertEquals("Cow", recent.get(1).name);
	}

	@Test
	public void recordDistinguishesVariantsByVersion()
	{
		LookupHistory h = new LookupHistory();
		h.record("Vorkath", "Post-Quest");
		h.record("Vorkath", "Dragon Slayer II");
		assertEquals(2, h.recent().size());
	}

	@Test
	public void identityIsCaseInsensitive()
	{
		LookupHistory h = new LookupHistory();
		h.record("Goblin", "");
		h.record("goblin", "");
		assertEquals(1, h.recent().size());
		assertTrue(h.isFavorite("ABYSSAL DEMON", "") == false);
	}

	@Test
	public void recentIsCappedNewestFirst()
	{
		LookupHistory h = new LookupHistory();
		for (int i = 0; i < LookupHistory.RECENT_CAP + 5; i++)
		{
			h.record("Monster " + i, "");
		}
		List<LookupHistory.Entry> recent = h.recent();
		assertEquals(LookupHistory.RECENT_CAP, recent.size());
		// Newest first: the last one recorded sits at the head.
		assertEquals("Monster " + (LookupHistory.RECENT_CAP + 4), recent.get(0).name);
		// The five oldest fell off the end.
		assertFalse(h.recent().stream().anyMatch(e -> e.name.equals("Monster 0")));
	}

	@Test
	public void favoriteToggleAndNewestPinnedFirst()
	{
		LookupHistory h = new LookupHistory();
		assertTrue(h.toggleFavorite("Cow", ""));
		assertTrue(h.toggleFavorite("Goblin", ""));
		assertTrue(h.isFavorite("Goblin", ""));

		List<LookupHistory.Entry> favs = h.favorites();
		assertEquals("Goblin", favs.get(0).name);
		assertEquals("Cow", favs.get(1).name);

		// Same call un-favorites.
		assertFalse(h.toggleFavorite("Goblin", ""));
		assertFalse(h.isFavorite("Goblin", ""));
		assertEquals(1, h.favorites().size());
	}

	@Test
	public void favoritesAreUncapped()
	{
		LookupHistory h = new LookupHistory();
		for (int i = 0; i < LookupHistory.RECENT_CAP + 10; i++)
		{
			h.toggleFavorite("Monster " + i, "");
		}
		assertEquals(LookupHistory.RECENT_CAP + 10, h.favorites().size());
	}

	@Test
	public void listsAreIndependentAndClearSeparately()
	{
		LookupHistory h = new LookupHistory();
		h.record("Goblin", "");
		h.toggleFavorite("Goblin", "");

		h.clearRecent();
		assertTrue(h.recent().isEmpty());
		assertTrue(h.isFavorite("Goblin", ""));

		h.record("Cow", "");
		h.clearFavorites();
		assertFalse(h.isFavorite("Goblin", ""));
		assertEquals(1, h.recent().size());
	}

	@Test
	public void removeAllByNameDropsFromBothLists()
	{
		LookupHistory h = new LookupHistory();
		h.record("Old Monster", "A");
		h.record("Old Monster", "B");
		h.toggleFavorite("Old Monster", "A");
		h.record("Cow", "");

		assertTrue(h.removeAllByName("old monster"));
		assertEquals(1, h.recent().size());
		assertEquals("Cow", h.recent().get(0).name);
		assertTrue(h.favorites().isEmpty());
		assertFalse(h.removeAllByName("Nonexistent"));
	}

	@Test
	public void jsonRoundTripPreservesOrderAndState()
	{
		LookupHistory h = new LookupHistory();
		h.record("Cow", "");
		h.record("Vorkath", "Post-Quest");
		h.toggleFavorite("Goblin", "");

		LookupHistory restored = LookupHistory.fromJson(GSON, h.toJson(GSON));
		assertEquals("Vorkath", restored.recent().get(0).name);
		assertEquals("Post-Quest", restored.recent().get(0).version);
		assertEquals("Cow", restored.recent().get(1).name);
		assertTrue(restored.isFavorite("Goblin", ""));
	}

	@Test
	public void garbageJsonDegradesToEmpty()
	{
		assertTrue(LookupHistory.fromJson(GSON, "not json at all {{{").recent().isEmpty());
		assertTrue(LookupHistory.fromJson(GSON, "").favorites().isEmpty());
		assertTrue(LookupHistory.fromJson(GSON, null).recent().isEmpty());
		// Valid JSON of the wrong shape parses to empty lists rather than throwing.
		assertTrue(LookupHistory.fromJson(GSON, "[1,2,3]").recent().isEmpty());
	}

	@Test
	public void fromJsonReappliesCapAndDedup()
	{
		StringBuilder sb = new StringBuilder("{\"recent\":[");
		for (int i = 0; i < LookupHistory.RECENT_CAP + 5; i++)
		{
			if (i > 0)
			{
				sb.append(',');
			}
			sb.append("{\"name\":\"M").append(i).append("\",\"version\":\"\"}");
		}
		// A duplicate of M0 and a blank-name entry that should both be filtered.
		sb.append(",{\"name\":\"M0\",\"version\":\"\"},{\"name\":\"\",\"version\":\"\"}]}");

		LookupHistory h = LookupHistory.fromJson(GSON, sb.toString());
		assertEquals(LookupHistory.RECENT_CAP, h.recent().size());
	}
}
