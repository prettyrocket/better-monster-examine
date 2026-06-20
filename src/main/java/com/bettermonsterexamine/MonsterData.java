package com.bettermonsterexamine;

import com.google.gson.annotations.SerializedName;
import java.util.List;
import lombok.Getter;

/**
 * One monster variant from the Weirdgloop DPS-calc dataset (cdn/json/monsters.json),
 * which mirrors the OSRS Wiki monster infobox. Keyed by NPC {@link #id}; a single
 * name can have several variants distinguished by {@link #version}.
 */
@Getter
public class MonsterData
{
	private int id;
	private String name;
	private String version;
	private int level;
	private int speed;
	private int size;
	private List<String> style;
	@SerializedName("max_hit")
	private String maxHit;
	private Skills skills;
	private Offensive offensive;
	private Defensive defensive;
	private List<String> attributes;
	@SerializedName("is_slayer_monster")
	private boolean slayerMonster;
	private Weakness weakness;
	private Immunities immunities;

	/** The six monster combat levels (not bonuses). */
	@Getter
	public static class Skills
	{
		private int atk;
		private int def;
		private int hp;
		private int magic;
		private int ranged;
		private int str;
	}

	/** Aggressive (offensive) bonuses. */
	@Getter
	public static class Offensive
	{
		private int atk;
		private int str;
		private int magic;
		@SerializedName("magic_str")
		private int magicStr;
		private int ranged;
		@SerializedName("ranged_str")
		private int rangedStr;
	}

	/** Defensive bonuses by attack type. */
	@Getter
	public static class Defensive
	{
		private int stab;
		private int slash;
		private int crush;
		private int magic;
		private int light;
		private int standard;
		private int heavy;
		@SerializedName("flat_armour")
		private int flatArmour;
	}

	/** Elemental weakness, e.g. element="earth", severity=40 (%). */
	@Getter
	public static class Weakness
	{
		private String element;
		private int severity;
	}

	/** Immunities; {@code burn} is the wiki "burn immunity" tier (Weak/Normal/Strong), null if none. */
	@Getter
	public static class Immunities
	{
		private String burn;
	}

	/** Display name including the variant suffix when present, e.g. "Vorkath (Post-quest)". */
	public String displayName()
	{
		return (version == null || version.isEmpty()) ? name : name + " (" + version + ")";
	}
}
