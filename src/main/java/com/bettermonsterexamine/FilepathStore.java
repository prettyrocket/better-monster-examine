package com.bettermonsterexamine;

import com.bettermonsterexamine.wiki.CacheStore;
import com.google.common.io.CharStreams;
import java.io.IOException;
import java.io.Reader;
import net.runelite.client.util.Filepath;

/** The wiki caches' store, kept inside the plugin's own data folder. */
final class FilepathStore implements CacheStore
{
	/** Holds nothing and refuses writes, so every load goes to the wiki. */
	static final CacheStore NONE = new CacheStore()
	{
		@Override
		public String read(String name)
		{
			return null;
		}

		@Override
		public void write(String name, String text) throws IOException
		{
			throw new IOException("No plugin data folder");
		}

		@Override
		public void delete(String name)
		{
		}

		@Override
		public long lastModified(String name)
		{
			return 0;
		}
	};

	private final Filepath dir;

	FilepathStore(Filepath dir)
	{
		this.dir = dir;
	}

	@Override
	public String read(String name) throws IOException
	{
		Filepath file = dir.join(name);
		if (!file.isFile())
		{
			return null;
		}
		try (Reader reader = file.openReader())
		{
			return CharStreams.toString(reader);
		}
	}

	@Override
	public void write(String name, String text) throws IOException
	{
		Filepath file = dir.join(name);
		file.getParent().createDirectories();
		file.write(text);
	}

	@Override
	public void delete(String name) throws IOException
	{
		dir.join(name).deleteIfExists();
	}

	@Override
	public long lastModified(String name) throws IOException
	{
		Filepath file = dir.join(name);
		return file.isFile() ? file.getLastModifiedTime().toMillis() : 0;
	}
}
