package com.bettermonsterexamine;

import com.bettermonsterexamine.wiki.WikiApi;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

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
	private static final File CACHE_FILE =
		new File(new File(RuneLite.RUNELITE_DIR, "better-monster-examine"), "superiors.json");
	private static final Duration MAX_AGE = Duration.ofDays(7);
	/** MediaWiki caps a multi-title query at 50 pages. */
	private static final int TITLES_PER_QUERY = 50;
	private static final Type PAIRS_TYPE = new TypeToken<Map<String, String>>()
	{
	}.getType();

	/** The table's normal-variant and superior-variant columns, after the Slayer level. */
	private static final int NORMAL_COL = 1;
	private static final int SUPERIOR_COL = 2;

	private static final Pattern ROWSPAN = Pattern.compile("rowspan\\s*=\\s*\"?(\\d+)");
	private static final Pattern LINK = Pattern.compile("\\[\\[([^\\]|]+)");

	private final Gson gson;
	private final OkHttpClient http;

	/** Lower-case normal monster name (page title and link label both) → the superior's page title. */
	private volatile Map<String, String> superiors = Collections.emptyMap();
	private volatile Runnable updateListener;

	@Inject
	SuperiorService(Gson gson, OkHttpClient http, ScheduledExecutorService executor)
	{
		this.gson = gson;
		this.http = http;
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
		if (CACHE_FILE.isFile())
		{
			try
			{
				Map<String, String> cached = gson.fromJson(
					new String(Files.readAllBytes(CACHE_FILE.toPath()), StandardCharsets.UTF_8), PAIRS_TYPE);
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
		}
		if (haveCache && System.currentTimeMillis() - CACHE_FILE.lastModified() < MAX_AGE.toMillis())
		{
			return;
		}

		// Blocking calls are fine here: this is the executor, never the client thread or the EDT.
		try
		{
			JsonObject page = get(HttpUrl.get(WikiApi.API_URL).newBuilder()
				.addQueryParameter("action", "parse")
				.addQueryParameter("format", "json")
				.addQueryParameter("prop", "wikitext")
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
			Files.createDirectories(CACHE_FILE.getParentFile().toPath());
			Files.write(CACHE_FILE.toPath(), gson.toJson(pairs).getBytes(StandardCharsets.UTF_8));
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
		List<String> titles = new ArrayList<>(pairs.keySet());
		for (int i = 0; i < titles.size(); i += TITLES_PER_QUERY)
		{
			try
			{
				JsonObject query = get(HttpUrl.get(WikiApi.API_URL).newBuilder()
					.addQueryParameter("action", "query")
					.addQueryParameter("format", "json")
					.addQueryParameter("redirects", "1")
					.addQueryParameter("titles",
						String.join("|", titles.subList(i, Math.min(i + TITLES_PER_QUERY, titles.size()))))
					.build()).getAsJsonObject("query");
				// "normalized" first-letter-capitalises a title; "redirects" then follows it.
				for (String kind : new String[]{"normalized", "redirects"})
				{
					if (query == null || !query.has(kind))
					{
						continue;
					}
					for (JsonElement el : query.getAsJsonArray(kind))
					{
						JsonObject r = el.getAsJsonObject();
						String superior = pairs.containsKey(r.get("from").getAsString())
							? pairs.get(r.get("from").getAsString())
							: out.get(r.get("from").getAsString());
						if (superior != null)
						{
							out.put(r.get("to").getAsString(), superior);
						}
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

	private JsonObject get(HttpUrl url) throws IOException
	{
		Request req = new Request.Builder().url(url).header("User-Agent", WikiApi.USER_AGENT).build();
		try (Response res = http.newCall(req).execute())
		{
			if (!res.isSuccessful() || res.body() == null)
			{
				throw new IOException("HTTP " + res.code());
			}
			return gson.fromJson(res.body().string(), JsonObject.class);
		}
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

	/** Index of the {@code |} splitting a cell's attributes from its content, ignoring links and templates. */
	private static int attributeBar(String cell)
	{
		int depth = 0;
		for (int i = 0; i < cell.length(); i++)
		{
			char ch = cell.charAt(i);
			if ((ch == '[' || ch == '{') && i + 1 < cell.length() && cell.charAt(i + 1) == ch)
			{
				depth++;
				i++;
			}
			else if ((ch == ']' || ch == '}') && i + 1 < cell.length() && cell.charAt(i + 1) == ch)
			{
				depth--;
				i++;
			}
			else if (ch == '|' && depth == 0)
			{
				return i;
			}
		}
		return -1;
	}

	private static String attributes(String cell)
	{
		int bar = attributeBar(cell);
		return bar < 0 ? "" : cell.substring(0, bar);
	}

	private static String content(String cell)
	{
		int bar = attributeBar(cell);
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
