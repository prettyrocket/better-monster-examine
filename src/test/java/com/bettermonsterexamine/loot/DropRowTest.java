package com.bettermonsterexamine.loot;

import com.google.gson.Gson;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Locks the {@code drop_json} parsing shapes observed on the live OSRS Wiki Bucket {@code dropsline}
 * dataset (Vorkath, Abyssal demon, Green dragon), mirroring {@code WikiSanitizerTest}: {@code Always}
 * vs fractional rarity, quantity ranges, {@code (noted)} quantities, and comma-grouped denominators.
 */
public class DropRowTest
{
	private static final Gson GSON = new Gson();

	private static DropRow parse(String dropJson)
	{
		return GSON.fromJson(dropJson, DropRow.class);
	}

	@Test
	public void alwaysDropParsesAsAlwaysWithNoFraction()
	{
		// Vorkath: Superior dragon bones — always dropped, fixed quantity 2.
		DropRow row = parse("{\"Rarity\":\"Always\",\"Drop Quantity\":\"2\",\"Quantity Low\":2,"
			+ "\"Quantity High\":2,\"Rolls\":1,\"Drop type\":\"combat\",\"Dropped item\":\"Superior dragon bones\"}");

		assertEquals("Superior dragon bones", row.getItem());
		assertTrue(row.isAlways());
		assertNull(row.getRarityFraction());
		assertEquals(2, row.getQuantityLow());
		assertEquals(2, row.getQuantityHigh());
		assertEquals("combat", row.getDropType());
		assertFalse(row.isNoted());
	}

	@Test
	public void fractionalRarityParsesToProbability()
	{
		// Vorkath: Rune longsword — 5/150 over a 2–3 quantity range, 2 rolls.
		DropRow row = parse("{\"Rarity\":\"5/150\",\"Drop Quantity\":\"2–3\",\"Quantity Low\":2,"
			+ "\"Quantity High\":3,\"Rolls\":2,\"Drop type\":\"combat\",\"Dropped item\":\"Rune longsword\"}");

		assertFalse(row.isAlways());
		assertEquals(5.0 / 150.0, row.getRarityFraction(), 1e-9);
		assertEquals(2, row.getQuantityLow());
		assertEquals(3, row.getQuantityHigh());
		assertEquals(2, row.getRolls());
	}

	@Test
	public void notedQuantityIsDetectedFromDisplayOnly()
	{
		// Abyssal demon: Pure essence — "60 (noted)" display; Low/High stay plain ints.
		DropRow row = parse("{\"Rarity\":\"5/128\",\"Drop Quantity\":\"60 (noted)\",\"Quantity Low\":60,"
			+ "\"Quantity High\":60,\"Rolls\":1,\"Drop type\":\"combat\",\"Dropped item\":\"Pure essence\"}");

		assertTrue(row.isNoted());
		assertEquals(60, row.getQuantityLow());
		assertEquals("60 (noted)", row.getQuantityDisplay());
	}

	@Test
	public void commaGroupedDenominatorIsTolerated()
	{
		// Abyssal demon: Silver ore — "1/4,096".
		DropRow row = parse("{\"Rarity\":\"1/4,096\",\"Drop Quantity\":\"100 (noted)\",\"Quantity Low\":100,"
			+ "\"Quantity High\":100,\"Rolls\":1,\"Drop type\":\"combat\",\"Dropped item\":\"Silver ore\"}");

		assertEquals(1.0 / 4096.0, row.getRarityFraction(), 1e-9);
	}

	@Test
	public void rareDropTableFlagFollowsSentinelPresence()
	{
		DropRow row = parse("{\"Rarity\":\"1/128\",\"Dropped item\":\"Coins\"}");

		// Absent by default (a monster's own table); presence of the empty-string sentinel flips it.
		assertFalse(row.isRareDropTable());
		row.setRareDropTable("");
		assertTrue(row.isRareDropTable());
	}

	@Test
	public void malformedAndMissingRaritiesYieldNoFraction()
	{
		assertNull(DropRow.parseFraction(null));
		assertNull(DropRow.parseFraction("Always"));
		assertNull(DropRow.parseFraction("~"));
		assertNull(DropRow.parseFraction("1/0"));
	}

	@Test
	public void variantIsTheTextAfterTheHashInDroppedFrom()
	{
		// Vorkath's post-quest table row carries "Dropped from":"Vorkath#Post-quest".
		DropRow variant = parse("{\"Dropped item\":\"Coins\",\"Dropped from\":\"Vorkath#Post-quest\"}");
		assertEquals("Post-quest", variant.variant());

		// A single-variant page has no '#', so the variant is empty.
		DropRow plain = parse("{\"Dropped item\":\"Coins\",\"Dropped from\":\"Goblin\"}");
		assertEquals("", plain.variant());

		// A missing "Dropped from" is also the empty (single) variant.
		assertEquals("", parse("{\"Dropped item\":\"Coins\"}").variant());
	}

	@Test
	public void rarityProbabilityHandlesAlwaysTildeAndCommas()
	{
		assertEquals(1.0, DropRow.probabilityOf("Always"), 1e-9);
		assertEquals(1.0 / 128.0, DropRow.probabilityOf("~1/128"), 1e-9);
		assertEquals(1.0 / 4096.0, DropRow.probabilityOf("1/4,096"), 1e-9);
		// "Varies"/blank/malformed sort to the bottom, not the top.
		assertEquals(-1.0, DropRow.probabilityOf("Varies"), 1e-9);
		assertEquals(-1.0, DropRow.probabilityOf(null), 1e-9);
		assertEquals(-1.0, DropRow.probabilityOf("1/0"), 1e-9);
	}

	@Test
	public void sectionFollowsBucketFlagsDeterministically()
	{
		DropRow always = parse("{\"Rarity\":\"Always\",\"Dropped item\":\"Big bones\"}");
		assertEquals(DropTable.SECTION_HUNDRED, always.section());

		DropRow other = parse("{\"Rarity\":\"1/128\",\"Dropped item\":\"Coins\"}");
		assertEquals(DropTable.SECTION_OTHER, other.section());

		DropRow rdt = parse("{\"Rarity\":\"1/512\",\"Dropped item\":\"Rune spear\"}");
		rdt.setRareDropTable("");
		assertEquals(DropTable.SECTION_RARE_DROP_TABLE, rdt.section());

		// "Always" wins over the rare-drop-table flag (the 100% section is checked first).
		DropRow alwaysAndFlagged = parse("{\"Rarity\":\"Always\",\"Dropped item\":\"Ashes\"}");
		alwaysAndFlagged.setRareDropTable("");
		assertEquals(DropTable.SECTION_HUNDRED, alwaysAndFlagged.section());
	}
}
