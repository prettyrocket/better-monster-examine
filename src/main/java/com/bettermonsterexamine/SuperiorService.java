package com.bettermonsterexamine;

import com.bettermonsterexamine.wiki.TitleResolver;
import com.bettermonsterexamine.wiki.WikiApi;
import com.bettermonsterexamine.wiki.WikiCache;
import com.bettermonsterexamine.wiki.WikiClient;
import com.bettermonsterexamine.wiki.WikitextTemplates;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import okhttp3.OkHttpClient;

/**
 * Which superior slayer monster each Slayer monster can spawn. No Bucket carries the pairing — the
 * {@code infobox_monster} rows of a monster and its superior share nothing but a slayer category, and
 * a category can hold several pairs (Bloodveld and Mutated bloodveld). The one structured source is
 * the table on the wiki's <i>Superior slayer monster</i> page, so that page's wikitext is fetched,
 * cached beside the dataset, and refreshed weekly like it.
 */
@Slf4j
@Singleton
public class SuperiorService
{
	private static final String PAGE = "Superior slayer monster";
	private static final Type PAIRS_TYPE = new TypeToken<Map<String, String>>()
	{
	}.getType();

	/** The table's normal-variant and superior-variant columns, after the Slayer level. */
	private static final int NORMAL_COL = 1;
	private static final int SUPERIOR_COL = 2;

	private static final Pattern ROWSPAN = Pattern.compile("rowspan\\s*=\\s*\"?(\\d+)");
	private static final Pattern LINK = Pattern.compile("\\[\\[([^\\]|]+)");

	private final Gson gson;
	private final WikiClient wiki;
	private final WikiCache cache = new WikiCache(
		new File(new File(RuneLite.RUNELITE_DIR, "better-monster-examine"), "superiors.json"), Duration.ofDays(7));

	/** Lower-case normal monster name (page title and link label both) → the superior's page title. */
	private volatile Map<String, String> superiors = Collections.emptyMap();
	private volatile Runnable updateListener;

	@Inject
	SuperiorService(Gson gson, OkHttpClient http, ScheduledExecutorService executor)
	{
		this.gson = gson;
		this.wiki = WikiApi.client(http, gson);
		executor.execute(this::init);
	}

	/** The superior {@code name} can spawn, or null when it has none (or the table hasn't loaded). */
	public String superiorOf(String name)
	{
		return name == null ? null : superiors.get(name.toLowerCase(Locale.ROOT));
	}

	/** Called (off the EDT) when the table lands or refreshes, so an open card can re-render. */
	public void setUpdateListener(Runnable listener)
	{
		this.updateListener = listener;
	}

	private void init()
	{
		boolean haveCache = false;
		try
		{
			String text = cache.read();
			Map<String, String> cached = text == null ? null : gson.fromJson(text, PAIRS_TYPE);
			haveCache = cached != null && !cached.isEmpty();
			if (haveCache)
			{
				publish(cached);
			}
		}
		catch (Exception e)
		{
			log.debug("Failed to read cached superior table", e);
		}
		if (haveCache && (cache.isFresh() || cache.revalidate(wiki, PAGE, WikiApi.FULL_REFETCH)))
		{
			return;
		}

		// Blocking calls are fine here: this is the executor, never the client thread or the EDT.
		try
		{
			JsonObject page = wiki.fetchJson(wiki.action("parse")
				.addQueryParameter("prop", "wikitext|revid")
				.addQueryParameter("redirects", "1")
				.addQueryParameter("page", PAGE)
				.build());
			Map<String, String> pairs = parse(page.getAsJsonObject("parse").getAsJsonObject("wikitext")
				.get("*").getAsString());
			if (pairs.isEmpty())
			{
				log.debug("Superior table page carried no pairs");
				return;
			}
			pairs.putAll(redirectTargets(pairs));
			// Publish before caching so a broken page never poisons the cache.
			publish(pairs);
			JsonElement revid = page.getAsJsonObject("parse").get("revid");
			cache.write(gson.toJson(pairs), revid == null ? 0 : revid.getAsLong());
		}
		catch (Exception e)
		{
			log.debug("Superior table fetch failed", e);
		}
	}

