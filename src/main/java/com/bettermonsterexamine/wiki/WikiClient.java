package com.bettermonsterexamine.wiki;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.time.Duration;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Blocking GETs against one MediaWiki {@code api.php}, as JSON.
 *
 * <p>Every call blocks, so run it on a background executor, never a UI or game thread. Sharing one
 * single-threaded executor across callers keeps requests in series, which is what MediaWiki's API
 * etiquette asks for. Failures (network, non-2xx, empty body) all surface as {@link IOException},
 * so a caller can keep serving its cache.
 *
 * <p>Given a shared cache age, every request asks the wiki's CDN to keep its answer public for that
 * long ({@code maxage}/{@code smaxage}). By default {@code api.php} answers are private, so each
 * client's request reaches the wiki's servers. With the age set, the first client to ask fills the
 * CDN, and everyone sending the same URL is served from it. The CDN matches the exact query string,
 * so these parameters always go first, before anything a caller adds.
 */
public final class WikiClient
{
	private final OkHttpClient http;
	private final Gson gson;
	private final HttpUrl apiUrl;
	private final String userAgent;
	private final String sharedCacheSeconds;

	/**
	 * @param apiUrl    the wiki's {@code api.php}
	 * @param userAgent      who is asking, with contact details; Weird Gloop and MediaWiki both ask for it
	 * @param sharedCacheAge how long the wiki's CDN may serve an answer to everyone, or null for never
	 */
	public WikiClient(OkHttpClient http, Gson gson, String apiUrl, String userAgent, Duration sharedCacheAge)
	{
		this.http = http;
		this.gson = gson;
		this.apiUrl = HttpUrl.get(apiUrl);
		this.userAgent = userAgent;
		this.sharedCacheSeconds = sharedCacheAge == null || sharedCacheAge.isZero()
			? null : String.valueOf(sharedCacheAge.getSeconds());
	}

	/** A request to the API for {@code action}, already asking for JSON. */
	public HttpUrl.Builder action(String action)
	{
		HttpUrl.Builder url = apiUrl.newBuilder()
			.addQueryParameter("action", action)
			.addQueryParameter("format", "json");
		if (sharedCacheSeconds != null)
		{
			url.addQueryParameter("maxage", sharedCacheSeconds)
				.addQueryParameter("smaxage", sharedCacheSeconds);
		}
		return url;
	}

	/** GET {@code url} and return the body as text. */
	public String fetch(HttpUrl url) throws IOException
	{
		Request req = new Request.Builder().url(url).header("User-Agent", userAgent).build();
		try (Response res = http.newCall(req).execute())
		{
			if (!res.isSuccessful() || res.body() == null)
			{
				throw new IOException("HTTP " + res.code() + " from " + url.queryParameter("action"));
			}
			return res.body().string();
		}
	}

	/** GET {@code url} and parse the body as a JSON object. */
	public JsonObject fetchJson(HttpUrl url) throws IOException
	{
		JsonObject body = gson.fromJson(fetch(url), JsonObject.class);
		if (body == null)
		{
			throw new IOException("Empty response from " + url.queryParameter("action"));
		}
		return body;
	}
}
