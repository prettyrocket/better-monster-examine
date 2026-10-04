package com.bettermonsterexamine.loot;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.client.config.ConfigManager;

/**
 * Colours a GP value the way RuneLite's Ground Items plugin colours a dropped stack, so a drop's value
 * on the Drops tab reads the same as it will on the floor.
 *
 * <p>Ground Items checks its tiers from the highest down and takes the first whose price the value is
 * strictly <b>above</b>. A tier priced at 0 is switched off, and a value under every tier gets no
 * tier colour. The thresholds and colours come from the player's own Ground Items settings, with
 * RuneLite's defaults when they've never changed them.
 */
public final class ValueTiers
{
	/** Ground Items' config group, which these settings are read from. */
	public static final String GROUP = "grounditems";

	// RuneLite's defaults: 20K light blue, 100K green, 1M orange, 10M pink.
	private static final Color LOW = Color.decode("#66B2FF");
	private static final Color MEDIUM = Color.decode("#99FF99");
	private static final Color HIGH = Color.decode("#FF9600");
	private static final Color INSANE = Color.decode("#FF66B2");
	static final ValueTiers DEFAULTS = new ValueTiers(20_000, LOW, 100_000, MEDIUM, 1_000_000, HIGH, 10_000_000, INSANE);

	private final List<Tier> tiers;

	private static final class Tier
	{
		private final long price;
		private final Color color;

		private Tier(long price, Color color)
		{
			this.price = price;
			this.color = color;
		}
	}

	ValueTiers(int lowPrice, Color low, int mediumPrice, Color medium, int highPrice, Color high,
		int insanePrice, Color insane)
	{
		List<Tier> t = new ArrayList<>();
		add(t, insanePrice, insane);
		add(t, highPrice, high);
		add(t, mediumPrice, medium);
		add(t, lowPrice, low);
		this.tiers = Collections.unmodifiableList(t);
	}

	private static void add(List<Tier> into, int price, Color color)
	{
		if (price > 0 && color != null)
		{
			into.add(new Tier(price, color));
		}
	}

	/** The player's Ground Items tiers, falling back to RuneLite's default for any setting they never changed. */
	static ValueTiers fromGroundItems(ConfigManager config)
	{
		return new ValueTiers(
			price(config, "lowValuePrice", 20_000), color(config, "lowValueColor", LOW),
			price(config, "mediumValuePrice", 100_000), color(config, "mediumValueColor", MEDIUM),
			price(config, "highValuePrice", 1_000_000), color(config, "highValueColor", HIGH),
			price(config, "insaneValuePrice", 10_000_000), color(config, "insaneValueColor", INSANE));
	}

	private static int price(ConfigManager config, String key, int fallback)
	{
		Integer v = config.getConfiguration(GROUP, key, Integer.class);
		return v == null ? fallback : v;
	}

	private static Color color(ConfigManager config, String key, Color fallback)
	{
		Color v = config.getConfiguration(GROUP, key, Color.class);
		return v == null ? fallback : v;
	}

	/** The colour Ground Items would give a stack worth {@code value}, or null when it falls under every tier. */
	Color colorFor(long value)
	{
		for (Tier t : tiers)
		{
			if (value > t.price)
			{
				return t.color;
			}
		}
		return null;
	}

	/** True when {@code key} is one of the Ground Items settings these tiers are built from. */
	public static boolean isTierSetting(String key)
	{
		return key != null && key.matches("(low|medium|high|insane)Value(Price|Color)");
	}
}