	/**
	 * The table links some monsters by a redirect rather than the name the dataset carries ("Rock slug"
	 * for Rockslug), so each linked page is resolved and its target keyed to the same superior. Best
	 * effort: a failed lookup leaves the table's own names, which cover all but those few.
	 */
	private Map<String, String> redirectTargets(Map<String, String> pairs)
	{
		Map<String, String> out = new HashMap<>();
		for (List<String> batch : TitleResolver.batches(new ArrayList<>(pairs.keySet())))
		{
			try
			{
				TitleResolver resolver = new TitleResolver();
				resolver.addAll(wiki.fetchJson(wiki.action("query")
					.addQueryParameter("redirects", "1")
					.addQueryParameter("titles", String.join("|", batch))
					.build()).getAsJsonObject("query"));
				for (String linked : batch)
				{
					String target = resolver.resolve(linked);
					if (!target.equalsIgnoreCase(linked))
					{
						out.put(target, pairs.get(linked));
					}
				}
			}
			catch (Exception e)
			{
				log.debug("Superior redirect lookup failed", e);
			}
		}
		return out;
	}

	/** Publish normal → superior, keyed case-insensitively, and tell the panel. */
	private void publish(Map<String, String> pairs)
	{
		Map<String, String> index = new HashMap<>();
		pairs.forEach((normal, superior) -> index.put(normal.toLowerCase(Locale.ROOT), superior));
		superiors = index;
		Runnable listener = updateListener;
		if (listener != null)
		{
			listener.run();
		}
	}

	/**
	 * Read the superior table's wikitext into normal → superior. Cells are placed by column, tracking
	 * {@code rowspan}: monsters that share a superior (Cockatrice and Moonlight cockatrice → Cockathrice)
	 * are written as one superior cell spanning several rows, and the rows under it carry only the normal
	 * monster. Keyed by the normal monster's page title, as linked — a label only ever differs in case.
	 */
	static Map<String, String> parse(String wikitext)
	{
		Map<String, String> out = new LinkedHashMap<>();
		int start = wikitext.indexOf("{|");
		int end = wikitext.indexOf("\n|}", start);
		if (start < 0 || end < 0)
		{
			return out;
		}

		// Per column (the ones we read and those left of them): the cell text and how many more rows
		// it still covers.
		String[] spanText = new String[SUPERIOR_COL + 1];
		int[] spanLeft = new int[SUPERIOR_COL + 1];
		for (String row : wikitext.substring(start, end).split("\n\\|-"))
		{
			List<String> cells = cells(row);
			if (cells.isEmpty())
			{
				continue;
			}
			String[] col = new String[SUPERIOR_COL + 1];
			int next = 0;
			for (int c = 0; c <= SUPERIOR_COL; c++)
			{
				if (spanLeft[c] > 0)
				{
					spanLeft[c]--;
					col[c] = spanText[c];
				}
				else if (next < cells.size())
				{
					String cell = cells.get(next++);
					col[c] = content(cell);
					Matcher span = ROWSPAN.matcher(attributes(cell));
					if (span.find())
					{
						spanText[c] = col[c];
						spanLeft[c] = Integer.parseInt(span.group(1)) - 1;
					}
				}
			}

			String normal = link(col[NORMAL_COL]);
			String superior = link(col[SUPERIOR_COL]);
			if (normal != null && superior != null)
			{
				out.put(normal, superior);
			}
		}
		return out;
	}

	/** A row's data cells, one per {@code |}-led line (header {@code !} lines and the table opener skipped). */
	private static List<String> cells(String row)
	{
		List<String> cells = new ArrayList<>();
		for (String line : row.split("\n"))
		{
			if (line.startsWith("|") && !line.startsWith("|}") && !line.startsWith("|-"))
			{
				cells.add(line.substring(1));
			}
		}
		return cells;
	}

	private static String attributes(String cell)
	{
		int bar = WikitextTemplates.indexOfBar(cell, 0);
		return bar < 0 ? "" : cell.substring(0, bar);
	}

	private static String content(String cell)
	{
		int bar = WikitextTemplates.indexOfBar(cell, 0);
		return (bar < 0 ? cell : cell.substring(bar + 1)).trim();
	}

	/** The page title of a cell's first wikilink, or null when it has none. */
	private static String link(String cell)
	{
		if (cell == null)
		{
			return null;
		}
		Matcher m = LINK.matcher(cell);
		return m.find() ? m.group(1).trim() : null;
	}
}
