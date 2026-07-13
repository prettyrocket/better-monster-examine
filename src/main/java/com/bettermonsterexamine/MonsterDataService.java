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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
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
	private static final File LEVELS_CACHE_FILE = new File(CACHE_DIR, "level-ranges.json");
	private static final Duration MAX_AGE = Duration.ofDays(7);
	/** MediaWiki caps a multi-title query at 50 pages. */
	private static final int TITLES_PER_QUERY = 50;
	private static final Type LEVEL_RANGES_TYPE =
		new TypeToken<Map<String, Map<String, InfoboxLevels.LevelText>>>()
		{
		}.getType();

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
	// "name|version anchor" (lower-case) -> the levels Bucket dropped for that variant; see gapFill.
	private volatile Map<String, Map<String, InfoboxLevels.LevelText>> levelRanges = Collections.emptyMap();

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
		// Load the gap-filled levels first, so the very first index() already applies them.
		readLevelRanges();

		boolean haveCache = false;
		List<MonsterData> rows = null;
		if (CACHE_FILE.isFile())
		{
			try (Reader r = Files.newBufferedReader(CACHE_FILE.toPath(), StandardCharsets.UTF_8))
			{
				BucketResponse cached = gson.fromJson(r, BucketResponse.class);
				// Only trust a cache that yielded rows; a truncated/empty/old-format file falls
				// through to a fetch rather than masquerading as a valid (and "fresh") dataset.
				if (cached != null && cached.bucket != null && !cached.bucket.isEmpty())
				{
					rows = cached.bucket;
					index(rows);
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
			// A fresh dataset cached before this feature existed has no level ranges beside it;
			// fill them without re-pulling the whole bestiary.
			if (levelRanges.isEmpty())
			{
				gapFill(rows);
			}
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
					// The dataset is usable now (the gaps read as a dash, as they always did); the
					// handful of levels Bucket can't carry fill in behind it.
					gapFill(parsed.bucket);
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

	// ---- level gap-fill ------------------------------------------------------
	//
	// Bucket types the five combat levels as INTEGER, so a level the wiki writes as a range never
	// reaches us: the wiki's own module writes it with tonumber(), a range yields nil, and the field
	// is dropped from the row. Vardorvis is the only monster in the bestiary this actually costs
	// (its Strength and Defence scale with remaining HP, e.g. "270-360"), which is why they rendered
	// as a dash. There is no Bucket field to fix, so the values come from the page wikitext instead.
	//
	// Rather than make stats fetch per monster — which would cost the whole layer its offline-first,
	// synchronous render — we only ever look at rows Bucket left a hole in (~18 pages bestiary-wide,
	// the rest of which are genuinely blank on the wiki and rightly stay a dash) and pull those in
	// one batched query, cached and refreshed alongside the dataset itself.

	/** The Bucket row key a parsed page's levels are matched back to: name + version anchor. */
	private static String levelKey(String name, String versionAnchor)
	{
		String anchor = versionAnchor == null ? "" : versionAnchor.trim();
		return name.toLowerCase(Locale.ROOT) + "|" + anchor.toLowerCase(Locale.ROOT);
	}

	/** Fetch + parse the pages whose Bucket rows are missing a level, then re-index with the results. */
	private void gapFill(List<MonsterData> rows)
	{
		if (rows == null)
		{
			return;
		}
		List<String> pages = rows.stream()
			.filter(m -> m != null && m.getName() != null && hasData(m) && m.hasMissingLevel())
			.map(MonsterData::getName)
			.distinct()
			.collect(Collectors.toList());
		if (pages.isEmpty())
		{
			return;
		}

		log.debug("Gap-filling levels from {} wiki page(s)", pages.size());
		Map<String, Map<String, InfoboxLevels.LevelText>> found = new ConcurrentHashMap<>();
		AtomicInteger pending = new AtomicInteger((pages.size() + TITLES_PER_QUERY - 1) / TITLES_PER_QUERY);
		for (int i = 0; i < pages.size(); i += TITLES_PER_QUERY)
		{
			List<String> batch = pages.subList(i, Math.min(i + TITLES_PER_QUERY, pages.size()));
			fetchWikitext(batch, found, () ->
			{
				// Publish once every batch has reported, so the dataset is re-indexed only once.
				if (pending.decrementAndGet() > 0)
				{
					return;
				}
				if (found.isEmpty())
				{
					return;
				}
				this.levelRanges = found;
				writeLevelRanges(found);
				index(rows);
				log.info("Recovered {} monster level(s) the Bucket API cannot carry", found.size());
			});
		}
	}

	/** One batched {@code action=query} for up to 50 pages' wikitext; parses each into {@code found}. */
	private void fetchWikitext(List<String> titles, Map<String, Map<String, InfoboxLevels.LevelText>> found,
		Runnable done)
	{
		HttpUrl url = HttpUrl.get(API_URL).newBuilder()
			.addQueryParameter("action", "query")
			.addQueryParameter("format", "json")
			.addQueryParameter("formatversion", "2")
			.addQueryParameter("prop", "revisions")
			.addQueryParameter("rvprop", "content")
			.addQueryParameter("rvslots", "main")
			.addQueryParameter("redirects", "1")
			.addQueryParameter("titles", String.join("|", titles))
			.build();
		Request req = new Request.Builder().url(url).header("User-Agent", USER_AGENT).build();
		http.newCall(req).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				// The dataset still serves; these levels simply stay a dash until the next refresh.
				log.debug("Level gap-fill fetch failed", e);
				done.run();
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response res = response)
				{
					if (res.isSuccessful() && res.body() != null)
					{
						readWikitext(gson.fromJson(res.body().string(), QueryResponse.class), titles, found);
					}
				}
				catch (Exception e)
				{
					log.debug("Level gap-fill parse failed", e);
				}
				done.run();
			}
		});
	}

	/**
	 * Parse each returned page's infobox and key its levels back to the Bucket rows they belong to.
	 * The monster name we asked for isn't always the title we get back (MediaWiki normalises case and
	 * follows redirects), so the response's own alias lists map our name to the page that answered it.
	 */
	private static void readWikitext(QueryResponse res, List<String> titles,
		Map<String, Map<String, InfoboxLevels.LevelText>> found)
	{
		if (res == null || res.query == null || res.query.pages == null)
		{
			return;
		}

		Map<String, String> alias = new HashMap<>();
		addAliases(alias, res.query.normalized);
		addAliases(alias, res.query.redirects);

		Map<String, String> wikitext = new HashMap<>();
		for (QueryResponse.Page page : res.query.pages)
		{
			String content = page.content();
			if (page.title != null && content != null)
			{
				wikitext.put(page.title.toLowerCase(Locale.ROOT), content);
			}
		}

		for (String name : titles)
		{
			String title = name.toLowerCase(Locale.ROOT);
			// Follow name -> normalised -> redirect target (bounded, so a redirect loop can't hang).
			for (int hop = 0; hop < 4 && alias.containsKey(title); hop++)
			{
				title = alias.get(title);
			}
			String content = wikitext.get(title);
			if (content == null)
			{
				continue;
			}
			for (Map.Entry<String, Map<String, InfoboxLevels.LevelText>> e : InfoboxLevels.parse(content).entrySet())
			{
				found.put(levelKey(name, e.getKey()), e.getValue());
			}
		}
	}

	private static void addAliases(Map<String, String> into, List<QueryResponse.Alias> aliases)
	{
		if (aliases == null)
		{
			return;
		}
		for (QueryResponse.Alias a : aliases)
		{
			if (a.from != null && a.to != null)
			{
				into.put(a.from.toLowerCase(Locale.ROOT), a.to.toLowerCase(Locale.ROOT));
			}
		}
	}

	private void readLevelRanges()
	{
		if (!LEVELS_CACHE_FILE.isFile())
		{
			return;
		}
		try (Reader r = Files.newBufferedReader(LEVELS_CACHE_FILE.toPath(), StandardCharsets.UTF_8))
		{
			Map<String, Map<String, InfoboxLevels.LevelText>> cached = gson.fromJson(r, LEVEL_RANGES_TYPE);
			if (cached != null && !cached.isEmpty())
			{
				this.levelRanges = cached;
			}
		}
		catch (Exception e)
		{
			log.debug("Failed to read cached level ranges", e);
		}
	}

	private void writeLevelRanges(Map<String, Map<String, InfoboxLevels.LevelText>> ranges)
	{
		try
		{
			Files.createDirectories(CACHE_DIR.toPath());
			Files.write(LEVELS_CACHE_FILE.toPath(), gson.toJson(ranges, LEVEL_RANGES_TYPE)
				.getBytes(StandardCharsets.UTF_8));
		}
		catch (IOException e)
		{
			log.debug("Failed to cache level ranges", e);
		}
	}

	/** The slice of the MediaWiki {@code action=query} response we need: each page's wikitext. */
	private static final class QueryResponse
	{
		private Query query;

		private static final class Query
		{
			private List<Alias> normalized;
			private List<Alias> redirects;
			private List<Page> pages;
		}

		/** A title MediaWiki rewrote — {@code from} is what we asked for, {@code to} what answered. */
		private static final class Alias
		{
			private String from;
			private String to;
		}

		private static final class Page
		{
			private String title;
			private List<Revision> revisions;

			private String content()
			{
				if (revisions == null || revisions.isEmpty())
				{
					return null;
				}
				Revision rev = revisions.get(0);
				return rev.slots == null || rev.slots.main == null ? null : rev.slots.main.content;
			}
		}

		private static final class Revision
		{
			private Slots slots;
		}

		private static final class Slots
		{
			private Slot main;
		}

		private static final class Slot
		{
			private String content;
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
		Map<String, Map<String, InfoboxLevels.LevelText>> ranges = this.levelRanges;
		for (MonsterData m : all)
		{
			if (m == null || m.getName() == null || !hasData(m))
			{
				continue;
			}
			// Hand back the levels Bucket dropped for this variant, if we recovered any.
			Map<String, InfoboxLevels.LevelText> dropped = ranges.get(levelKey(m.getName(), m.getVersionAnchor()));
			m.setLevelRanges(dropped != null ? dropped : Collections.emptyMap());
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
