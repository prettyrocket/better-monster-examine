package com.bettermonsterexamine.wiki;

import com.google.gson.Gson;
import java.time.Duration;
import okhttp3.OkHttpClient;

/**
 * The OSRS Wiki endpoint every request goes to, and how we identify ourselves to it.
 *
 * <p>A descriptive User-Agent with contact details is the one thing the wiki asks of API users
 * (RS:Real-time Prices), so it can warn us of breaking changes or flag problem usage. RuneLite's
 * HTTP client prefixes its own {@code RuneLite/<version>} token, so the client version rides along.
 */
public final class WikiApi
{
	public static final String API_URL = "https://oldschool.runescape.wiki/api.php";
	public static final String USER_AGENT =
		"better-monster-examine (RuneLite plugin; https://github.com/prettyrocket/better-monster-examine)";

	/**
	 * How long the wiki's CDN may serve one answer to every user. An hour is invisible next
	 * to our own 7-day caches, but it turns a burst of identical requests (a popular monster, or the
	 * bestiary after everyone's weekly refresh) into one hit on the wiki's servers.
	 */
	private static final Duration SHARED_CACHE_AGE = Duration.ofHours(1);

	/**
	 * How long a page-backed cache may go on revalidating by revision id before it downloads in full
	 * anyway. Template edits don't change a page's revision, so this is what eventually picks up
	 * a change to a shared drop table.
	 */
	public static final Duration FULL_REFETCH = Duration.ofDays(30);

	/** A client for the OSRS Wiki that identifies as this plugin. */
	public static WikiClient client(OkHttpClient http, Gson gson)
	{
		return new WikiClient(http, gson, API_URL, USER_AGENT, SHARED_CACHE_AGE);
	}

	private WikiApi()
	{
	}
}
