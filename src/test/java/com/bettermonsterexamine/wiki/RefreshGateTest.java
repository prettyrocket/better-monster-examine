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
		return new WikiCache(CacheStore.directory(dir.getRoot().toPath()), "data.json", Duration.ofDays(7));
	}

	private RefreshGate gate(WikiCache cache)
	{
		return new RefreshGate(cache, HOUR, () -> now);
	}

	@Test
	public void aMissingCacheMayRefreshAtOnce()
	{
		assertTrue(gate(cache()).tryStart("a"));
	}

	@Test
	public void onlyOneRefreshRunsAtATime()
	{
		RefreshGate g = gate(cache());
		assertTrue(g.tryStart("a"));

		now += HOUR.toMillis() * 2;
		assertFalse(g.tryStart("a"));

		g.finish(false);
		assertTrue(g.tryStart("a"));
	}

	@Test
	public void anAttemptHoldsOffTheNextForTheInterval()
	{
		RefreshGate g = gate(cache());
		assertTrue(g.tryStart("a"));
		g.finish(false);

		now += HOUR.toMillis() - 1;
		assertFalse(g.tryStart("a"));

		now += 1;
		assertTrue(g.tryStart("a"));
	}

	@Test
	public void aRecentDownloadCountsAsAnAttempt() throws Exception
	{
		WikiCache c = cache();
		c.write("{}");
		assertTrue(new File(dir.getRoot(), "data.json").setLastModified(T0 - HOUR.toMillis() / 2));
		RefreshGate g = gate(c);

		assertFalse(g.tryStart("a"));

		now = T0 + HOUR.toMillis() / 2;
		assertTrue(g.tryStart("a"));
	}

	@Test
	public void aForcedStartBlocksAndHoldsOffMisses()
	{
		RefreshGate g = gate(cache());
		g.start();
		assertFalse(g.tryStart("a"));

		g.finish(false);
		assertFalse(g.tryStart("a"));

		now += HOUR.toMillis();
		assertTrue(g.tryStart("a"));
	}

	@Test
	public void aKeyStillMissingAfterALandedDownloadNeverAsksAgain()
	{
		RefreshGate g = gate(cache());
		assertTrue(g.tryStart("a"));
		g.finish(true);

		now += HOUR.toMillis() * 2;
		assertFalse(g.tryStart("a"));
		assertTrue(g.tryStart("b"));
	}

	@Test
	public void aFailedDownloadLeavesItsKeysFreeToRetry()
	{
		RefreshGate g = gate(cache());
		assertTrue(g.tryStart("a"));
		g.finish(false);

		now += HOUR.toMillis();
		assertTrue(g.tryStart("a"));
	}

	@Test
	public void aKeyThatMissedWhileThrottledIsSettledByTheNextRefresh()
	{
		RefreshGate g = gate(cache());
		assertTrue(g.tryStart("a"));
		g.finish(true);

		assertFalse(g.tryStart("b"));
		now += HOUR.toMillis();
		assertTrue(g.tryStart("c"));
		g.finish(true);

		now += HOUR.toMillis();
		assertFalse(g.tryStart("b"));
		assertFalse(g.tryStart("c"));
	}
}
