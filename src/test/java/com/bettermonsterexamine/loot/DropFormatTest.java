package com.bettermonsterexamine.loot;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

/**
 * Locks {@link DropFormat}'s pure display shaping: rarity (Always / fraction / plain dash), the wiki
 * quantity string, comma-free compact coin values, and the GE/Alch caption line. Displayed numbers
 * drop thousands commas and en/em dashes become a plain hyphen.
 */
public class DropFormatTest
{
	private static DropRow row(String quantity, String rarity)
	{
		return new DropRow("Item", quantity, rarity, "Other");
	}

	@Test
	public void rarityShowsAlwaysFractionOrPlainDash()
	{
		assertEquals("Always", DropFormat.rarity(row("1", "Always")));
		assertEquals("1/128", DropFormat.rarity(row("1", "1/128")));
		// Thousands commas are dropped from the fraction.
		assertEquals("1/4096", DropFormat.rarity(row("1", "1/4,096")));
		assertEquals("-", DropFormat.rarity(row("1", "")));
		assertEquals("-", DropFormat.rarity(row("1", null)));
	}

	@Test
	public void quantityDropsCommasAndNormalisesDashes()
	{
		assertEquals("60 (noted)", DropFormat.quantity(row("60 (noted)", "1/128")));
		assertEquals("35-55", DropFormat.quantity(row(" 35–55 ", "1/128")));
		assertEquals("1000", DropFormat.quantity(row("1,000", "1/128")));
		assertEquals("", DropFormat.quantity(row("", "1/128")));
	}

	@Test
	public void priceIsCompactCommaFreeAndBlankForZero()
	{
		assertEquals("", DropFormat.price(0));
		assertEquals("450000", DropFormat.price(450_000));
		assertEquals("3.9M", DropFormat.price(3_900_000));
		assertEquals("2M", DropFormat.price(2_000_000));
		assertEquals("2.1B", DropFormat.price(2_100_000_000));
	}

	@Test
	public void priceLineShowsOnlyThePartsThatArePriced()
	{
		assertEquals("GE 480 · Alch 90", DropFormat.priceLine(480, 90));
		assertEquals("GE 3.9M", DropFormat.priceLine(3_900_000, 0));
		assertEquals("Alch 450000", DropFormat.priceLine(0, 450_000));
		assertEquals("", DropFormat.priceLine(0, 0));
	}
}
