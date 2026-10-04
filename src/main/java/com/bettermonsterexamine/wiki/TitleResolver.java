package com.bettermonsterexamine.wiki;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Maps the titles we asked MediaWiki about to the pages that answered.
 *
 * <p>A multi-title {@code action=query} doesn't answer under the title asked: it first normalises it
 * ({@code "rock slug"} → {@code "Rock slug"}), then, with {@code redirects=1}, follows a redirect
 * ({@code "Rock slug"} → {@code "Rockslug"}). The response lists both hops under {@code normalized}
 * and {@code redirects}. Collect them with {@link #addAll} and {@link #resolve} follows the chain,
 * case-insensitively and with a hop limit, so a redirect loop can't hang it.
 */
public final class TitleResolver
{
	/** MediaWiki's cap on titles per query for an anonymous client. */
	public static final int TITLES_PER_QUERY = 50;
	private static final int MAX_HOPS = 4;

	/** Lower-case "from" title → the "to" title as the wiki spells it. */
	private final Map<String, String> aliases = new HashMap<>();

	/** Record one hop. */
	public void add(String from, String to)
	{
		if (from != null && to != null)
		{
			aliases.put(from.toLowerCase(Locale.ROOT), to);
		}
	}

	/** Record the {@code normalized} and {@code redirects} hops of an {@code action=query} response's {@code query}. */
	public void addAll(JsonObject query)
	{
		if (query == null)
		{
			return;
		}
		for (String kind : new String[]{"normalized", "redirects"})
		{
			JsonElement list = query.get(kind);
			if (list == null || !list.isJsonArray())
			{
				continue;
			}
			for (JsonElement el : list.getAsJsonArray())
			{
				JsonObject hop = el.getAsJsonObject();
				JsonElement from = hop.get("from");
				JsonElement to = hop.get("to");
				if (from != null && to != null)
				{
					add(from.getAsString(), to.getAsString());
				}
			}
		}
	}

	/** The page that answered for {@code asked}, as the wiki spells it; {@code asked} itself when nothing redirected it. */
	public String resolve(String asked)
	{
		String title = asked;
		for (int hop = 0; hop < MAX_HOPS; hop++)
		{
			String next = aliases.get(title.toLowerCase(Locale.ROOT));
			if (next == null)
			{
				break;
			}
			title = next;
		}
		return title;
	}

	/** Split titles into lists of at most {@link #TITLES_PER_QUERY}, in order. */
	public static <T> List<List<T>> batches(List<T> titles)
	{
		List<List<T>> out = new ArrayList<>();
		for (int i = 0; i < titles.size(); i += TITLES_PER_QUERY)
		{
			out.add(titles.subList(i, Math.min(i + TITLES_PER_QUERY, titles.size())));
		}
		return out;
	}
}
