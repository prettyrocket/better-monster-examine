package com.bettermonsterexamine;

import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Provides the monster dataset, indexed by NPC id and by name. The whole bestiary is fetched in a
 * single uncapped query from the OSRS Wiki <b>Bucket</b> API ({@code infobox_monster}) — the wiki's
 * official structured-data store — and cached under {@code .runelite/better-monster-examine/} so
 * subsequent launches work offline; it refreshes in the background once the cache ages past
 * {@link #MAX_AGE}. All loading happens off the client thread, and accessors safely return empty
 * until it lands.
 */
@Slf4j
@Singleton
public class MonsterDataService
{
	private static final String API_URL = "https://oldschool.runescape.wiki/api.php";
	private static final String USER_AGENT = "better-monster-examine (RuneLite plugin)";
	private static final File CACHE_DIR = new File(RuneLite.RUNELITE_DIR, "better-monster-examine");
	private static final File CACHE_FILE = new File(CACHE_DIR, "bucket-monsters.json");
	private static final Duration MAX_AGE = Duration.ofDays(7);

	/** Bucket fields fetched for every monster — the rendered set plus extras captured for later use. */
	private static final String[] FIELDS = {
		"name", "id", "version_anchor", "default_version", "combat_level", "hitpoints", "size",
		"attack_level", "strength_level", "defence_level", "magic_level", "ranged_level",
		"attack_bonus", "strength_bonus", "magic_attack_bonus", "magic_damage_bonus",
		"range_attack_bonus", "range_strength_bonus",
		"stab_attack_bonus", "slash_attack_bonus", "crush_attack_bonus",
		"stab_defence_bonus", "slash_defence_bonus", "crush_defence_bonus", "magic_defence_bonus",
		"range_defence_bonus", "light_range_defence_bonus", "standard_range_defence_bonus",
		"heavy_range_defence_bonus", "flat_armour",
		"attack_style", "attack_speed", "max_hit", "experience_bonus",
		"attribute", "elemental_weakness", "elemental_weakness_percent",
		"examine", "poisonous",
		"cannon_immune", "thrall_immune", "burn_immune", "freeze_resistance",
		"slayer_level", "slayer_experience", "slayer_category", "assigned_by", "uses_skill",
		"image", "league_region", "release_date", "is_members_only",
	};

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

	/** The wrapper the Bucket API returns: {@code {"bucketQuery": …, "bucket": [ rows ]}}. */
	private static final class BucketResponse
	{
		private List<MonsterData> bucket;
	}

	private void init()
	{
		boolean haveCache = false;
		if (CACHE_FILE.isFile())
		{
			try (Reader r = Files.newBufferedReader(CACHE_FILE.toPath(), StandardCharsets.UTF_8))
			{
				BucketResponse cached = gson.fromJson(r, BucketResponse.class);
				// Only trust a cache that yielded rows; a truncated/empty/old-format file falls
				// through to a fetch rather than masquerading as a valid (and "fresh") dataset.
				if (cached != null && cached.bucket != null && !cached.bucket.isEmpty())
				{
					index(cached.bucket);
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
		HttpUrl url = HttpUrl.get(API_URL).newBuilder()
			.addQueryParameter("action", "bucket")
			.addQueryParameter("format", "json")
			.addQueryParameter("query", buildQuery())
			.build();
		Request req = new Request.Builder().url(url).header("User-Agent", USER_AGENT).build();
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
					BucketResponse parsed = gson.fromJson(json, BucketResponse.class);
					if (parsed == null || parsed.bucket == null || parsed.bucket.isEmpty())
					{
						log.debug("Monster dataset response carried no rows");
						return;
					}
					// Parse (and publish) before caching so a corrupt download never poisons the cache.
					index(parsed.bucket);
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

	/** Build the uncapped {@code select(…).run()} Bucket query over {@code infobox_monster}. */
	private static String buildQuery()
	{
		StringBuilder sel = new StringBuilder();
		for (String f : FIELDS)
		{
			if (sel.length() > 0)
			{
				sel.append(',');
			}
			sel.append('\'').append(f).append('\'');
		}
		return "bucket('infobox_monster').select(" + sel + ").limit(5000).run()";
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

	/** Build the id/name indexes from the parsed Bucket rows and publish them atomically. */
	private void index(List<MonsterData> all)
	{
		if (all == null)
		{
			return;
		}

		// Group by name, dropping rows with no combat data (e.g. a Vanguard's non-combat "Moving"
		// pose, which Bucket returns with null stats).
		Map<String, List<MonsterData>> name = new LinkedHashMap<>();
		for (MonsterData m : all)
		{
			if (m == null || m.getName() == null || !hasData(m))
			{
				continue;
			}
			name.computeIfAbsent(m.getName().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(m);
		}

		// Give each variant a unique display label within its name group.
		for (List<MonsterData> group : name.values())
		{
			assignVersions(group);
		}

		// Index by spawn id (each row may carry several). When ids collide across rows (e.g. Duke
		// Sucellus' shared "Defeated" id), the default form wins, else first-seen.
		Map<Integer, MonsterData> id = new HashMap<>();
		for (List<MonsterData> group : name.values())
		{
			for (MonsterData m : group)
			{
				if (m.getIds() == null)
				{
					continue;
				}
				for (String s : m.getIds())
				{
					Integer idn = MonsterData.parseId(s);
					if (idn == null)
					{
						continue;
					}
					MonsterData existing = id.get(idn);
					if (existing == null || (m.isDefaultVersion() && !existing.isDefaultVersion()))
					{
						id.put(idn, m);
					}
				}
			}
		}

		// Publish atomically once fully built.
		this.byId = id;
		this.byName = name;
		log.debug("Indexed {} monster rows ({} unique names)", all.size(), name.size());
	}

	/**
	 * Assign each variant in a name group a unique display {@code version}: the {@code version_anchor}
	 * when it's present and unique, otherwise disambiguated with the combat level (or hitpoints when
	 * the level is unknown) — e.g. {@code "Awake (lvl 758)"} or {@code "Level 100"}. A lone blank
	 * anchor stays "" (the plain "Standard" form).
	 */
	private static void assignVersions(List<MonsterData> group)
	{
		Map<String, Integer> counts = new HashMap<>();
		for (MonsterData m : group)
		{
			counts.merge(baseLabel(m), 1, Integer::sum);
		}
		Set<String> used = new HashSet<>();
		for (MonsterData m : group)
		{
			String base = baseLabel(m);
			String label;
			if (base.isEmpty())
			{
				label = group.size() == 1 ? "" : tier(m);
			}
			else
			{
				label = counts.get(base) == 1 ? base : base + " (" + tierShort(m) + ")";
			}
			// Guarantee uniqueness even if level/hitpoints also coincide.
			String unique = label;
			int n = 2;
			while (!unique.isEmpty() && !used.add(unique.toLowerCase(Locale.ROOT)))
			{
				unique = label + " #" + n++;
			}
			m.setVersion(unique);
		}
	}

	private static String baseLabel(MonsterData m)
	{
		return m.getVersionAnchor() == null ? "" : m.getVersionAnchor().trim();
	}

	private static String tier(MonsterData m)
	{
		return m.getLevel() > 0 ? "Level " + m.getLevel() : "HP " + m.getHitpoints();
	}

	private static String tierShort(MonsterData m)
	{
		return m.getLevel() > 0 ? "lvl " + m.getLevel() : "hp " + m.getHitpoints();
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

	/**
	 * The variant to show for a name: the one matching {@code version} (case-insensitive), else
	 * {@link #defaultVariant}. Null when the name has no variants.
	 */
	public MonsterData variant(String name, String version)
	{
		List<MonsterData> variants = variantsForName(name);
		if (variants.isEmpty())
		{
			return null;
		}
		if (version != null && !version.isEmpty())
		{
			for (MonsterData v : variants)
			{
				if (version.equalsIgnoreCase(v.getVersion()))
				{
					return v;
				}
			}
		}
		return defaultVariant(variants);
	}

	/**
	 * Pick a sensible default from a name's variants: the Bucket-flagged default form with data;
	 * else the plain (unlabelled) form with data; else the highest-level variant with data; failing
	 * that, any standard form, else the first. Pure over its argument.
	 */
	public MonsterData defaultVariant(List<MonsterData> variants)
	{
		for (MonsterData m : variants)
		{
			if (m.isDefaultVersion() && hasData(m))
			{
				return m;
			}
		}
		for (MonsterData m : variants)
		{
			if (isStandard(m) && hasData(m))
			{
				return m;
			}
		}

		MonsterData best = null;
		for (MonsterData m : variants)
		{
			if (hasData(m) && (best == null || m.getLevel() > best.getLevel()))
			{
				best = m;
			}
		}
		if (best != null)
		{
			return best;
		}

		for (MonsterData m : variants)
		{
			if (isStandard(m))
			{
				return m;
			}
		}
		return variants.isEmpty() ? null : variants.get(0);
	}

	/** The version string of the variant whose combat level matches {@code combatLevel}, or null. */
	public String variantVersionForLevel(String name, int combatLevel)
	{
		for (MonsterData v : variantsForName(name))
		{
			if (v.getLevel() == combatLevel)
			{
				return v.getVersion();
			}
		}
		return null;
	}

	private static boolean isStandard(MonsterData m)
	{
		return m.getVersion() == null || m.getVersion().isEmpty();
	}

	private static boolean hasData(MonsterData m)
	{
		return m.getHitpoints() > 0 || m.getLevel() > 0;
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
