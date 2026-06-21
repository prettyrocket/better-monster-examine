package com.bettermonsterexamine;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Fetches a monster's OSRS Wiki page on demand (cached per monster) and parses its
 * infobox for the fields not present in the bundled Weirdgloop dataset. On-demand +
 * cached wiki access is consistent with accepted hub plugins (e.g. Loot Lookup).
 */
@Slf4j
@Singleton
public class WikiInfoboxService
{
	private static final Pattern PARAM = Pattern.compile("^\\|\\s*([^=|{}]+?)\\s*=\\s*(.*)$");
	private static final Pattern VERSION = Pattern.compile("^version(\\d+)$");
	private static final Pattern LINK = Pattern.compile("\\[\\[(?:[^\\]|]*\\|)?([^\\]]*)\\]\\]");
	private static final String USER_AGENT = "better-monster-examine (RuneLite plugin)";

	private final OkHttpClient http;
	private final Map<String, WikiInfo> cache = new ConcurrentHashMap<>();
	private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

	@Inject
	WikiInfoboxService(OkHttpClient http)
	{
		this.http = http;
	}

	public WikiInfo getCached(String name)
	{
		return cache.get(key(name));
	}

	/** Fetch + parse the monster's wiki infobox if not already cached; {@code onReady} runs once it lands. */
	public void fetch(String name, Runnable onReady)
	{
		String k = key(name);
		if (cache.containsKey(k) || !inFlight.add(k))
		{
			return;
		}

		HttpUrl url = HttpUrl.get("https://oldschool.runescape.wiki/w/" + name.replace(' ', '_'))
			.newBuilder()
			.addQueryParameter("action", "raw")
			.build();
		Request req = new Request.Builder()
			.url(url)
			.header("User-Agent", USER_AGENT)
			.build();

		log.debug("Fetching wiki infobox for {}", name);
		http.newCall(req).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				inFlight.remove(k);
				log.debug("Wiki infobox fetch failed for {}", name, e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (r.isSuccessful() && r.body() != null)
					{
						cache.put(k, parse(r.body().string()));
						log.debug("Cached wiki infobox for {}", name);
					}
				}
				catch (Exception e)
				{
					log.debug("Wiki infobox parse failed for {}", name, e);
				}
				finally
				{
					inFlight.remove(k);
					onReady.run();
				}
			}
		});
	}

	private static String key(String name)
	{
		return name.toLowerCase(Locale.ROOT);
	}

	static WikiInfo parse(String wikitext)
	{
		Map<String, String> params = new HashMap<>();
		Map<String, String> versionIdx = new HashMap<>();
		for (String line : wikitext.split("\\r?\\n"))
		{
			Matcher m = PARAM.matcher(line);
			if (!m.matches())
			{
				continue;
			}
			String keyName = m.group(1).toLowerCase(Locale.ROOT).trim();
			// first-wins: the monster infobox sits near the top, ahead of nav/other templates
			params.putIfAbsent(keyName, m.group(2).trim());

			Matcher vm = VERSION.matcher(keyName);
			if (vm.matches())
			{
				versionIdx.putIfAbsent(stripMarkup(m.group(2)).toLowerCase(Locale.ROOT).trim(), vm.group(1));
			}
		}
		return new WikiInfo(params, versionIdx);
	}

	/** Strip wiki link markup: {@code [[Magic]]} -> Magic, {@code [[a|b]]} -> b. */
	static String stripMarkup(String s)
	{
		if (s == null)
		{
			return null;
		}
		return LINK.matcher(s).replaceAll("$1").replace("[[", "").replace("]]", "").trim();
	}
}
