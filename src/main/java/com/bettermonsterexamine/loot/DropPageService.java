package com.bettermonsterexamine.loot;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
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
 * Provides a monster's drop tables on demand by parsing its <b>rendered OSRS Wiki page</b> (the
 * MediaWiki {@code action=parse} API), grouping rows under the wiki's own section headings. This is
 * the only source that carries the wiki's editorial drop tables — Herbs, Gem/Rare drop table,
 * <b>Catacombs of Kourend</b> and <b>Wilderness Slayer Cave</b> tables, Tertiary, … — which the
 * structured drop data cannot express (it carries no section field, and can't even see the region
 * tables).
 *
 * <p>Fetched <b>per monster</b> the first time it's looked up and cached under
 * {@code .runelite/better-monster-examine/droppages/}, refreshed weekly ({@link #MAX_AGE}) — the same
 * on-demand-per-page pattern the drops fetch always used, just from the page instead of the bucket.
 * All loading happens off the client thread and EDT; {@link #tableFor} reads the concurrent by-page
 * index without blocking and returns null until a page lands. Item icon / GE price / High Alch are
 * <b>not</b> parsed here — they come from the RuneLite client by item id at render time.
 */
@Slf4j
@Singleton
public class DropPageService
{
	private static final String API_URL = "https://oldschool.runescape.wiki/api.php";
	private static final String USER_AGENT = "better-monster-examine (RuneLite plugin)";
	private static final File CACHE_DIR = new File(RuneLite.RUNELITE_DIR, "better-monster-examine/droppages");
	private static final Duration MAX_AGE = Duration.ofDays(7);

	// Drop-table structure on a rendered monster page (see the Drops section):
	//   <tr> [inventory-image] [item-col: <a href="/w/Item">] [qty] [table-bg-*: rarity] [ge] [alch]
	// Rows inherit the <h3>/<h4> section heading above them; a drops <h2> region runs to the next <h2>.
	private static final Pattern H2 = Pattern.compile("<h2[^>]*>(.*?)</h2>", Pattern.DOTALL);
	private static final Pattern HEADING = Pattern.compile("<h([34])[^>]*\\sid=\"[^\"]+\"[^>]*>(.*?)</h\\1>", Pattern.DOTALL);
	private static final Pattern ROW = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL);
	private static final Pattern ITEM = Pattern.compile("class=\"item-col\"[^>]*>.*?<a href=\"/w/([^\"?#]+)\"", Pattern.DOTALL);
	private static final Pattern TD = Pattern.compile("<td([^>]*)>(.*?)</td>", Pattern.DOTALL);
	private static final Pattern TAG = Pattern.compile("<[^>]+>");
	private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(\\d+);");
	private static final Pattern FOOTNOTE = Pattern.compile("\\[[^\\]]*\\]");
	// Each rarity value is wrapped in a span carrying every form of the odds; the element's own text is
	// the raw source fraction ("16/100") and data-drop-oneover is the 1/x the wiki actually shows.
	private static final Pattern ONE_OVER =
		Pattern.compile("<span[^>]*data-drop-oneover=\"([^\"]*)\"[^>]*>.*?</span>", Pattern.DOTALL);

	private final Gson gson;
	private final OkHttpClient http;
	private final ScheduledExecutorService executor;

	/** lower-case page name -> that page's parsed drop rows; an entry means "loaded". */
	private final Map<String, List<DropRow>> byPage = new ConcurrentHashMap<>();
	/** Pages with a fetch in flight, so concurrent requests coalesce into one network call. */
	private final Set<String> loading = ConcurrentHashMap.newKeySet();

	private volatile Consumer<String> updateListener;

	@Inject
	DropPageService(Gson gson, OkHttpClient http, ScheduledExecutorService executor)
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
	 * The drop table for a page — the wiki's sections in page order — or null when the page isn't
	 * loaded yet (call {@link #request} first and react via the update listener). A loaded page with no
	 * drops yields an empty table, never null.
	 */
	public DropTable tableFor(String pageName)
	{
		List<DropRow> rows = byPage.get(pageName.toLowerCase(Locale.ROOT));
		return rows == null ? null : DropTable.of(rows);
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
				List<DropRow> rows = parse(htmlOf(gson.fromJson(r, ParseResponse.class)));
				if (rows != null)
				{
					publish(key, rows, pageName);
					haveCache = true;
				}
			}
			catch (Exception e)
			{
				log.debug("Failed to read cached drop page for {}", pageName, e);
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
	 * Fetch and parse the monster's wiki page synchronously on the executor thread — never the client
	 * thread or EDT — then publish and cache it. A failed request leaves any served stale cache in place.
	 */
	private void fetch(String pageName, String key, File cacheFile)
	{
		try
		{
			HttpUrl url = HttpUrl.get(API_URL).newBuilder()
				.addQueryParameter("action", "parse")
				.addQueryParameter("page", pageName)
				.addQueryParameter("prop", "text")
				.addQueryParameter("format", "json")
				// Follow redirects: plenty of monster names are redirect pages ("Hill giant" →
				// "Hill Giant"), and without this we'd parse the redirect stub and show no drops.
				.addQueryParameter("redirects", "1")
				.build();
			Request req = new Request.Builder().url(url).header("User-Agent", USER_AGENT).build();
			try (Response res = http.newCall(req).execute())
			{
				if (!res.isSuccessful() || res.body() == null)
				{
					log.debug("Drop page fetch for {} returned {}", pageName, res.code());
					return;
				}
				String json = res.body().string();
				String html = htmlOf(gson.fromJson(json, ParseResponse.class));
				List<DropRow> rows = parse(html);
				if (rows == null)
				{
					log.debug("Drop page for {} carried no rendered text", pageName);
					return;
				}
				// Parse (and publish) before caching so a corrupt download never poisons the cache.
				publish(key, rows, pageName);
				writeCache(cacheFile, json);
				log.debug("Parsed and cached {} drop rows for {}", rows.size(), pageName);
			}
		}
		catch (Exception e)
		{
			log.debug("Drop page fetch/parse failed for {}", pageName, e);
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

	private static String htmlOf(ParseResponse response)
	{
		if (response == null || response.parse == null || response.parse.text == null)
		{
			return null;
		}
		return response.parse.text.star;
	}

	/**
	 * Parse a rendered wiki page's HTML into ordered drop rows, each tagged with the section heading it
	 * sits under. Restricted to the page's <b>drops {@code <h2>} region(s)</b> — a single "Drops"
	 * heading, or, for combat-level variants, several "Level <i>N</i> drops" headings each with their own
	 * 100% / Tertiary / … subsections (e.g. Giant frog). Each region runs to the next {@code <h2>}. Null
	 * when there's no HTML; empty when the page has no drops section. Pure, so it's unit-testable.
	 */
	static List<DropRow> parse(String html)
	{
		if (html == null)
		{
			return null;
		}

		// Locate every <h2> so a drops region can be cut from its heading to the next <h2> of any kind.
		List<Heading2> h2s = new ArrayList<>();
		Matcher h2m = H2.matcher(html);
		while (h2m.find())
		{
			h2s.add(new Heading2(h2m.end(), clean(h2m.group(1))));
		}

		// Drop tables live under an <h2> whose title mentions "drops" — one generic "Drops" heading on
		// most pages, or per-level headings on combat-level variants. Anything else (store, spawns, …) is
		// ignored, so unrelated tables never surface as bogus drops (we fail closed when there are none).
		List<DropRow> out = new ArrayList<>();
		for (int i = 0; i < h2s.size(); i++)
		{
			Heading2 h2 = h2s.get(i);
			if (!h2.title.toLowerCase(Locale.ROOT).contains("drop"))
			{
				continue;
			}
			int end = i + 1 < h2s.size() ? h2s.get(i + 1).start : html.length();
			parseRegion(html.substring(h2.start, end), h2.title, out);
		}
		return out;
	}

	/**
	 * Parse one drops {@code <h2>} region, appending its rows to {@code out}. Rows inherit the headings
	 * above them, and the heading <b>depth</b> decides what is a group and what is a section.
	 *
	 * <p>The depth matters because the wiki uses the extra level to split a monster's drops by location
	 * or combat level. On a Cyclops the {@code <h3>}s are the locations (Warriors' Guild top floor /
	 * basement) and the {@code <h4>}s under each are that location's tables — so an {@code <h3>} with
	 * {@code <h4>}s beneath it is a <i>group</i> and the {@code <h4>} is the section, while an
	 * {@code <h3>} with no {@code <h4>}s (the common case) <i>is</i> the section. A non-generic
	 * {@code <h2>} ("Level 99 drops", "Drop table 2") is itself a group. Collapsing these levels merges
	 * the top floor's and basement's like-named tables — same names, different drops and rates — which
	 * is what made the Dragon defender (basement "Pre-roll" only) look like a drop from every Cyclops.
	 */
	private static void parseRegion(String region, String h2Title, List<DropRow> out)
	{
		// A generic "Drops" heading adds no grouping of its own — the headings under it carry it all.
		boolean generic = h2Title.equalsIgnoreCase("Drops");

		// Merge headings and rows by position, so each row inherits the headings above it.
		List<Event> events = new ArrayList<>();
		Matcher hm = HEADING.matcher(region);
		while (hm.find())
		{
			events.add(new Event(hm.start(), Integer.parseInt(hm.group(1)), clean(hm.group(2)), null));
		}
		Matcher rm = ROW.matcher(region);
		while (rm.find())
		{
			String rowHtml = rm.group(1);
			if (!rowHtml.contains("item-col"))
			{
				continue;
			}
			String[] cells = rowCells(rowHtml);
			if (cells != null)
			{
				events.add(new Event(rm.start(), 0, null, cells));
			}
		}
		events.sort((a, b) -> Integer.compare(a.pos, b.pos));

		String h3 = null;
		String h4 = null;
		for (Event e : events)
		{
			if (e.heading != null)
			{
				if (e.level == 3)
				{
					h3 = e.heading;
					// A new <h3> opens a new group; its rows must not inherit the last group's <h4>.
					h4 = null;
				}
				else
				{
					h4 = e.heading;
				}
				continue;
			}

			// The deepest heading names the table; an <h3> is only a group once an <h4> sits under it.
			String section = h4 != null ? h4 : h3;
			String group = join(generic ? null : h2Title, h4 != null ? h3 : null);
			if (section == null)
			{
				// No subheading at all: a generic region has nothing to attribute the rows to (there
				// should be none); a per-level region falls back to its own <h2> title as the section.
				if (generic)
				{
					continue;
				}
				section = h2Title;
				group = "";
			}
			out.add(new DropRow(e.cells[0], e.cells[1], e.cells[2], group, section));
		}
	}

	/** Join the heading levels scoping a row into one group label, skipping the ones it doesn't have. */
	private static String join(String outer, String inner)
	{
		if (outer == null || outer.isEmpty())
		{
			return inner == null ? "" : inner;
		}
		if (inner == null || inner.isEmpty())
		{
			return outer;
		}
		return outer + ": " + inner;
	}

	/** Pull [item, quantity, rarity] out of one drop-table row, or null when it has no item link. */
	private static String[] rowCells(String rowHtml)
	{
		Matcher im = ITEM.matcher(rowHtml);
		if (!im.find())
		{
			return null;
		}
		String item = decodeSlug(im.group(1));

		List<String> tds = new ArrayList<>();
		String rarity = "";
		Matcher tm = TD.matcher(rowHtml);
		while (tm.find())
		{
			tds.add(tm.group(2));
			// The rarity cell is the one the wiki colours by rarity tier (class contains table-bg).
			if (rarity.isEmpty() && tm.group(1).contains("table-bg"))
			{
				rarity = rarityText(tm.group(2));
			}
		}
		String quantity = tds.size() >= 3 ? clean(tds.get(2)) : "";
		if (rarity.isEmpty() && tds.size() >= 4)
		{
			rarity = rarityText(tds.get(3));
		}
		return new String[]{item, quantity, rarity};
	}

	/**
	 * Read a rarity cell the way the wiki displays it. Each value sits in a span whose <b>text</b> is the
	 * raw source fraction and whose {@code data-drop-oneover} is the same odds as 1/x — the form the wiki
	 * renders, and the only one a player can compare at a glance ({@code 16/100} vs {@code 1/6.25}). So
	 * take the attribute where there is one, and keep everything around it (a multi-roll {@code "2 × "}
	 * prefix, a {@code ';'} joining a per-roll rate to the combined per-kill rate) as written.
	 */
	private static String rarityText(String cellHtml)
	{
		Matcher m = ONE_OVER.matcher(cellHtml);
		StringBuilder sb = new StringBuilder();
		while (m.find())
		{
			m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1)));
		}
		m.appendTail(sb);
		return FOOTNOTE.matcher(clean(sb.toString())).replaceAll("").trim();
	}

	/** A wiki page slug ({@code Grimy_ranarr_weed}) → a display name ({@code Grimy ranarr weed}). */
	private static String decodeSlug(String slug)
	{
		try
		{
			return URLDecoder.decode(slug, "UTF-8").replace('_', ' ');
		}
		catch (UnsupportedEncodingException | IllegalArgumentException e)
		{
			return slug.replace('_', ' ');
		}
	}

	/** Strip tags, unescape the entities the wiki emits, and collapse whitespace. */
	static String clean(String s)
	{
		if (s == null)
		{
			return "";
		}
		String t = TAG.matcher(s).replaceAll(" ");
		t = t.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
			.replace("&quot;", "\"").replace("&#39;", "'");
		Matcher nm = NUMERIC_ENTITY.matcher(t);
		StringBuilder sb = new StringBuilder();
		while (nm.find())
		{
			int cp = Integer.parseInt(nm.group(1));
			nm.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(cp))));
		}
		nm.appendTail(sb);
		// Normalise non-breaking spaces (from &nbsp; / &#160;) — Java's \s and trim() ignore them.
		return sb.toString().replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
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
			log.debug("Failed to cache drop page to {}", cacheFile, e);
		}
	}

	/** An {@code <h2>} heading: where its content starts (just after {@code </h2>}) and its cleaned title. */
	private static final class Heading2
	{
		private final int start;
		private final String title;

		private Heading2(int start, String title)
		{
			this.start = start;
			this.title = title;
		}
	}

	/** A merged heading/row event at a page position: exactly one of {@code heading} / {@code cells}. */
	private static final class Event
	{
		private final int pos;
		/** Heading depth (3 or 4) — what separates a location/level group from a drop table. 0 for a row. */
		private final int level;
		private final String heading;
		private final String[] cells;

		private Event(int pos, int level, String heading, String[] cells)
		{
			this.pos = pos;
			this.level = level;
			this.heading = heading;
			this.cells = cells;
		}
	}

	/** The {@code action=parse} response shape: {@code {"parse":{"text":{"*":"<html>"}}}}. */
	static final class ParseResponse
	{
		private Parse parse;
	}

	static final class Parse
	{
		private Text text;
	}

	static final class Text
	{
		@SerializedName("*")
		private String star;
	}
}
