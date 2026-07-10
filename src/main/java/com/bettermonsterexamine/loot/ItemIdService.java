package com.bettermonsterexamine.loot;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Bridges wiki item names to RuneLite client item ids via the OSRS Wiki <b>Bucket</b> {@code item_id}
 * bucket ({@code page_name → id}). The drops layer needs it because {@code dropsline} names its items
 * by wiki page title, while the client's price/alch/icon lookups key on the numeric item id; once we
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
	private static final String API_URL = "https://oldschool.runescape.wiki/api.php";
	private static final String USER_AGENT = "better-monster-examine (RuneLite plugin)";
	private static final File CACHE_DIR = new File(RuneLite.RUNELITE_DIR, "better-monster-examine");
	private static final File CACHE_FILE = new File(CACHE_DIR, "item-ids.json");
	private static final Duration MAX_AGE = Duration.ofDays(7);
	/** Bucket hard-caps every query at 5000 rows; we paginate with {@code offset} while a batch fills it. */
	private static final int ROW_LIMIT = 5000;

	private final Gson gson;
	private final OkHttpClient http;
	private final ScheduledExecutorService executor;

	/** wiki item page name -> client item id; published atomically once the bulk load lands. */
	private volatile Map<String, Integer> byName = Collections.emptyMap();
	private volatile Runnable updateListener;

	@Inject
	ItemIdService(Gson gson, OkHttpClient http, ScheduledExecutorService executor)
	{
		this.gson = gson;
		this.http = http;
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
		if (CACHE_FILE.isFile())
		{
			try (Reader r = Files.newBufferedReader(CACHE_FILE.toPath(), StandardCharsets.UTF_8))
			{
				BucketResponse cached = gson.fromJson(r, BucketResponse.class);
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
		}

		boolean fresh = haveCache && (System.currentTimeMillis() - CACHE_FILE.lastModified()) < MAX_AGE.toMillis();
		if (!fresh)
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
			List<Row> all = new ArrayList<>();
			int offset = 0;
			while (true)
			{
				HttpUrl url = HttpUrl.get(API_URL).newBuilder()
					.addQueryParameter("action", "bucket")
					.addQueryParameter("format", "json")
					.addQueryParameter("query", buildQuery(offset))
					.build();
				Request req = new Request.Builder().url(url).header("User-Agent", USER_AGENT).build();
				int batch;
				try (Response res = http.newCall(req).execute())
				{
					if (!res.isSuccessful() || res.body() == null)
					{
						log.debug("Item-id fetch returned {}", res.code());
						return;
					}
					BucketResponse parsed = gson.fromJson(res.body().string(), BucketResponse.class);
					if (parsed == null || parsed.bucket == null)
					{
						log.debug("Item-id response carried no bucket");
						return;
					}
					batch = parsed.bucket.size();
					all.addAll(parsed.bucket);
				}
				if (batch < ROW_LIMIT)
				{
					break;
				}
				offset += ROW_LIMIT;
			}

			Map<String, Integer> map = index(new BucketResponse(all));
			if (map.isEmpty())
			{
				return;
			}
			// Parse (and publish) before caching so a corrupt download never poisons the cache.
			publish(map);
			writeCache(gson.toJson(new BucketResponse(all)));
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

	/** Build the paginated bulk {@code select('page_name','id')} Bucket query over {@code item_id}. */
	private static String buildQuery(int offset)
	{
		return "bucket('item_id').select('page_name','id')"
			+ ".offset(" + offset + ").limit(" + ROW_LIMIT + ").run()";
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

	private static void writeCache(String json)
	{
		try
		{
			Files.createDirectories(CACHE_DIR.toPath());
			Files.write(CACHE_FILE.toPath(), json.getBytes(StandardCharsets.UTF_8));
		}
		catch (IOException e)
		{
			log.debug("Failed to cache item-id map", e);
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
