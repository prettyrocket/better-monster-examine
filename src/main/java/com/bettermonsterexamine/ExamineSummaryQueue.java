package com.bettermonsterexamine;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Correlates native NPC Examine responses with summary snapshots captured when each NPC was
 * clicked. It also recognises the messages it asks RuneLite to inject: those use the same
 * {@code NPC_EXAMINE} type and must not consume the next native response.
 */
final class ExamineSummaryQueue
{
	private static final int MAX_PENDING_AGE_TICKS = 5;
	private static final int MAX_INJECTED_MESSAGES = 32;
	private final Deque<PendingSummary> pending = new ArrayDeque<>();
	private final Deque<String> injected = new ArrayDeque<>();

	void add(List<String> summary, int tick)
	{
		discardExpired(tick);
		pending.addLast(new PendingSummary(summary, tick));
	}

	/**
	 * Consume one NPC_EXAMINE message. Returns the block to inject after a native response, or null
	 * when this is one of our own messages, nothing is pending, or the clicked NPC was unknown.
	 */
	String onNpcExamine(String message, int tick)
	{
		if (injected.removeFirstOccurrence(message))
		{
			return null;
		}

		discardExpired(tick);
		PendingSummary next = pending.pollFirst();
		if (next == null || next.lines.isEmpty())
		{
			return null;
		}

		String block = String.join("<br>", next.lines);
		if (injected.size() >= MAX_INJECTED_MESSAGES)
		{
			// RuneLite deliberately drops queued chat on Tutorial Island; bound markers that never
			// get their matching event so repeated examines there cannot grow this deque forever.
			injected.removeFirst();
		}
		injected.addLast(block);
		return block;
	}

	/** A consumed/dropped click must not shift every later Examine response indefinitely. */
	private void discardExpired(int tick)
	{
		while (!pending.isEmpty() && tick - pending.peekFirst().tick > MAX_PENDING_AGE_TICKS)
		{
			pending.removeFirst();
		}
	}

	void clearPending()
	{
		pending.clear();
	}

	void clear()
	{
		pending.clear();
		injected.clear();
	}

	private static final class PendingSummary
	{
		private final List<String> lines;
		private final int tick;

		private PendingSummary(List<String> lines, int tick)
		{
			this.lines = lines;
			this.tick = tick;
		}
	}
}
