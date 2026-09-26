package com.bettermonsterexamine.slayer;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * The Slayer items a monster needs — rock hammer for gargoyles, nose peg for aberrant spectres. The
 * wiki only states these in prose (no Bucket field, and the Slayer equipment table's "Use(s)" column is
 * free text), so they're kept by hand in {@code /slayer/required-items.json}, keyed by <b>monster
 * name</b> rather than category: a category is too coarse (Dusk and Dawn are both "Gargoyles", but only
 * Dusk needs the hammer; sulphur lizards are "Lizards" but need no ice cooler). Every pairing there is
 * one the monster's own wiki page states.
 */
@Slf4j
@Singleton
public class RequiredItems
{
	private static final String RESOURCE = "/slayer/required-items.json";

	private final Map<String, Requirement> byMonster;

	@Inject
	RequiredItems(Gson gson)
	{
		this(read(gson));
	}

	RequiredItems(List<Requirement> table)
	{
		Map<String, Requirement> map = new HashMap<>();
		for (Requirement r : table)
		{
			if (r.monsters == null || r.items == null || r.items.isEmpty())
			{
				continue;
			}
			for (String m : r.monsters)
			{
				map.put(m.toLowerCase(Locale.ROOT), r);
			}
		}
		this.byMonster = map;
	}

	/** What the named monster needs, or null when it needs nothing special. */
	public Requirement forMonster(String name)
	{
		return name == null ? null : byMonster.get(name.toLowerCase(Locale.ROOT));
	}

	static List<Requirement> read(Gson gson)
	{
		try (InputStream in = RequiredItems.class.getResourceAsStream(RESOURCE))
		{
			if (in == null)
			{
				return Collections.emptyList();
			}
			try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8))
			{
				List<Requirement> table = gson.fromJson(r, new TypeToken<List<Requirement>>()
				{
				}.getType());
				return table == null ? Collections.emptyList() : table;
			}
		}
		catch (IOException | JsonParseException e)
		{
			log.warn("Failed to read {}", RESOURCE, e);
			return Collections.emptyList();
		}
	}

	/** One table entry: the items (the usual one first, then what can stand in for it) and their use. */
	@Getter
	public static class Requirement
	{
		/** e.g. {@code "Finishing blow"}, {@code "Protection"}, {@code "Lure"}. */
		private String use;
		private List<String> items;
		/** A condition on the requirement, e.g. {@code "In the Karuulm Slayer Dungeon"}; may be null. */
		private String note;
		private List<String> monsters;
	}
}
