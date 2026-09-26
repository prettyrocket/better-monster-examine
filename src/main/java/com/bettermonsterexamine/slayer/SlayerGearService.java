package com.bettermonsterexamine.slayer;

import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
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
 * Recommended gear for a monster, from the wiki's {@code recommended_equipment} Bucket. Coverage is
 * thin — only a monster's {@code /Strategies} guide or its Slayer task page carries setups, about 85
 * pages in all — so rather than bulk-load 2.7 MB for them, each monster asks for its own candidate
 * pages in <b>one</b> query ({@link GearParser#candidatePages}) the first time it's viewed, cached per
 * monster under {@code .runelite/better-monster-examine/slayergear/} and refreshed weekly: the same
 * on-demand pattern as the drop pages.
 */
@Slf4j
@Singleton
public class SlayerGearService
{
	private static final String API_URL = "https://oldschool.runescape.wiki/api.php";
	private static final String USER_AGENT = "better-monster-examine (RuneLite plugin)";
	private static final File CACHE_DIR = new File(RuneLite.RUNELITE_DIR, "better-monster-examine/slayergear");
	private static final Duration MAX_AGE = Duration.ofDays(7);

	private final Gson gson;
	private final OkHttpClient http;
	private final ScheduledExecutorService executor;

	/** lower-case monster page -> its setups, best source first; an entry means "loaded". */
	private final Map<String, List<GearSetup>> byPage = new ConcurrentHashMap<>();
	private final Set<String> loading = ConcurrentHashMap.newKeySet();

	private volatile Consumer<String> updateListener;

	@Inject
	SlayerGearService(Gson gson, OkHttpClient http, ScheduledExecutorService executor)
	{
		this.gson = gson;
		this.http = http;
		this.executor = executor;
	}

	/** Set the callback fired (on a background thread) with a page name once its gear is published. */
	public void setUpdateListener(Consumer<String> listener)
	{
		this.updateListener = listener;
	}

	/** Ensure this monster's gear is loaded, fetching off-thread if needed. Returns immediately. */
	public void request(String pageName, List<String> categories)
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
		List<String> pages = GearParser.candidatePages(pageName, categories);
		executor.execute(() -> load(pageName, key, pages));
	}

	/** The monster's setups, best source first; empty when the wiki has none, null until loaded. */
	public List<GearSetup> setupsFor(String pageName)
	{
		return byPage.get(pageName.toLowerCase(Locale.ROOT));
	}

	private void load(String pageName, String key, List<String> pages)
	{
		File cacheFile = cacheFileFor(key);
		boolean haveCache = false;
		if (cacheFile.isFile())
		{
			try
			{
				String json = new String(Files.readAllBytes(cacheFile.toPath()), StandardCharsets.UTF_8);
				List<GearSetup> setups = GearParser.parse(gson, json, pages);
				if (setups != null)
				{
					publish(key, setups, pageName);
					haveCache = true;
				}
			}
			catch (Exception e)
			{
				log.debug("Failed to read cached gear for {}", pageName, e);
			}
		}

		boolean fresh = haveCache && (System.currentTimeMillis() - cacheFile.lastModified()) < MAX_AGE.toMillis();
		if (fresh)
		{
			loading.remove(key);
		}
		else
		{
			fetch(pageName, key, pages, cacheFile);
		}
	}

	private void fetch(String pageName, String key, List<String> pages, File cacheFile)
	{
		try
		{
			HttpUrl url = HttpUrl.get(API_URL).newBuilder()
				.addQueryParameter("action", "bucket")
				.addQueryParameter("format", "json")
				.addQueryParameter("query", buildQuery(pages))
				.build();
			Request req = new Request.Builder().url(url).header("User-Agent", USER_AGENT).build();
			try (Response res = http.newCall(req).execute())
			{
				if (!res.isSuccessful() || res.body() == null)
				{
					log.debug("Gear fetch for {} returned {}", pageName, res.code());
					return;
				}
				String json = res.body().string();
				List<GearSetup> setups = GearParser.parse(gson, json, pages);
				if (setups == null)
				{
					log.debug("Gear response for {} was unreadable", pageName);
					return;
				}
				publish(key, setups, pageName);
				writeCache(cacheFile, json);
			}
		}
		catch (Exception e)
		{
			log.debug("Gear fetch failed for {}", pageName, e);
		}
		finally
		{
			loading.remove(key);
		}
	}

	/** One query over every candidate page — Bucket matches {@code page_name} case-insensitively. */
	static String buildQuery(List<String> pages)
	{
		StringBuilder or = new StringBuilder();
		for (String p : pages)
		{
			or.append(or.length() > 0 ? "," : "").append("{'page_name','").append(lua(p)).append("'}");
		}
		return "bucket('recommended_equipment').select('page_name','json')"
			+ ".where(bucket.Or(" + or + ")).limit(500).run()";
	}

	private static String lua(String s)
	{
		return s.replace("\\", "\\\\").replace("'", "\\'");
	}

	private void publish(String key, List<GearSetup> setups, String pageName)
	{
		byPage.put(key, setups);
		Consumer<String> listener = updateListener;
		if (listener != null)
		{
			listener.accept(pageName);
		}
	}

	private static File cacheFileFor(String key)
	{
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
			log.debug("Failed to cache gear to {}", cacheFile, e);
		}
	}
}
