package com.bettermonsterexamine.loot;

import com.bettermonsterexamine.wiki.BucketQuery;
import com.bettermonsterexamine.wiki.WikiApi;
import com.bettermonsterexamine.wiki.WikiCache;
import com.bettermonsterexamine.wiki.WikiClient;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import java.io.File;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import okhttp3.OkHttpClient;

/**
 * Bridges wiki item names to RuneLite client item ids via the OSRS Wiki <b>Bucket</b> {@code item_id}
 * bucket ({@code page_name → id}). The drops layer needs it because the page parse yields item names
 * as wiki page titles, while the client's price/alch/icon lookups key on the numeric item id; once we
 * have the id, {@code ItemManager}/{@code ItemComposition} supply everything else with zero network.
 *
 * <p>Bulk-loaded once (like {@link com.bettermonsterexamine.MonsterDataService}) — the map changes
 * rarely, so it's fetched whole, cached under {@code .runelite/better-monster-examine/item-ids.json},
 * and refreshed in the background past {@link #MAX_AGE}. Loading happens off the client thread and EDT,
 * paginating past the 5000-row cap; accessors return null until it lands.
 */
@Slf4j
@Singleton
public class ItemIdService
{
	private static final Duration MAX_AGE = Duration.ofDays(7);
	private static final BucketQuery QUERY = new BucketQuery("item_id", "page_name", "id");

	private final Gson gson;
	private final WikiClient wiki;
	private final WikiCache cache = new WikiCache(
		new File(new File(RuneLite.RUNELITE_DIR, "better-monster-examine"), "item-ids.json"), MAX_AGE);
	private final ScheduledExecutorService executor;

	/** wiki item page name -> client item id; published atomically once the bulk load lands. */
	private volatile Map<String, Integer> byName = Collections.emptyMap();
	private volatile Runnable updateListener;

	@Inject
	ItemIdService(Gson gson, OkHttpClient http, ScheduledExecutorService executor)
	{
		this.gson = gson;
		this.wiki = WikiApi.client(http, gson);
		this.executor = executor;
		executor.execute(this::init);
	}

	/** Set the callback fired (on a background thread) once the id map has been published. */
	public void setUpdateListener(Runnable listener)
	{
		this.updateListener = listener;
	}

	/** True once the id map has been loaded (from cache or network), so lookups return real ids. */
	public boolean isLoaded()
	{
		return !byName.isEmpty();
	}

	/** The client item id for a wiki item page name, or null when unknown (or not loaded yet). */
	public Integer idFor(String itemName)
	{
		return itemName == null ? null : byName.get(itemName);
	}

	private void init()
	{
		boolean haveCache = false;
		try
		{
			String text = cache.read();
			BucketResponse cached = text == null ? null : gson.fromJson(text, BucketResponse.class);
			if (cached != null && cached.bucket != null && !cached.bucket.isEmpty())
			{
				publish(index(cached));
				haveCache = true;
				log.info("Loaded item-id map from cache ({} entries)", byName.size());
			}
		}
		catch (Exception e)
		{
			log.debug("Failed to read cached item-id map", e);
		}

		if (!haveCache || !cache.isFresh())
		{
			log.debug("{} Fetching item-id map.", haveCache ? "Cache stale." : "Cache miss.");
			fetch();
		}
	}

	/**
	 * Fetch the whole {@code item_id} bucket (paginating past the 5000-row cap) synchronously on the
	 * executor thread — never the client thread or EDT — then publish and cache the merged result.
	 */
	private void fetch()
	{
		try
		{
			JsonObject merged = new JsonObject();
			merged.add("bucket", QUERY.fetchAll(wiki));
			String json = gson.toJson(merged);
			Map<String, Integer> map = index(gson.fromJson(json, BucketResponse.class));
			if (map.isEmpty())
			{
				return;
			}
			// Parse (and publish) before caching so a corrupt download never poisons the cache.
			publish(map);
			cache.write(json);
			log.info("Fetched and cached item-id map ({} entries)", map.size());
		}
		catch (Exception e)
		{
			log.debug("Item-id fetch/parse failed", e);
		}
	}

	private void publish(Map<String, Integer> map)
	{
		this.byName = map;
		Runnable listener = updateListener;
		if (listener != null)
		{
			listener.run();
		}
	}

	/**
	 * Build the name → id map from a parsed Bucket response (first row wins for a repeated name). Pure
	 * over its argument, so the {@code id}-array shape stays unit-testable. Empty when the response
	 * carried no {@code bucket}.
	 */
	static Map<String, Integer> index(BucketResponse response)
	{
		Map<String, Integer> map = new HashMap<>();
		if (response == null || response.bucket == null)
		{
			return map;
		}
		for (Row r : response.bucket)
		{
			if (r == null || r.pageName == null || r.id == null || r.id.isEmpty())
			{
				continue;
			}
			Integer id = parseId(r.id.get(0));
			if (id != null)
			{
				map.putIfAbsent(r.pageName, id);
			}
		}
		return map;
	}

	/** Parse a Bucket id string (repeated fields arrive as string arrays, e.g. {@code ["383"]}). */
	static Integer parseId(String s)
	{
		if (s == null)
		{
			return null;
		}
		try
		{
			return Integer.parseInt(s.trim());
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	/** The wrapper the Bucket API returns: {@code {"bucketQuery": …, "bucket": [ rows ]}}. */
	static final class BucketResponse
	{
		private List<Row> bucket;

		BucketResponse()
		{
		}

		BucketResponse(List<Row> bucket)
		{
			this.bucket = bucket;
		}
	}

	/** One {@code item_id} row: the item page name and its id (a Bucket repeated field → string array). */
	static final class Row
	{
		@SerializedName("page_name")
		private String pageName;
		@SerializedName("id")
		private List<String> id;
	}
}
