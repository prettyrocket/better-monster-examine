package com.bettermonsterexamine.loot;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** Locks {@link DropRow#isAlways()}, the one behavioural bit on the otherwise-plain page-row DTO. */
public class DropRowTest
{
	@Test
	public void isAlwaysMatchesTheAlwaysRarityCaseInsensitively()
	{
		assertTrue(new DropRow("Bones", "1", "Always", "", "100%").isAlways());
		assertTrue(new DropRow("Bones", "1", "always", "", "100%").isAlways());
		assertFalse(new DropRow("Coins", "5", "1/128", "", "Other").isAlways());
		assertFalse(new DropRow("Coins", "5", null, "", "Other").isAlways());
	}
}
