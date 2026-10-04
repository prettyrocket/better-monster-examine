package com.bettermonsterexamine.wiki;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class TitleResolverTest
{
	private static JsonObject query(String json)
	{
		return new Gson().fromJson(json, JsonObject.class);
	}

	@Test
	public void followsNormalisationThenRedirect()
	{
		// The shape action=query&redirects=1 returns for a lower-case redirect title.
		TitleResolver r = new TitleResolver();
		r.addAll(query("{\"normalized\":[{\"from\":\"rock slug\",\"to\":\"Rock slug\"}],"
			+ "\"redirects\":[{\"from\":\"Rock slug\",\"to\":\"Rockslug\"}]}"));

		assertEquals("Rockslug", r.resolve("rock slug"));
		assertEquals("Rockslug", r.resolve("ROCK SLUG"));
	}

	@Test
	public void aTitleNothingRedirectedIsItsOwnAnswer()
	{
		TitleResolver r = new TitleResolver();
		r.addAll(query("{\"pages\":[]}"));

		assertEquals("Vardorvis", r.resolve("Vardorvis"));
	}

	@Test
	public void aRedirectLoopStopsInsteadOfHanging()
	{
		TitleResolver r = new TitleResolver();
		r.add("A", "B");
		r.add("B", "A");

		// Four hops from A lands back on A; what matters is that it returns.
		assertEquals("A", r.resolve("A"));
	}

	@Test
	public void batchesAtTheTitleCap()
	{
		List<Integer> titles = new ArrayList<>();
		for (int i = 0; i < 120; i++)
		{
			titles.add(i);
		}

		List<List<Integer>> batches = TitleResolver.batches(titles);

		assertEquals(3, batches.size());
		assertEquals(50, batches.get(0).size());
		assertEquals(20, batches.get(2).size());
		assertEquals(Integer.valueOf(100), batches.get(2).get(0));
	}
}
