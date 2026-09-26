package com.bettermonsterexamine.slayer;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns {@code recommended_equipment} Bucket rows into {@link GearSetup}s. Each row stores one setup as
 * a JSON blob of <b>unexpanded</b> wikitext: every item is a {@code {{plink}}} — its inventory image then
 * its link — so the items are read straight out of that pattern and anything else in the option (a
 * footnote, a "(on task)" qualifier, a {@code >} ranking) is ignored or kept as a short note. Pure, so
 * it's unit-tested.
 */
public final class GearParser
{
	/** Slots in paper-doll order, so every setup reads the same way; unknown slots follow. */
	static final List<String> SLOT_ORDER = Arrays.asList(
		"head", "cape", "neck", "ammo", "weapon", "2h", "body", "shield", "legs", "hands", "feet", "ring", "special");

	private static final Pattern PLINK = Pattern.compile(
		"<span class=\"plink-template\">\\[\\[File:([^|\\]]+?)\\.png[^\\]]*\\]\\]</span>\\[\\[([^|\\]]+)(?:\\|([^\\]]+))?\\]\\]");
	private static final Pattern NOTE = Pattern.compile("\\(([^()]*[A-Za-z][^()]*)\\)");
	private static final Pattern NOISE = Pattern.compile("<sup.*?</sup>|\u007f[^\u007f]*\u007f|\\[\\[[^\\]]*\\]\\]", Pattern.DOTALL);

	private GearParser()
	{
	}

	/**
	 * Parse an {@code action=bucket} response into setups, ordered by {@code pages} (so a monster's own
	 * strategy guide comes before its task page), each page's setups in wiki order. Rows that don't
	 * parse are skipped; null when the response itself is unreadable.
	 */
	public static List<GearSetup> parse(Gson gson, String responseJson, List<String> pages)
	{
		JsonObject root;
		try
		{
			root = gson.fromJson(responseJson, JsonObject.class);
		}
		catch (JsonParseException e)
		{
			return null;
		}
		if (root == null || !root.has("bucket") || !root.get("bucket").isJsonArray())
		{
			return null;
		}

		List<GearSetup> out = new ArrayList<>();
		for (JsonElement el : root.getAsJsonArray("bucket"))
		{
			if (!el.isJsonObject())
			{
				continue;
			}
			JsonObject row = el.getAsJsonObject();
			String page = string(row, "page_name");
			String json = string(row, "json");
			if (page == null || json == null)
			{
				continue;
			}
			try
			{
				GearSetup setup = setup(page, gson.fromJson(json, JsonObject.class));
				if (setup != null)
				{
					out.add(setup);
				}
			}
			catch (JsonParseException | IllegalStateException e)
			{
				// One malformed setup shouldn't hide the page's others.
			}
		}

		List<String> order = new ArrayList<>();
		for (String p : pages)
		{
			order.add(p.toLowerCase(Locale.ROOT));
		}
		// Stable, so each page keeps its own setups in wiki order.
		out.sort((a, b) -> Integer.compare(rank(order, a.getPage()), rank(order, b.getPage())));
		return out;
	}

	private static int rank(List<String> order, String page)
	{
		int i = order.indexOf(page.toLowerCase(Locale.ROOT));
		return i < 0 ? order.size() : i;
	}

	private static GearSetup setup(String page, JsonObject blob)
	{
		if (blob == null || !blob.has("Recommended Equipment") || !blob.get("Recommended Equipment").isJsonObject())
		{
			return null;
		}
		JsonObject equipment = blob.getAsJsonObject("Recommended Equipment");
		List<String> keys = new ArrayList<>();
		for (Map.Entry<String, JsonElement> e : equipment.entrySet())
		{
			keys.add(e.getKey());
		}
		keys.sort((a, b) -> Integer.compare(slotRank(a), slotRank(b)));

		List<GearSetup.Slot> slots = new ArrayList<>();
		for (String key : keys)
		{
			JsonElement value = equipment.get(key);
			if (!value.isJsonArray())
			{
				continue;
			}
			List<GearSetup.Option> options = new ArrayList<>();
			for (JsonElement o : value.getAsJsonArray())
			{
				if (o.isJsonPrimitive())
				{
					GearSetup.Option option = option(o.getAsString());
					if (option != null)
					{
						options.add(option);
					}
				}
			}
			if (!options.isEmpty())
			{
				slots.add(new GearSetup.Slot(key, options));
			}
		}
		if (slots.isEmpty())
		{
			return null;
		}
		String style = blob.has("style") && blob.get("style").isJsonPrimitive() ? blob.get("style").getAsString().trim() : "";
		return new GearSetup(page, style, slots);
	}

	private static int slotRank(String slot)
	{
		int i = SLOT_ORDER.indexOf(slot);
		return i < 0 ? SLOT_ORDER.size() : i;
	}

	/** One option cell: its plink items, plus a parenthesised qualifier if it carries one. */
	static GearSetup.Option option(String wikitext)
	{
		List<GearSetup.Item> items = new ArrayList<>();
		Matcher m = PLINK.matcher(wikitext);
		while (m.find())
		{
			String label = m.group(3) != null ? m.group(3) : m.group(2);
			items.add(new GearSetup.Item(label.trim(), m.group(2).trim(), m.group(1).trim()));
		}
		if (items.isEmpty())
		{
			return null;
		}
		String rest = NOISE.matcher(PLINK.matcher(wikitext).replaceAll(" ")).replaceAll(" ");
		Matcher n = NOTE.matcher(rest);
		String note = n.find() ? "(" + n.group(1).trim() + ")" : "";
		return new GearSetup.Option(Collections.unmodifiableList(items), note);
	}

	private static String string(JsonObject o, String key)
	{
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}
}
