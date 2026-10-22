package com.bettermonsterexamine.wiki;

import java.io.File;
import java.time.Duration;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RefreshGateTest
{
	private static final Duration HOUR = Duration.ofHours(1);
	private static final long T0 = 1_700_000_000_000L;

	@Rule
	public final TemporaryFolder dir = new TemporaryFolder();

	private long now = T0;

	private WikiCache cache()
	{
		return new WikiCache(new File(dir.getRoot(), "data.json"), Duration.ofDays(7));
	}

	private RefreshGate gate(WikiCache cache)
	{
		return new RefreshGate(cache, HOUR, () -> now);
	}

	@Test
	public void aMissingCacheMayRefreshAtOnce()
	{
		assertTrue(gate(cache()).tryStart());
	}

	@Test
	public void onlyOneRefreshRunsAtATime()
	{
		RefreshGate g = gate(cache());
		assertTrue(g.tryStart());

		now += HOUR.toMillis() * 2;
		assertFalse(g.tryStart());

		g.finish();
		assertTrue(g.tryStart());
	}

	@Test
	public void anAttemptHoldsOffTheNextForTheInterval()
	{
		RefreshGate g = gate(cache());
		assertTrue(g.tryStart());
		g.finish();

		now += HOUR.toMillis() - 1;
		assertFalse(g.tryStart());

		now += 1;
		assertTrue(g.tryStart());
	}

	@Test
	public void aRecentDownloadCountsAsAnAttempt() throws Exception
	{
		WikiCache c = cache();
		c.write("{}");
		assertTrue(c.getFile().setLastModified(T0 - HOUR.toMillis() / 2));
		RefreshGate g = gate(c);

		assertFalse(g.tryStart());

		now = T0 + HOUR.toMillis() / 2;
		assertTrue(g.tryStart());
	}

	@Test
	public void aForcedStartBlocksAndHoldsOffMisses()
	{
		RefreshGate g = gate(cache());
		g.start();
		assertFalse(g.tryStart());

		g.finish();
		assertFalse(g.tryStart());

		now += HOUR.toMillis();
		assertTrue(g.tryStart());
	}
}
