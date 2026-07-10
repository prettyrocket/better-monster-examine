package com.bettermonsterexamine.loot;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Provides a monster's drop table on demand from the OSRS Wiki <b>Bucket</b> API
 * ({@code dropsline} bucket). Unlike {@link com.bettermonsterexamine.MonsterDataService}, which bulk-
 * loads the whole bestiary once, drops are fetched <b>per page</b> the first time a monster is looked
 * up (~50 rows / ~25 KB) and cached under {@code .runelite/better-monster-examine/drops/} so anything
 * already viewed works offline. The asymmetry is deliberate: stats are the searchable index (need the
 * whole thing local), drops are per-entity detail hung off an already-identified monster, ~10× the
 * size, and the API hard-caps queries at 5000 rows.
 *
 * <p>All loading happens off the client thread and EDT: {@link #request} dispatches to the background
 * executor, and the fetch runs synchronously <b>there</b> (never on the client thread or EDT),
 * paginating past the 5000-row cap. Loaded pages are published atomically into a concurrent index;
 * {@link #tableFor} reads it without blocking and returns null until a page lands. Register a
 * {@link #setUpdateListener listener} to be told (on a background thread) when one does.
 */
@Slf4j
@Singleton
public class DropTableService
{
	private static final String API_URL = "https://oldschool.runescape.wiki/api.php";
	private static final String USER_AGENT = "better-monster-examine (RuneLite plugin)";
	private static final File CACHE_DIR = new File(RuneLite.RUNELITE_DIR, "better-monster-examine/drops");
	private static final Duration MAX_AGE = Duration.ofDays(7);
	/** Bucket hard-caps every query at 5000 rows; we paginate with {@code offset} while a batch fills it. */
	private static final int ROW_LIMIT = 5000;

	private final Gson gson;
	private final OkHttpClient http;
	private final ScheduledExecutorService executor;

	/** lower-case page name -> that page's drop rows (all variants); an entry means "loaded". */
	private final Map<String, List<DropRow>> byPage = new ConcurrentHashMap<>();
	/** Pages with a fetch in flight, so concurrent requests coalesce into one network call. */
	private final Set<String> loading = ConcurrentHashMap.newKeySet();

	private volatile Consumer<String> updateListener;

	@Inject
	DropTableService(Gson gson, OkHttpClient http, ScheduledExecutorService executor)
	{
		this.gson = gson;
		this.http = http;
		this.executor = executor;
	}

	/** Set the callback fired (on a background thread) with a page name once its drops are published. */
	public void setUpdateListener(Consumer<String> listener)
	{
		this.updateListener = listener;
	}

	/**
	 * Ensure this page's drops are loaded, fetching them off-thread if needed. Safe to call from the
	 * client thread or EDT; returns immediately. A no-op once the page is loaded or already loading.
	 */
	public void request(String pageName)
	{
		if (pageName == null || pageName.isEmpty())
		{
			return;
		}
		String key = pageName.toLowerCase(Locale.ROOT);
		if (byPage.containsKey(key) || !loading.add(key))
		{
			return;
		}
		executor.execute(() -> load(pageName, key));
	}

	/**
	 * The drop tables for a page — one per variant, in first-seen order — or null when the page isn't
	 * loaded yet (call {@link #request} first and react via the update listener). A loaded page with no
	 * drops yields an empty list, never null.
	 */
	public List<DropTable> tableFor(String pageName)
	{
		List<DropRow> rows = byPage.get(pageName.toLowerCase(Locale.ROOT));
		return rows == null ? null : DropTable.group(rows);
	}

	/** True once this page's drops have been loaded (from cache or network), so reads return real data. */
	public boolean isLoaded(String pageName)
	{
		return byPage.containsKey(pageName.toLowerCase(Locale.ROOT));
	}

	/**
	 * Load one page: serve a fresh cache immediately, otherwise fetch. A stale cache still publishes
	 * first (so the page stays usable offline) before the refresh lands. Runs on the executor.
	 */
	private void load(String pageName, String key)
	{
		File cacheFile = cacheFileFor(key);
		boolean haveCache = false;
		if (cacheFile.isFile())
		{
			try (Reader r = Files.newBufferedReader(cacheFile.toPath(), StandardCharsets.UTF_8))
			{
				List<DropRow> rows = parseRows(gson.fromJson(r, BucketResponse.class));
				if (rows != null)
				{
					publish(key, rows, pageName);
					haveCache = true;
				}
			}
			catch (Exception e)
			{
				log.debug("Failed to read cached drops for {}", pageName, e);
			}
		}

		boolean fresh = haveCache && (System.currentTimeMillis() - cacheFile.lastModified()) < MAX_AGE.toMillis();
		if (fresh)
		{
			loading.remove(key);
		}
		else
		{
			fetch(pageName, key, cacheFile);
		}
	}

	/**
	 * Fetch every page of drops for {@code pageName} (paginating past the 5000-row cap) synchronously
	 * on the executor thread — never the client thread or EDT — then publish and cache the merged
	 * result. A failed request leaves any already-served stale cache in place.
	 */
	private void fetch(String pageName, String key, File cacheFile)
	{
		try
		{
			List<RawRow> all = new ArrayList<>();
			int offset = 0;
			while (true)
			{
				HttpUrl url = HttpUrl.get(API_URL).newBuilder()
					.addQueryParameter("action", "bucket")
					.addQueryParameter("format", "json")
					.addQueryParameter("query", buildQuery(pageName, offset))
					.build();
				Request req = new Request.Builder().url(url).header("User-Agent", USER_AGENT).build();
				int batch;
				try (Response res = http.newCall(req).execute())
				{
					if (!res.isSuccessful() || res.body() == null)
					{
						log.debug("Drops fetch for {} returned {}", pageName, res.code());
						return;
					}
					BucketResponse parsed = gson.fromJson(res.body().string(), BucketResponse.class);
					if (parsed == null || parsed.bucket == null)
					{
						log.debug("Drops response for {} carried no bucket", pageName);
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

			BucketResponse merged = new BucketResponse(all);
			List<DropRow> rows = parseRows(merged);
			if (rows == null)
			{
				return;
			}
			// Parse (and publish) before caching so a corrupt download never poisons the cache.
			publish(key, rows, pageName);
			writeCache(cacheFile, gson.toJson(merged));
			log.debug("Fetched and cached {} drop rows for {}", rows.size(), pageName);
		}
		catch (Exception e)
		{
			log.debug("Drops fetch/parse failed for {}", pageName, e);
		}
		finally
		{
			loading.remove(key);
		}
	}

	private void publish(String key, List<DropRow> rows, String pageName)
	{
		byPage.put(key, rows);
		Consumer<String> listener = updateListener;
		if (listener != null)
		{
			listener.accept(pageName);
		}
	}

	/** Build the paginated {@code where('page_name', X)} Bucket query over {@code dropsline}. */
	private static String buildQuery(String pageName, int offset)
	{
		return "bucket('dropsline').where('page_name','" + escape(pageName) + "')"
			+ ".select('item_name','page_name_sub','rare_drop_table','drop_json')"
			+ ".offset(" + offset + ").limit(" + ROW_LIMIT + ").run()";
	}

	/** Escape a page name for the single-quoted Bucket query string: apostrophes are doubled. */
	private static String escape(String value)
	{
		return value.replace("'", "''");
	}

	/**
	 * Turn a parsed Bucket response into drop rows: each row's {@code drop_json} blob is itself parsed
	 * into a {@link DropRow}, then the item-name fallback, variant key and rare-drop-table flag from the
	 * enclosing row are stamped on. Null when the response carried no {@code bucket}. Pure, so it's
	 * unit-testable.
	 */
	static List<DropRow> parseRows(BucketResponse response)
	{
		if (response == null || response.bucket == null)
		{
			return null;
		}
		Gson gson = new Gson();
		List<DropRow> out = new ArrayList<>(response.bucket.size());
		for (RawRow raw : response.bucket)
		{
			if (raw == null || raw.dropJson == null)
			{
				continue;
			}
			DropRow row;
			try
			{
				row = gson.fromJson(raw.dropJson, DropRow.class);
			}
			catch (JsonSyntaxException e)
			{
				continue;
			}
			if (row == null)
			{
				continue;
			}
			if (row.getItem() == null)
			{
				row.setItem(raw.itemName);
			}
			row.setPageNameSub(raw.pageNameSub);
			row.setRareDropTable(raw.rareDropTable);
			out.add(row);
		}
		return out;
	}

	private static File cacheFileFor(String key)
	{
		// Slugify for a safe filename, disambiguated with a hash so distinct names never collide.
		String slug = key.replaceAll("[^a-z0-9]+", "-");
		return new File(CACHE_DIR, slug + "-" + Integer.toHexString(key.hashCode()) + ".json");
	}

	private static void writeCache(File cacheFile, String json)
	{
		try
		{
			Files.createDirectories(CACHE_DIR.toPath());
			Files.write(cacheFile.toPath(), json.getBytes(StandardCharsets.UTF_8));
		}
		catch (IOException e)
		{
			log.debug("Failed to cache drops to {}", cacheFile, e);
		}
	}

	/** The wrapper the Bucket API returns: {@code {"bucketQuery": …, "bucket": [ rows ]}}. */
	static final class BucketResponse
	{
		private List<RawRow> bucket;

		BucketResponse()
		{
		}

		BucketResponse(List<RawRow> bucket)
		{
			this.bucket = bucket;
		}
	}

	/** One raw {@code dropsline} row: the nested {@code drop_json} string plus its meta fields and flag. */
	static final class RawRow
	{
		@SerializedName("item_name")
		private String itemName;
		@SerializedName("page_name_sub")
		private String pageNameSub;
		@SerializedName("rare_drop_table")
		private String rareDropTable;
		@SerializedName("drop_json")
		private String dropJson;
	}
}
