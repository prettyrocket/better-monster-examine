package com.bettermonsterexamine.wiki;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Collections;

/**
 * One text file cached on disk with a maximum age: the offline-first rule every wiki fetch follows.
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
 * instead of downloading again. A revision id only changes when the page itself is edited,
 * not when a template it uses is, so a file is still downloaded in full once it is older than
 * a backstop.
 */
public final class WikiCache
{
	private final File file;
	private final Duration maxAge;

	public WikiCache(File file, Duration maxAge)
	{
		this.file = file;
		this.maxAge = maxAge;
	}

	/** The cached text, or null when there is no file. */
	public String read() throws IOException
	{
		return file.isFile() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : null;
	}

	/** True when the file exists and is younger than the maximum age. */
	public boolean isFresh()
	{
		return file.isFile() && System.currentTimeMillis() - file.lastModified() < maxAge.toMillis();
	}

	/** Replace the cached text, creating the folder if needed. Forgets any remembered revision. */
	public void write(String text) throws IOException
	{
		writeText(text);
		Files.deleteIfExists(revisionFile().toPath());
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
		writeText(text);
		Files.write(revisionFile().toPath(),
			(revision + " " + System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
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
			return file.setLastModified(System.currentTimeMillis());
		}
		catch (IOException e)
		{
			return false;
		}
	}

	private void writeText(String text) throws IOException
	{
		File dir = file.getAbsoluteFile().getParentFile();
		if (dir != null)
		{
			Files.createDirectories(dir.toPath());
		}
		Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
	}

	/** {revision, time of the last full download}, or null when there is none to trust. */
	private long[] readRevision()
	{
		File rev = revisionFile();
		if (!rev.isFile() || !file.isFile())
		{
			return null;
		}
		try
		{
			String[] parts = new String(Files.readAllBytes(rev.toPath()), StandardCharsets.UTF_8).trim().split(" ");
			long[] out = {Long.parseLong(parts[0]), Long.parseLong(parts[1])};
			return out[0] > 0 ? out : null;
		}
		catch (IOException | RuntimeException e)
		{
			return null;
		}
	}

	private File revisionFile()
	{
		return new File(file.getPath() + ".rev");
	}

	public File getFile()
	{
		return file;
	}
}
