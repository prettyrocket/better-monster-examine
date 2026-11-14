package com.bettermonsterexamine.wiki;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
		return new WikiCache(CacheStore.directory(dir.getRoot().toPath()), "sub/page.json", maxAge);
	}

	private File file(String name)
	{
		return new File(dir.getRoot(), "sub/" + name);
	}

	private static void write(File file, String text) throws Exception
	{
		Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
	}

	/** Runs {@code test} against a wiki whose every page is at {@code revision}. */
	private static void withWikiAt(long revision, WikiTest test) throws Exception
	{
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/api.php", exchange ->
		{
			byte[] body = ("{\"query\":{\"pages\":[{\"title\":\"Vardorvis\",\"lastrevid\":" + revision + "}]}}")
				.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		try
		{
			String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/api.php";
			test.run(new WikiClient(new OkHttpClient(), new Gson(), url, "test", null));
		}
		finally
		{
			server.stop(0);
		}
	}

	private interface WikiTest
	{
		void run(WikiClient wiki) throws Exception;
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

	@Test
	public void anUneditedPageResetsTheAgeWithoutTouchingTheFile() throws Exception
	{
		WikiCache c = cache(Duration.ofDays(7));
		c.write("v1", 42L);
		long day = Duration.ofDays(1).toMillis();
		long downloaded = System.currentTimeMillis() - 8 * day;
		write(file("page.json.rev"), "42 " + downloaded + " " + downloaded);
		assertFalse(c.isFresh());

		withWikiAt(42L, wiki -> assertTrue(c.revalidate(wiki, "Vardorvis", Duration.ofDays(30))));

		assertTrue(c.isFresh());
		assertEquals(42L, c.revision());
		assertEquals("v1", c.read());
		// The backstop still counts from the last full download, not the check.
		assertFalse(c.revalidate(OFFLINE, "Vardorvis", Duration.ofDays(8)));
	}

	@Test
	public void anEditedPageMeansDownload() throws Exception
	{
		WikiCache c = cache(Duration.ZERO);
		c.write("v1", 42L);

		withWikiAt(43L, wiki -> assertFalse(c.revalidate(wiki, "Vardorvis", Duration.ofDays(30))));
		assertFalse(c.isFresh());
	}

	@Test
	public void theSidecarsCheckTimeGovernsFreshnessNotTheFiles() throws Exception
	{
		WikiCache c = cache(Duration.ofDays(7));
		c.write("v1", 42L);
		long old = System.currentTimeMillis() - Duration.ofDays(8).toMillis();
		write(file("page.json.rev"), "42 " + old + " " + old);

		assertFalse(c.isFresh());
	}

	@Test
	public void anOlderTwoFieldSidecarTakesItsCheckTimeFromTheFile() throws Exception
	{
		WikiCache c = cache(Duration.ofDays(7));
		c.write("v1", 42L);
		long old = System.currentTimeMillis() - Duration.ofDays(8).toMillis();
		write(file("page.json.rev"), "42 " + old);
		assertTrue(c.isFresh());
		assertEquals(42L, c.revision());

		assertTrue(file("page.json").setLastModified(old));
		assertFalse(c.isFresh());
	}

	@Test
	public void aSidecarWithoutItsFileIsNotTrusted() throws Exception
	{
		WikiCache c = cache(Duration.ofDays(7));
		c.write("v1", 42L);
		assertTrue(file("page.json").delete());

		assertFalse(c.isFresh());
		assertEquals(0, c.revision());
	}
}
