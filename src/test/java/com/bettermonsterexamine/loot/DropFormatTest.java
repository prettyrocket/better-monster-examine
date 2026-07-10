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

	@Test
	public void tierOfMapsProbabilityToRarityBands()
	{
		// Always (100%) and unknown/unparseable rarities are common.
		assertEquals(RarityTier.COMMON, DropFormat.tierOf("Always"));
		assertEquals(RarityTier.COMMON, DropFormat.tierOf("-"));
		assertEquals(RarityTier.COMMON, DropFormat.tierOf(null));
		// 1/20 is above the 1/50 threshold -> common; 1/128 -> uncommon.
		assertEquals(RarityTier.COMMON, DropFormat.tierOf("1/20"));
		assertEquals(RarityTier.UNCOMMON, DropFormat.tierOf("1/128"));
		assertEquals(RarityTier.RARE, DropFormat.tierOf("1/1000"));
		// A leading ~ and thousands commas are tolerated; 1/13000 -> ultra rare.
		assertEquals(RarityTier.ULTRA_RARE, DropFormat.tierOf("~1/13,000"));
		// A multi-roll numerator counts: 5/128 ~ 1/26 -> common.
		assertEquals(RarityTier.COMMON, DropFormat.tierOf("5/128"));
	}

	@Test
	public void handlesMultiRollAndCombinedRarityCells()
	{
		// Vorkath's rune javelin cell (× is U+00D7): a combined per-kill rate after ';' wins.
		String rune = "2 × 1/24,576 ; 1/12,480";
		assertEquals("1/12480", DropFormat.rarity(new DropRow("Item", "50", rune, "Other")));
		assertEquals(RarityTier.ULTRA_RARE, DropFormat.tierOf(rune));

		// Adamant javelin: "N × 1/M" with no combined value -> N/M for the tier (~2/1920 = rare).
		assertEquals(RarityTier.RARE, DropFormat.tierOf("2 × 1/1,920"));
	}
}
