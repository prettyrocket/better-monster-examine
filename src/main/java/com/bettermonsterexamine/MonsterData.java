package com.bettermonsterexamine;

import com.google.gson.annotations.SerializedName;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

/**
 * One monster variant from the OSRS Wiki Bucket {@code infobox_monster} dataset — the single
 * source behind the plugin. Flat, mirroring the Bucket row; a single name can carry several
 * variants distinguished by {@link #versionAnchor}. TEXT and {@code max_hit} fields keep their raw
 * Bucket values and are cleaned on access via {@link WikiSanitizer}.
 *
 * <p>{@link #version} is not a Bucket field: it is the unique display label
 * {@link MonsterDataService} assigns per variant (the {@code version_anchor}, disambiguated with the
 * combat level when blank or colliding), so the rest of the app keeps treating a variant as
 * "name + version".
 */
@Getter
public class MonsterData
{
	private String name;
	/** Repeated NPC spawn ids (Bucket returns them as strings); see {@link #getId()}. */
	@SerializedName("id")
	private List<String> ids;
	@SerializedName("version_anchor")
	private String versionAnchor;
	/** Bucket serialises this as {@code ""} (this is a/the default form) or {@code null}; not a boolean. */
	@Getter(AccessLevel.NONE)
	@SerializedName("default_version")
	private String defaultVersion;

	/** Unique display label among a name's variants; assigned by the service after indexing. */
	@Setter
	private String version = "";

	@SerializedName("combat_level")
	private int level;
	private int hitpoints;
	private int size;

	@SerializedName("attack_level")
	private int attackLevel;
	@SerializedName("strength_level")
	private int strengthLevel;
	@SerializedName("defence_level")
	private int defenceLevel;
	@SerializedName("magic_level")
	private int magicLevel;
	@SerializedName("ranged_level")
	private int rangedLevel;

	@SerializedName("attack_bonus")
	private int attackBonus;
	@SerializedName("strength_bonus")
	private int strengthBonus;
	@SerializedName("magic_attack_bonus")
	private int magicAttackBonus;
	@SerializedName("magic_damage_bonus")
	private int magicDamageBonus;
	@SerializedName("range_attack_bonus")
	private int rangeAttackBonus;
	@SerializedName("range_strength_bonus")
	private int rangeStrengthBonus;
	// Per-style melee attack bonuses; usually unset on the wiki (it carries the single attack_bonus).
	@SerializedName("stab_attack_bonus")
	private int stabAttackBonus;
	@SerializedName("slash_attack_bonus")
	private int slashAttackBonus;
	@SerializedName("crush_attack_bonus")
	private int crushAttackBonus;

	@SerializedName("stab_defence_bonus")
	private int stabDefenceBonus;
	@SerializedName("slash_defence_bonus")
	private int slashDefenceBonus;
	@SerializedName("crush_defence_bonus")
	private int crushDefenceBonus;
	@SerializedName("magic_defence_bonus")
	private int magicDefenceBonus;
	@SerializedName("range_defence_bonus")
	private int rangeDefenceBonus;
	@SerializedName("light_range_defence_bonus")
	private int lightRangeDefenceBonus;
	@SerializedName("standard_range_defence_bonus")
	private int standardRangeDefenceBonus;
	@SerializedName("heavy_range_defence_bonus")
	private int heavyRangeDefenceBonus;
	@SerializedName("flat_armour")
	private int flatArmour;

	@SerializedName("attack_style")
	private List<String> attackStyles;
	@SerializedName("attack_speed")
	private int attackSpeed;
	@Getter(AccessLevel.NONE)
	@SerializedName("max_hit")
	private List<String> maxHit;
	@SerializedName("experience_bonus")
	private double experienceBonus;

	@SerializedName("attribute")
	private List<String> attributes;
	@SerializedName("elemental_weakness")
	private String weaknessElement;
	@SerializedName("elemental_weakness_percent")
	private int weaknessPercent;

	@Getter(AccessLevel.NONE)
	private String examine;
	@Getter(AccessLevel.NONE)
	private String poisonous;

	@SerializedName("cannon_immune")
	private String cannonImmune;
	@SerializedName("thrall_immune")
	private String thrallImmune;
	@SerializedName("burn_immune")
	private String burnImmune;
	@SerializedName("freeze_resistance")
	private String freezeResistance;

	// --- Captured for completeness (not yet rendered — see #31) ---
	@SerializedName("slayer_level")
	private int slayerLevel;
	@SerializedName("slayer_experience")
	private double slayerExperience;
	@SerializedName("slayer_category")
	private List<String> slayerCategory;
	@SerializedName("assigned_by")
	private List<String> assignedBy;
	@SerializedName("uses_skill")
	private List<String> usesSkill;
	private List<String> image;
	@SerializedName("league_region")
	private String leagueRegion;
	@SerializedName("release_date")
	private String releaseDate;
	@Getter(AccessLevel.NONE)
	@SerializedName("is_members_only")
	private String membersOnly;

	// ---- derived accessors -------------------------------------------------

	/** The first (primary) NPC id, or 0 when the row carries none — used for the DPS-calc deep link. */
	public int getId()
	{
		if (ids != null)
		{
			for (String s : ids)
			{
				Integer v = parseId(s);
				if (v != null)
				{
					return v;
				}
			}
		}
		return 0;
	}

	/** Parse a Bucket id string to an int, or null when it isn't numeric. */
	static Integer parseId(String s)
	{
		try
		{
			return s == null ? null : Integer.valueOf(s.trim());
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	/** True when Bucket flags this as a default form ({@code default_version} present). */
	public boolean isDefaultVersion()
	{
		return defaultVersion != null;
	}

	public boolean isMembersOnly()
	{
		return membersOnly != null;
	}

	/** A monster is a Slayer target when the wiki lists a Slayer category for it. */
	public boolean isSlayerMonster()
	{
		return slayerCategory != null && !slayerCategory.isEmpty();
	}

	/** Examine text, cleaned of wiki markup; null when absent. */
	public String getExamine()
	{
		return WikiSanitizer.text(examine);
	}

	/** Poisonous flag (e.g. {@code "Yes (venom)"}), cleaned of wiki markup; null when absent. */
	public String getPoisonous()
	{
		return WikiSanitizer.text(poisonous);
	}

	/** The max-hit values, one clean line each (plainlist/strip-markers/&lt;br&gt;/links removed). */
	public List<String> getMaxHitLines()
	{
		return WikiSanitizer.maxHitLines(maxHit);
	}

	public List<String> getAttackStyles()
	{
		return attackStyles != null ? attackStyles : Collections.emptyList();
	}

	public List<String> getAttributes()
	{
		return attributes != null ? attributes : Collections.emptyList();
	}

	/** Burn-immunity label (e.g. {@code "Immune (weak)"}) when immune, else null. */
	public String getBurnLabel()
	{
		return burnImmune != null && burnImmune.toLowerCase(Locale.ROOT).startsWith("immune") ? burnImmune : null;
	}

	/** True when this immunity field carries the wiki's {@code "Immune"} value. */
	static boolean isImmune(String field)
	{
		return field != null && field.trim().equalsIgnoreCase("Immune");
	}
}
