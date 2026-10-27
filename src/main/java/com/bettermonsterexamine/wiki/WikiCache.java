package com.bettermonsterexamine.wiki;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;

/**
 * One text file cached in a {@link CacheStore} with a maximum age: the offline-first rule every wiki fetch follows.
 *
 * <ul>
 * <li>A file younger than the maximum age is used as-is, with no request.</li>
 * <li>An older file is still used straight away, and the caller fetches a replacement behind it.
 * If that fetch fails, the old file keeps serving.</li>
 * <li>A fresh download is applied <b>before</b> it is {@link #write written}, so a response that
 * fails to parse never replaces a good file.</li>
 * </ul>
 *
 * <p>A file cached from one wiki page can also remember that page's <b>revision id</b>
 * ({@link #write(String, long)}). Then, once it ages out, {@link #revalidate} asks the wiki for the
 * page's current revision, about 1.5 KB, and if nobody has edited the page it just resets the age
 * instead of downloading again. The revision, the time of the last full download and the time of the
 * last check live in a {@code .rev} file beside the cache, so freshness never relies on setting a
 * file's modified time, which not every store can. A revision id only changes when the page itself is edited,
 * not when a template it uses is, so a file is still downloaded in full once it is older than
 * a backstop.
 */
public final class WikiCache
{
	private final CacheStore store;
	private final String name;
	private final Duration maxAge;

	public WikiCache(CacheStore store, String name, Duration maxAge)
	{
		this.store = store;
		this.name = name;
		this.maxAge = maxAge;
	}

	/** The cached text, or null when there is none. */
	public String read() throws IOException
	{
		return store.read(name);
	}

	/**
	 * True when the file exists and was last downloaded, or last revalidated, within the maximum age.
	 */
	public boolean isFresh()
	{
		long[] r = readRevision();
		long checked = r != null ? r[2] : modified();
		return checked > 0 && System.currentTimeMillis() - checked < maxAge.toMillis();
	}

	/** Replace the cached text, creating the folder if needed. Forgets any remembered revision. */
	public void write(String text) throws IOException
	{
		store.write(name, text);
		store.delete(revisionName());
	}

	/**
	 * Replace the cached text and remember the page revision it came from, restarting the backstop
	 * clock. A revision of 0 or less means unknown, which is the same as {@link #write(String)}.
	 */
	public void write(String text, long revision) throws IOException
	{
		if (revision <= 0)
		{
			write(text);
			return;
		}
		store.write(name, text);
		long now = System.currentTimeMillis();
		writeRevision(revision, now, now);
	}

	/** The revision of the page the cached text came from, or 0 when unknown. */
	public long revision()
	{
		long[] r = readRevision();
		return r == null ? 0 : r[0];
	}

	/**
	 * For a file past its age: true when the page {@code title} is still at the cached revision, in
	 * which case the age is reset and no download is needed. False, so download, when the revision is
	 * unknown, the last full download is older than {@code backstop}, the page has changed or is gone,
	 * or the check itself fails.
	 */
	public boolean revalidate(WikiClient wiki, String title, Duration backstop)
	{
		long[] r = readRevision();
		if (r == null || System.currentTimeMillis() - r[1] >= backstop.toMillis())
		{
			return false;
		}
		try
		{
			Long current = TitleResolver.lastRevisions(wiki, Collections.singletonList(title)).get(title);
			if (current == null || current != r[0])
			{
				return false;
			}
			writeRevision(r[0], r[1], System.currentTimeMillis());
			return true;
		}
		catch (IOException e)
		{
			return false;
		}
	}

	private void writeRevision(long revision, long downloaded, long checked) throws IOException
	{
		store.write(revisionName(), revision + " " + downloaded + " " + checked);
	}

	/**
	 * {revision, time of the last full download, time of the last check}, or null when there is none
	 * to trust. A sidecar written before the check time was kept has only two fields; its check time
	 * is the file's modified time, which is where it used to live.
	 */
	private long[] readRevision()
	{
		try
		{
			long modified = modified();
			String text = modified > 0 ? store.read(revisionName()) : null;
			if (text == null)
			{
				return null;
			}
			String[] parts = text.trim().split(" ");
			long[] out = {
				Long.parseLong(parts[0]),
				Long.parseLong(parts[1]),
				parts.length > 2 ? Long.parseLong(parts[2]) : modified,
			};
			return out[0] > 0 ? out : null;
		}
		catch (IOException | RuntimeException e)
		{
			return null;
		}
	}

	/** When the cached text was last written, or 0 when there is none. */
	private long modified()
	{
		try
		{
			return store.lastModified(name);
		}
		catch (IOException e)
		{
			return 0;
		}
	}

	private String revisionName()
	{
		return name + ".rev";
	}
}
