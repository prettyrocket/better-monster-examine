package com.bettermonsterexamine.wiki;

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

	private WikiApi()
	{
	}
}
