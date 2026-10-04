package com.bettermonsterexamine.wiki;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;

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

	/** Replace the cached text, creating the folder if needed. */
	public void write(String text) throws IOException
	{
		File dir = file.getAbsoluteFile().getParentFile();
		if (dir != null)
		{
			Files.createDirectories(dir.toPath());
		}
		Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
	}

	public File getFile()
	{
		return file;
	}
}
