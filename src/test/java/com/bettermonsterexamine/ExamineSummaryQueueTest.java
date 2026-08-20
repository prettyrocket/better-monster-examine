package com.bettermonsterexamine;

import java.util.Collections;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class ExamineSummaryQueueTest
{
	@Test
	public void nativeResponseReturnsOneCompactBlockOnce()
	{
		ExamineSummaryQueue queue = new ExamineSummaryQueue();
		queue.add(List.of("Melee", "Ranged", "Element"), 100);

		assertEquals("Melee<br>Ranged<br>Element", queue.onNpcExamine("Vanilla examine text.", 101));
		assertNull(queue.onNpcExamine("Another native message.", 101));
	}

	@Test
	public void injectedBlockCannotConsumeTheNextNativeResponse()
	{
		ExamineSummaryQueue queue = new ExamineSummaryQueue();
		queue.add(List.of("<colHIGHLIGHT>Examined A stats:<colNORMAL>",
			"<col=ff4040>Melee:</col> Crush (+0)"), 100);
		String first = queue.onNpcExamine("Vanilla A", 100);
		queue.add(List.of("Summary B"), 101);

		assertNull(queue.onNpcExamine(first, 101));
		assertEquals("Summary B", queue.onNpcExamine("Vanilla B", 102));
	}

	@Test
	public void unknownMonsterKeepsRapidResponsesAligned()
	{
		ExamineSummaryQueue queue = new ExamineSummaryQueue();
		queue.add(Collections.emptyList(), 100);
		queue.add(List.of("Known summary"), 100);

		assertNull(queue.onNpcExamine("Unknown monster's text.", 100));
		assertEquals("Known summary", queue.onNpcExamine("Known monster's text.", 101));
	}

	@Test
	public void clearingPendingPreventsAStaleResponse()
	{
		ExamineSummaryQueue queue = new ExamineSummaryQueue();
		queue.add(List.of("Stale summary"), 100);

		queue.clearPending();

		assertNull(queue.onNpcExamine("Later response", 101));
	}

	@Test
	public void missingResponseExpiresBeforeTheNextExamine()
	{
		ExamineSummaryQueue queue = new ExamineSummaryQueue();
		queue.add(List.of("Missing summary"), 100);

		queue.add(List.of("Current summary"), 106);

		assertEquals("Current summary", queue.onNpcExamine("Current vanilla response", 106));
	}
}
