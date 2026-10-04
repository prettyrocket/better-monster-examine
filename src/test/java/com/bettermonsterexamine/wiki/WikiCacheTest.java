package com.bettermonsterexamine.wiki;

import com.google.gson.Gson;
import java.io.File;
import java.time.Duration;
import okhttp3.OkHttpClient;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class WikiCacheTest
{
	@Rule
	public final TemporaryFolder dir = new TemporaryFolder();

	// Nothing listens on port 1, so any revision check fails fast.
	private static final WikiClient OFFLINE =
		new WikiClient(new OkHttpClient(), new Gson(), "http://127.0.0.1:1/api.php", "test", null);

	private WikiCache cache(Duration maxAge)
	{
		return new WikiCache(new File(dir.getRoot(), "sub/page.json"), maxAge);
	}

	@Test
	public void aMissingFileReadsAsNothingAndIsNotFresh() throws Exception
	{
		WikiCache c = cache(Duration.ofDays(7));

		assertNull(c.read());
		assertFalse(c.isFresh());
		assertEquals(0, c.revision());
	}

	@Test
	public void writesCreateTheFolderAndReadBack() throws Exception
	{
		WikiCache c = cache(Duration.ofDays(7));
		c.write("{\"a\":1}");

		assertEquals("{\"a\":1}", c.read());
		assertTrue(c.isFresh());
		assertFalse(cache(Duration.ZERO).isFresh());
	}

	@Test
	public void remembersTheRevisionAPlainWriteForgets() throws Exception
	{
		WikiCache c = cache(Duration.ofDays(7));
		c.write("v1", 15353449L);
		assertEquals(15353449L, c.revision());

		c.write("v2");
		assertEquals(0, c.revision());
	}

	@Test
	public void withoutARevisionThereIsNothingToRevalidate() throws Exception
	{
		WikiCache c = cache(Duration.ZERO);
		c.write("v1");

		assertFalse(c.revalidate(OFFLINE, "Vardorvis", Duration.ofDays(30)));
	}

	@Test
	public void pastTheBackstopItDownloadsWhateverTheRevision() throws Exception
	{
		WikiCache c = cache(Duration.ZERO);
		c.write("v1", 42L);

		assertFalse(c.revalidate(OFFLINE, "Vardorvis", Duration.ZERO));
	}

	@Test
	public void aFailedCheckMeansDownload() throws Exception
	{
		WikiCache c = cache(Duration.ZERO);
		c.write("v1", 42L);

		assertFalse(c.revalidate(OFFLINE, "Vardorvis", Duration.ofDays(30)));
		assertEquals("v1", c.read());
	}
}
