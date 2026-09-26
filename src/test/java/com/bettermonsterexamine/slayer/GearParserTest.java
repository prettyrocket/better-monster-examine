package com.bettermonsterexamine.slayer;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Exercises {@link GearParser}: items read from the wiki's {@code {{plink}}} wikitext, slots in
 * paper-doll order, qualifiers kept, noise dropped, and setups ordered best source first.
 */
public class GearParserTest
{
	private static final Gson GSON = new Gson();

	private static String plink(String image, String link, String label)
	{
		return "<span class=\"plink-template\">[[File:" + image + ".png|link=" + link + "]]</span>[["
			+ link + (label == null ? "" : "|" + label) + "]]";
	}

	private static JsonObject row(String page, String style, String slot, String... options)
	{
		JsonArray arr = new JsonArray();
		for (String o : options)
		{
			arr.add(o);
		}
		JsonObject equipment = new JsonObject();
		equipment.add(slot, arr);
		JsonObject blob = new JsonObject();
		blob.add("Recommended Equipment", equipment);
		if (style != null)
		{
			blob.addProperty("style", style);
		}
		JsonObject r = new JsonObject();
		r.addProperty("page_name", page);
		r.addProperty("json", blob.toString());
		return r;
	}

	private static String response(JsonObject... rows)
	{
		JsonArray bucket = new JsonArray();
		for (JsonObject r : rows)
		{
			bucket.add(r);
		}
		JsonObject root = new JsonObject();
		root.add("bucket", bucket);
		return root.toString();
	}

	@Test
	public void readsItemsFromPlinks()
	{
		GearSetup.Option o = GearParser.option(plink("Saradomin mitre", "Mitre", null));
		assertEquals("Mitre", o.getItems().get(0).getLabel());
		assertEquals("Mitre", o.getItems().get(0).getLink());
		assertEquals("Saradomin mitre", o.getItems().get(0).getImageName());
		assertEquals("", o.getNote());
	}

	@Test
	public void keepsAlternativesAndQualifiersButNotNoise()
	{
		String cell = plink("Dragonbone necklace", "Dragonbone necklace", null) + " /<br/>"
			+ plink("Rada's blessing 3", "Rada's blessing 3", "Rada's blessing 3/2")
			+ " (on task)<sup><span title=\"This item can be stored in the POH\">[p]</span></sup>"
			+ "\u007f'\"`UNIQ--ref-00000004-QINU`\"'\u007f";
		GearSetup.Option o = GearParser.option(cell);
		assertEquals(2, o.getItems().size());
		assertEquals("Rada's blessing 3/2", o.getItems().get(1).getLabel());
		assertEquals("(on task)", o.getNote());
	}

	@Test
	public void optionWithoutItemsIsDropped()
	{
		assertNull(GearParser.option("Anything"));
	}

	@Test
	public void ordersSlotsAndSources()
	{
		JsonObject task = row("Slayer task/Abyssal demons", "Melee", "head", plink("Slayer helmet (i)", "Slayer helmet (i)", null));
		JsonObject strat = row("Abyssal demon/Strategies", "Ranged", "weapon", plink("Twisted bow", "Twisted bow", null));
		List<String> pages = Arrays.asList("Abyssal demon/Strategies", "Slayer task/Abyssal demons");
		List<GearSetup> setups = GearParser.parse(GSON, response(task, strat), pages);
		assertEquals(2, setups.size());
		assertEquals("Abyssal demon/Strategies", setups.get(0).getPage());
		assertEquals("Melee", setups.get(1).getStyle());

		JsonObject blob = new JsonObject();
		JsonObject eq = new JsonObject();
		for (String slot : Arrays.asList("ring", "weapon", "head"))
		{
			JsonArray a = new JsonArray();
			a.add(plink("X", "X", null));
			eq.add(slot, a);
		}
		blob.add("Recommended Equipment", eq);
		JsonObject r = new JsonObject();
		r.addProperty("page_name", "X/Strategies");
		r.addProperty("json", blob.toString());
		GearSetup s = GearParser.parse(GSON, response(r), Collections.singletonList("X/Strategies")).get(0);
		assertEquals("head", s.getSlots().get(0).getName());
		assertEquals("weapon", s.getSlots().get(1).getName());
		assertEquals("ring", s.getSlots().get(2).getName());
		assertEquals("", s.getStyle());
	}

	@Test
	public void unreadableResponseIsNull()
	{
		assertNull(GearParser.parse(GSON, "{\"error\":\"nope\"}", Collections.emptyList()));
		assertNull(GearParser.parse(GSON, "not json", Collections.emptyList()));
		assertTrue(GearParser.parse(GSON, response(), Collections.emptyList()).isEmpty());
	}

	@Test
	public void queryEscapesQuotes()
	{
		String q = SlayerGearService.buildQuery(Collections.singletonList("Kree'arra/Strategies"));
		assertTrue(q.contains("{'page_name','Kree\\'arra/Strategies'}"));
	}
}
