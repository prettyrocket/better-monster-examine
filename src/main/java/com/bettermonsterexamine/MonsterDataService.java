package com.bettermonsterexamine;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Provides the Weirdgloop monster dataset, indexed by NPC id and by name. The ~2.3 MB file
 * is fetched on demand from the OSRS Wiki DPS-calc CDN (the same source the calculator uses)
 * and cached under {@code .runelite/better-monster-examine/} so subsequent launches work
 * offline; it is refreshed in the background once the cache ages past {@link #MAX_AGE}. All
 * loading happens off the client thread, and accessors safely return empty until it lands.
 */
@Slf4j
@Singleton
public class MonsterDataService
{
	private static final String DATA_URL = "https://tools.runescape.wiki/osrs-dps/cdn/json/monsters.json";
	private static final File CACHE_DIR = new File(RuneLite.RUNELITE_DIR, "better-monster-examine");
	private static final File CACHE_FILE = new File(CACHE_DIR, "monsters.json");
	private static final Duration MAX_AGE = Duration.ofDays(7);
	private static final Type LIST_TYPE = new TypeToken<List<MonsterData>>()
	{
	}.getType();

	private final Gson gson;
	private final OkHttpClient http;

	private volatile Map<Integer, MonsterData> byId = Collections.emptyMap();
	// lower-case base name -> variants, insertion-ordered
	private volatile Map<String, List<MonsterData>> byName = Collections.emptyMap();

	@Inject
	MonsterDataService(Gson gson, OkHttpClient http, ScheduledExecutorService executor)
	{
		this.gson = gson;
		this.http = http;
		executor.execute(this::init);
	}

	private void init()
	{
		boolean haveCache = false;
		if (CACHE_FILE.isFile())
		{
			try (Reader r = Files.newBufferedReader(CACHE_FILE.toPath(), StandardCharsets.UTF_8))
			{
				List<MonsterData> cached = gson.fromJson(r, LIST_TYPE);
				// Only trust a cache that yielded entries; a truncated/empty file falls through
				// to a fetch rather than masquerading as a valid (and "fresh") dataset.
				if (cached != null && !cached.isEmpty())
				{
					index(cached);
					haveCache = true;
					log.info("Loaded monster dataset from cache ({} entries)", byId.size());
				}
			}
			catch (Exception e)
			{
				log.debug("Failed to read cached monster dataset", e);
			}
		}

		// Use the cache immediately if it's recent; otherwise (missing or stale) pull a fresh
		// copy. A stale cache still serves until the refresh lands, so we stay usable offline.
		boolean fresh = haveCache && (System.currentTimeMillis() - CACHE_FILE.lastModified()) < MAX_AGE.toMillis();
		if (fresh)
		{
			log.debug("Cache hit. Skipping monster data fetch.");
		}
		else
		{
			log.debug("{} Fetching monster data.", haveCache ? "Cache stale." : "Cache miss.");
			fetch();
		}
	}

	private void fetch()
	{
		Request req = new Request.Builder().url(DATA_URL).build();
		http.newCall(req).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Monster dataset fetch failed", e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response res = response)
				{
					if (!res.isSuccessful() || res.body() == null)
					{
						return;
					}
					String json = res.body().string();
					// Parse (and publish) before caching so a corrupt download never poisons the cache.
					index(gson.fromJson(json, LIST_TYPE));
					writeCache(json);
					log.info("Fetched and cached monster dataset ({} entries)", byId.size());
				}
				catch (Exception e)
				{
					log.debug("Monster dataset fetch/parse failed", e);
				}
			}
		});
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
			log.debug("Failed to cache monster dataset", e);
		}
	}

	/** Build the id/name indexes from a parsed dataset and publish them atomically. */
	private void index(List<MonsterData> all)
	{
		if (all == null)
		{
			return;
		}

		Map<Integer, MonsterData> id = new HashMap<>();
		Map<String, List<MonsterData>> name = new LinkedHashMap<>();
		for (MonsterData m : all)
		{
			if (m == null || m.getName() == null)
			{
				continue;
			}
			id.put(m.getId(), m);
			name.computeIfAbsent(m.getName().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(m);
		}

		// Publish atomically once fully built.
		this.byId = id;
		this.byName = name;
		log.debug("Indexed {} monster entries ({} unique names)", all.size(), name.size());
	}

	/** True once the dataset has been indexed, so accessors return real data (not async-empty). */
	public boolean isLoaded()
	{
		return !byName.isEmpty();
	}

	public MonsterData getById(int npcId)
	{
		return byId.get(npcId);
	}

	/**
	 * True when the dataset has this NPC — by id, or (for the many variant ids the dataset
	 * doesn't carry, e.g. Hellhounds across dungeons) by name.
	 */
	public boolean isKnownMonster(int npcId, String npcName)
	{
		return byId.containsKey(npcId)
			|| (npcName != null && byName.containsKey(npcName.toLowerCase(Locale.ROOT)));
	}

	/** All variants sharing the given (live NPC's) base name, in dataset order. */
	public List<MonsterData> variantsForId(int npcId)
	{
		MonsterData m = byId.get(npcId);
		if (m == null)
		{
			return Collections.emptyList();
		}
		return byName.getOrDefault(m.getName().toLowerCase(Locale.ROOT), Collections.singletonList(m));
	}

	public List<MonsterData> variantsForName(String name)
	{
		return byName.getOrDefault(name.toLowerCase(Locale.ROOT), Collections.emptyList());
	}

	/** Distinct base names matching the query, exact-match first, then alphabetical. */
	public List<String> searchNames(String query, int limit)
	{
		List<String> baseNames = byName.values().stream()
			.map(v -> v.get(0).getName())
			.collect(Collectors.toList());
		return matchNames(baseNames, query, limit);
	}

	/**
	 * Rank {@code names} against {@code query}: substring match (case-insensitive), an exact
	 * match floated to the top, the rest alphabetical, capped at {@code limit}. Pure helper so
	 * the ranking can be unit-tested without loading the dataset.
	 */
	static List<String> matchNames(Collection<String> names, String query, int limit)
	{
		String q = query.toLowerCase(Locale.ROOT).trim();
		return names.stream()
			.filter(n -> q.isEmpty() || n.toLowerCase(Locale.ROOT).contains(q))
			.distinct()
			.sorted((a, b) ->
			{
				boolean ea = a.equalsIgnoreCase(q);
				boolean eb = b.equalsIgnoreCase(q);
				if (ea != eb)
				{
					return ea ? -1 : 1;
				}
				return a.compareToIgnoreCase(b);
			})
			.limit(limit)
			.collect(Collectors.toList());
	}
}
