package com.bettermonsterexamine.wiki;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where a {@link WikiCache} keeps its files: text stored under a relative name such as
 * {@code "droppages/vorkath.json"}. An application supplies one rooted in its own data folder, so
 * this package needs no file API of its own beyond the JDK's {@link #directory} default.
 */
public interface CacheStore
{
	/** The text stored under {@code name}, or null when there is none. */
	String read(String name) throws IOException;

	/** Store {@code text} under {@code name}, replacing what was there and creating folders as needed. */
	void write(String name, String text) throws IOException;

	/** Remove {@code name}; nothing happens when it isn't there. */
	void delete(String name) throws IOException;

	/** When {@code name} was last written, in epoch milliseconds, or 0 when there is none. */
	long lastModified(String name) throws IOException;

	/** A store over a plain folder on disk. */
	static CacheStore directory(Path dir)
	{
		return new CacheStore()
		{
			@Override
			public String read(String name) throws IOException
			{
				Path file = dir.resolve(name);
				return Files.isRegularFile(file) ? new String(Files.readAllBytes(file), StandardCharsets.UTF_8) : null;
			}

			@Override
			public void write(String name, String text) throws IOException
			{
				Path file = dir.resolve(name);
				Files.createDirectories(file.toAbsolutePath().getParent());
				Files.write(file, text.getBytes(StandardCharsets.UTF_8));
			}

			@Override
			public void delete(String name) throws IOException
			{
				Files.deleteIfExists(dir.resolve(name));
			}

			@Override
			public long lastModified(String name) throws IOException
			{
				Path file = dir.resolve(name);
				return Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : 0;
			}
		};
	}
}
