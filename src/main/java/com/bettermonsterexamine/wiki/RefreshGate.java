package com.bettermonsterexamine.wiki;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Lets a {@link WikiCache} refresh before its maximum age, when the caller has reason to think it is
 * out of date: a lookup found nothing, so the game may have content the cache predates.
 *
 * <p>One refresh runs at a time, and none starts within the interval of the cache's last download or
 * the last attempt. A key that still misses after a download has landed is settled and never asks
 * again, so a lookup that will never succeed (a typo, an NPC the wiki doesn't carry) costs at most one
 * refresh, and only new keys can ask for another. The natural interval is
 * {@link WikiApi#SHARED_CACHE_AGE}: the CDN gives a repeated request the same answer for that long,
 * so refreshing sooner can't find anything new.
 */
public final class RefreshGate
{
	private final WikiCache cache;
	private final long intervalMillis;
	private final LongSupplier clock;
	private long last;
	private boolean running;
	/** Keys that missed since the last refresh started. */
	private final Set<String> missed = new HashSet<>();
	/** Keys the running refresh was started for. */
	private Set<String> inFlight = new HashSet<>();
	/** Keys that missed before a download that landed, so another refresh won't help them. */
	private final Set<String> settled = new HashSet<>();

	public RefreshGate(WikiCache cache, Duration interval)
	{
		this(cache, interval, System::currentTimeMillis);
	}

	RefreshGate(WikiCache cache, Duration interval, LongSupplier clock)
	{
		this.cache = cache;
		this.intervalMillis = interval.toMillis();
		this.clock = clock;
	}

	/**
	 * True when a refresh may start now for a lookup of {@code key} that missed, which the caller must
	 * then {@link #finish}. Cheap enough to call on every lookup: the disk is only read once the
	 * in-memory checks pass.
	 */
	public synchronized boolean tryStart(String key)
	{
		if (settled.contains(key))
		{
			return false;
		}
		missed.add(key);
		long now = clock.getAsLong();
		if (running || now - last < intervalMillis)
		{
			return false;
		}
		last = Math.max(last, cache.lastWritten());
		if (now - last < intervalMillis)
		{
			return false;
		}
		begin(now);
		return true;
	}

	/** Start a refresh whatever the interval, for a caller that has to download anyway. */
	public synchronized void start()
	{
		begin(clock.getAsLong());
	}

	/** End the running refresh; {@code landed} when it downloaded and published new data. */
	public synchronized void finish(boolean landed)
	{
		if (landed)
		{
			settled.addAll(inFlight);
		}
		else
		{
			missed.addAll(inFlight);
		}
		inFlight = new HashSet<>();
		running = false;
	}

	private void begin(long now)
	{
		running = true;
		last = now;
		inFlight = new HashSet<>(missed);
		missed.clear();
	}
}
