package com.bettermonsterexamine.wiki;

import com.google.gson.Gson;
import java.time.Duration;
import okhttp3.OkHttpClient;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class WikiClientTest
{
	private static final String API = "https://wiki.example/api.php";

	@Test
	public void aSharedCacheAgeGoesFirstSoEveryClientSendsTheSameUrl()
	{
		WikiClient wiki = new WikiClient(new OkHttpClient(), new Gson(), API, "test", Duration.ofHours(1));

		assertEquals(API + "?action=parse&format=json&maxage=3600&smaxage=3600&page=Vardorvis",
			wiki.action("parse").addQueryParameter("page", "Vardorvis").build().toString());
	}

	@Test
	public void withoutASharedCacheAgeAnswersStayPrivate()
	{
		WikiClient wiki = new WikiClient(new OkHttpClient(), new Gson(), API, "test", null);

		assertEquals(API + "?action=query&format=json", wiki.action("query").build().toString());
	}
}
