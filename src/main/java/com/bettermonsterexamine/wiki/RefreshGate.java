package com.bettermonsterexamine.wiki;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Lets a {@link WikiCache} refresh before its maximum age, when the caller has reason to think it is
 * out of date: a lookup found nothing, so the game may have content the cache predates.
 *
 * <p>One refresh runs at a time, and none starts within the interval of the cache's last download or
 * the last attempt. A lookup that will never succeed (a typo, a name the wiki doesn't carry) therefore
 * costs one refresh per interval, not one per lookup. The natural interval is
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
	 * True when a refresh may start now, which the caller must then {@link #finish}. Cheap enough to
	 * call on every lookup: the disk is only read once the in-memory check passes.
	 */
	public synchronized boolean tryStart()
	{
		long now = clock.getAsLong();
		if (running || now - last < intervalMillis)
		{
			return false;
		}
		last = Math.max(last, cache.getFile().lastModified());
		if (now - last < intervalMillis)
		{
			return false;
		}
		running = true;
		last = now;
		return true;
	}

	/** Start a refresh whatever the interval, for a caller that has to download anyway. */
	public synchronized void start()
	{
		running = true;
		last = clock.getAsLong();
	}

	public synchronized void finish()
	{
		running = false;
	}
}
