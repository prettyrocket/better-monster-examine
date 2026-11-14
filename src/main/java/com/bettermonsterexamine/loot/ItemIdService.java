package com.bettermonsterexamine.loot;

import com.bettermonsterexamine.wiki.BucketQuery;
import com.bettermonsterexamine.wiki.CacheStore;
import com.bettermonsterexamine.wiki.RefreshGate;
import com.bettermonsterexamine.wiki.WikiApi;
import com.bettermonsterexamine.wiki.WikiCache;
import com.bettermonsterexamine.wiki.WikiClient;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;

/**
 * Bridges wiki item names to RuneLite client item ids via the OSRS Wiki <b>Bucket</b> {@code item_id}
 * bucket ({@code page_name → id}). The drops layer needs it because the page parse yields item names
 * as wiki page titles, while the client's price/alch/icon lookups key on the numeric item id; once we
 * have the id, {@code ItemManager}/{@code ItemComposition} supply everything else with zero network.
 *
 * <p>Bulk-loaded once (like {@link com.bettermonsterexamine.MonsterDataService}) — the map changes
 * rarely, so it's fetched whole, cached as {@code item-ids.json} in the plugin data folder,
 * and refreshed in the background past {@link #MAX_AGE}, or sooner when a drop's item misses
 * ({@link #refreshOnMiss}). Loading happens off the client thread and EDT,
 * paginating past the 5000-row cap; accessors return null until it lands.
 */
@Slf4j
@Singleton
public class ItemIdService
{
	private static final Duration MAX_AGE = Duration.ofDays(7);
	private static final BucketQuery QUERY = new BucketQuery("item_id", "page_name", "page_name_sub", "id");
	/** A dose sub-entry, {@code "Xeric's aid (+)#(4)"}: the part after {@code #} is a dose count. */
	private static final Pattern DOSE_SUB = Pattern.compile("(.+)#(\\(\\d+\\))");

	private final Gson gson;
	private final WikiClient wiki;
	private WikiCache cache;
	private RefreshGate refreshGate;
	// Set once startup has run, loaded or not; see MonsterDataService.
	private volatile boolean initDone;
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
	}

	/** Start loading the map from {@code store}, then the wiki. Later calls do nothing. */
	public void start(CacheStore store)
	{
		if (cache != null)
		{
			return;
		}
		cache = new WikiCache(store, "item-ids.json", MAX_AGE);
		refreshGate = new RefreshGate(cache, WikiApi.SHARED_CACHE_AGE);
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

	/**
	 * A drop's item {@code key} resolved to no id, so the game may have items the cache predates:
	 * refresh it now rather than at its weekly age. Throttled by {@link RefreshGate}, so it is safe to
	 * call on every miss.
	 */
	public void refreshOnMiss(String key)
	{
		if (key != null && initDone && refreshGate.tryStart(key))
		{
			log.debug("Item lookup missed. Refreshing item-id map early.");
			executor.execute(this::fetchGated);
		}
	}

	private void init()
	{
		try
		{
			load();
		}
		finally
		{
			initDone = true;
		}
	}

	private void load()
	{
		boolean haveCache = false;
		boolean current = false;
		try
		{
			String text = cache.read();
			BucketResponse cached = text == null ? null : gson.fromJson(text, BucketResponse.class);
			if (cached != null && cached.bucket != null && !cached.bucket.isEmpty())
			{
				publish(index(cached));
				haveCache = true;
				current = hasCurrentFields(cached);
				log.info("Loaded item-id map from cache ({} entries)", byName.size());
			}
		}
		catch (Exception e)
		{
			log.debug("Failed to read cached item-id map", e);
		}

		// A cache written before page_name_sub was selected has no dose names, whatever its age.
		if (!haveCache || !current || !cache.isFresh())
		{
			log.debug("{} Fetching item-id map.", haveCache ? "Cache stale." : "Cache miss.");
			refreshGate.start();
			fetchGated();
		}
	}

	private void fetchGated()
	{
		boolean landed = false;
		try
		{
			landed = fetch();
		}
		finally
		{
			refreshGate.finish(landed);
		}
	}

	/**
	 * Fetch the whole {@code item_id} bucket (paginating past the 5000-row cap) synchronously on the
	 * executor thread — never the client thread or EDT — then publish and cache the merged result.
	 * True when a new map was published.
	 */
	private boolean fetch()
	{
		try
		{
			JsonObject merged = new JsonObject();
			merged.add("bucket", QUERY.fetchAll(wiki));
			String json = gson.toJson(merged);
			Map<String, Integer> map = index(gson.fromJson(json, BucketResponse.class));
			if (map.isEmpty())
			{
				return false;
			}
			// Parse (and publish) before caching so a corrupt download never poisons the cache.
			publish(map);
			cache.write(json);
			log.info("Fetched and cached item-id map ({} entries)", map.size());
			return true;
		}
		catch (Exception e)
		{
			log.debug("Item-id fetch/parse failed", e);
			return false;
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
	 *
	 * <p>Each dose also answers to its in-game name. A drop table links a dose as
	 * {@code "Xeric's aid (+)(4)"}, a redirect, while the bucket keys it by page
	 * {@code "Xeric's aid (+)"} with sub-entry {@code "Xeric's aid (+)#(4)"}. Untradeable doses (the
	 * Chambers of Xeric potions) are missing from the client's own search, so without this key they
	 * get no id and no icon. Page names win over these keys.
	 */
	static Map<String, Integer> index(BucketResponse response)
	{
		Map<String, Integer> map = new HashMap<>();
		if (response == null || response.bucket == null)
		{
			return map;
		}
		Map<String, Integer> doses = new HashMap<>();
		for (Row r : response.bucket)
		{
			if (r == null || r.pageName == null || r.id == null || r.id.isEmpty())
			{
				continue;
			}
			Integer id = parseId(r.id.get(0));
			if (id == null)
			{
				continue;
			}
			map.putIfAbsent(r.pageName, id);
			Matcher dose = r.pageNameSub == null ? null : DOSE_SUB.matcher(r.pageNameSub);
			if (dose != null && dose.matches())
			{
				doses.putIfAbsent(dose.group(1) + dose.group(2), id);
			}
		}
		doses.forEach(map::putIfAbsent);
		return map;
	}

	/**
	 * True when a cached response carries the fields this build selects. Every row has a
	 * {@code page_name_sub}, so its total absence means the cache predates selecting it.
	 */
	static boolean hasCurrentFields(BucketResponse response)
	{
		return response != null && response.bucket != null
			&& response.bucket.stream().anyMatch(r -> r != null && r.pageNameSub != null);
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
		@SerializedName("page_name_sub")
		private String pageNameSub;
		@SerializedName("id")
		private List<String> id;
	}
}
