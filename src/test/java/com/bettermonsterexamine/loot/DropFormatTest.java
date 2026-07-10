package com.bettermonsterexamine.loot;

import com.google.gson.Gson;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

/**
 * Locks {@link DropFormat}'s pure display shaping: rarity (Always / raw fraction / em dash), the
 * quantity display or low–high fallback, compact coin values, and the GE/Alch caption line.
 */
public class DropFormatTest
{
	private static final Gson GSON = new Gson();

	private static DropRow row(String dropJson)
	{
		return GSON.fromJson(dropJson, DropRow.class);
	}

	@Test
	public void rarityShowsAlwaysRawFractionOrEmDash()
	{
		assertEquals("Always", DropFormat.rarity(row("{\"Rarity\":\"Always\"}")));
		assertEquals("1/128", DropFormat.rarity(row("{\"Rarity\":\"1/128\"}")));
		assertEquals("—", DropFormat.rarity(row("{\"Dropped item\":\"x\"}")));
	}

	@Test
	public void quantityPrefersDisplayThenFallsBackToBounds()
	{
		assertEquals("60 (noted)", DropFormat.quantity(row("{\"Drop Quantity\":\"60 (noted)\"}")));
		assertEquals("35–55", DropFormat.quantity(row("{\"Quantity Low\":35,\"Quantity High\":55}")));
		assertEquals("2", DropFormat.quantity(row("{\"Quantity Low\":2,\"Quantity High\":2}")));
		assertEquals("", DropFormat.quantity(row("{\"Dropped item\":\"x\"}")));
	}

	@Test
	public void priceIsCompactAndBlankForZero()
	{
		assertEquals("", DropFormat.price(0));
		assertEquals("450,000", DropFormat.price(450_000));
		assertEquals("3.9M", DropFormat.price(3_900_000));
		assertEquals("2M", DropFormat.price(2_000_000));
		assertEquals("2.1B", DropFormat.price(2_100_000_000));
	}

	@Test
	public void priceLineShowsOnlyThePartsThatArePriced()
	{
		assertEquals("GE 480 · Alch 90", DropFormat.priceLine(480, 90));
		assertEquals("GE 3.9M", DropFormat.priceLine(3_900_000, 0));
		assertEquals("Alch 450,000", DropFormat.priceLine(0, 450_000));
		assertEquals("", DropFormat.priceLine(0, 0));
	}
}
