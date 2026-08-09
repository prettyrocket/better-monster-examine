package com.bettermonsterexamine;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The inbound {@code displayMonster} contract. Everything here arrives from another plugin over an
 * untyped map, so the point of these is that a malformed request is ignored rather than thrown.
 */
public class MonsterLookupMessageTest
{
	private static Map<String, Object> map(Object... kv)
	{
		Map<String, Object> m = new HashMap<>();
		for (int i = 0; i < kv.length; i += 2)
		{
			m.put((String) kv[i], kv[i + 1]);
		}
		return m;
	}

	@Test
	public void nameOnlyIsEnough()
	{
		MonsterLookupMessage r = MonsterLookupMessage.of(map("name", "Hill Giant"));
		assertEquals("Hill Giant", r.getName());
		assertNull(r.getLevel());
		assertNull(r.getNpcId());
		assertFalse(r.isDrops());
	}

	@Test
	public void nameIsTrimmed()
	{
		assertEquals("Vorkath", MonsterLookupMessage.of(map("name", "  Vorkath \n")).getName());
	}

	@Test
	public void levelAndNpcIdAreRead()
	{
		MonsterLookupMessage r = MonsterLookupMessage.of(map("name", "Hellhound", "level", 122, "npcId", 104));
		assertEquals(Integer.valueOf(122), r.getLevel());
		assertEquals(Integer.valueOf(104), r.getNpcId());
	}

	/** Senders may box differently; a Long or Double shouldn't drop the value on the floor. */
	@Test
	public void anyNumberTypeIsAccepted()
	{
		MonsterLookupMessage r = MonsterLookupMessage.of(map("npcId", 104L, "level", 122.0d));
		assertEquals(Integer.valueOf(104), r.getNpcId());
		assertEquals(Integer.valueOf(122), r.getLevel());
	}

	@Test
	public void tabSelectsDrops()
	{
		assertTrue(MonsterLookupMessage.of(map("name", "Zulrah", "tab", "drops")).isDrops());
		assertTrue(MonsterLookupMessage.of(map("name", "Zulrah", "tab", " DROPS ")).isDrops());
	}

	@Test
	public void tabDefaultsToStats()
	{
		assertFalse(MonsterLookupMessage.of(map("name", "Zulrah")).isDrops());
		assertFalse(MonsterLookupMessage.of(map("name", "Zulrah", "tab", "stats")).isDrops());
		assertFalse(MonsterLookupMessage.of(map("name", "Zulrah", "tab", "nonsense")).isDrops());
	}

	@Test
	public void npcIdAloneIsEnough()
	{
		MonsterLookupMessage r = MonsterLookupMessage.of(map("npcId", 104));
		assertNull(r.getName());
		assertEquals(Integer.valueOf(104), r.getNpcId());
	}

	@Test
	public void nothingActionableIsRejected()
	{
		assertNull(MonsterLookupMessage.of(null));
		assertNull(MonsterLookupMessage.of(Collections.emptyMap()));
		assertNull(MonsterLookupMessage.of(map("name", "   ")));
		assertNull(MonsterLookupMessage.of(map("tab", "drops")));
	}

	/** A wrongly-typed value is treated as absent — never a ClassCastException on the event bus. */
	@Test
	public void wrongTypesAreIgnoredNotThrown()
	{
		assertNull(MonsterLookupMessage.of(map("name", 42)));
		MonsterLookupMessage r = MonsterLookupMessage.of(map("name", "Vorkath", "level", "392", "npcId", "8061", "tab", 7));
		assertEquals("Vorkath", r.getName());
		assertNull(r.getLevel());
		assertNull(r.getNpcId());
		assertFalse(r.isDrops());
	}

	@Test
	public void unknownKeysAreIgnored()
	{
		MonsterLookupMessage r = MonsterLookupMessage.of(map("name", "Vorkath", "somethingNew", true));
		assertEquals("Vorkath", r.getName());
	}
}
