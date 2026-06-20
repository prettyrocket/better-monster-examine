package com.bettermonsterexamine;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Loads the bundled Weirdgloop monster dataset once and indexes it by NPC id and by
 * name. Prototype note: the dataset is bundled as a resource for offline viewing; a
 * shipped plugin should fetch + cache it (async, via the shared OkHttpClient) so it
 * stays current and keeps the jar small.
 */
@Slf4j
@Singleton
public class MonsterDataService
{
	private static final String RESOURCE = "/monsters.json";

	private final Map<Integer, MonsterData> byId = new HashMap<>();
	// lower-case base name -> variants, insertion-ordered
	private final Map<String, List<MonsterData>> byName = new LinkedHashMap<>();

	@Inject
	MonsterDataService(Gson gson)
	{
		try (InputStream in = getClass().getResourceAsStream(RESOURCE))
		{
			if (in == null)
			{
				log.warn("Monster dataset {} not found on classpath", RESOURCE);
				return;
			}

			Type listType = new TypeToken<List<MonsterData>>()
			{
			}.getType();
			List<MonsterData> all = gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), listType);

			for (MonsterData m : all)
			{
				if (m == null || m.getName() == null)
				{
					continue;
				}
				byId.put(m.getId(), m);
				byName.computeIfAbsent(m.getName().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(m);
			}
			log.debug("Loaded {} monster entries ({} unique names)", all.size(), byName.size());
		}
		catch (Exception e)
		{
			log.warn("Failed to load monster dataset", e);
		}
	}

	public MonsterData getById(int npcId)
	{
		return byId.get(npcId);
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
		String q = query.toLowerCase(Locale.ROOT).trim();
		return byName.values().stream()
			.map(v -> v.get(0).getName())
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
