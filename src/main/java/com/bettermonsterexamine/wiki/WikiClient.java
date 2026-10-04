package com.bettermonsterexamine.wiki;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
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
 */
public final class WikiClient
{
	private final OkHttpClient http;
	private final Gson gson;
	private final HttpUrl apiUrl;
	private final String userAgent;

	/**
	 * @param apiUrl    the wiki's {@code api.php}
	 * @param userAgent who is asking, with contact details; Weird Gloop and MediaWiki both ask for it
	 */
	public WikiClient(OkHttpClient http, Gson gson, String apiUrl, String userAgent)
	{
		this.http = http;
		this.gson = gson;
		this.apiUrl = HttpUrl.get(apiUrl);
		this.userAgent = userAgent;
	}

	/** A request to the API for {@code action}, already asking for JSON. */
	public HttpUrl.Builder action(String action)
	{
		return apiUrl.newBuilder()
			.addQueryParameter("action", action)
			.addQueryParameter("format", "json");
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
