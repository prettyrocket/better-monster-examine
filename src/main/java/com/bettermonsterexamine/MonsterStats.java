package com.bettermonsterexamine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The neutral view-model behind both renderers ({@link MonsterCard} and
 * {@link MonsterCardOverlay}): given a monster, the highlight mode and the player's Hitpoints
 * level, it resolves <em>which fields to show, their values, and their {@link ColourRole}</em> —
 * with zero rendering. It owns the content/semantics that were previously duplicated across the
 * panel's {@code buildWiki} and the overlay's tab builders; the renderers keep their own labels,
 * icons and layout and read values/roles from here.
 *
 * <p>Pure and immutable — built per render from current state on whichever thread is rendering
 * (panel: EDT; overlay: client thread), so it never shares mutable state across threads. Since the
 * cutover to the single Bucket dataset every field is present synchronously; there is no async
 * "wiki fields land later" path any more.
 */
final class MonsterStats
{
	/** A resolved field: a display value with a semantic colour role and optional tooltip. */
	static final class StatField
	{
		private final String value;
		private final ColourRole role;
		private final String tooltip;

		StatField(String value, ColourRole role, String tooltip)
		{
			this.value = value;
			this.role = role;
			this.tooltip = tooltip;
		}

		String value()
		{
			return value;
		}

		ColourRole role()
		{
			return role;
		}

		/** Tooltip text, or null. */
		String tooltip()
		{
			return tooltip;
		}
	}

	/** One max-hit line plus whether it should be flagged as exceeding the player's HP. */
	static final class MaxHitLine
	{
		private final String text;
		private final boolean overHp;

		MaxHitLine(String text, boolean overHp)
		{
			this.text = text;
			this.overHp = overHp;
		}

		String text()
		{
			return text;
		}

		boolean overHp()
		{
			return overHp;
		}
	}

	private final MonsterData m;
	private final HighlightMode mode;
	private final int playerHpLevel;
	private final int playerSlayerLevel;

	MonsterStats(MonsterData m, HighlightMode mode, int playerHpLevel, int playerSlayerLevel)
	{
		this.m = m;
		this.mode = mode;
		this.playerHpLevel = playerHpLevel;
		this.playerSlayerLevel = playerSlayerLevel;
	}

	// ---- Attributes / Info ---------------------------------------------------

	/** Size and attribute names on one line, e.g. {@code "7x7, Draconic, Undead"}; null if none. */
	String sizeAttr()
	{
		String sizeText = m.getSize() > 0 ? m.getSize() + "x" + m.getSize() : null;
		String attrText = !m.getAttributes().isEmpty() ? StatFormat.attributeNames(m.getAttributes()) : null;
		return StatFormat.join(", ", sizeText, attrText);
	}

	boolean slayerMonster()
	{
		return m.isSlayerMonster();
	}

	/**
	 * Required Slayer level to damage the monster (the wiki shows {@code 1} when there is no real
	 * requirement). Flagged {@link ColourRole#DANGER} when the player's Slayer level is known and
	 * below it — you can't harm it yet. Only meaningful on a Slayer monster.
	 */
	StatField slayerRequirement()
	{
		int req = Math.max(1, m.getSlayerLevel());
		boolean tooLow = playerSlayerLevel > 0 && playerSlayerLevel < req;
		return new StatField(String.valueOf(req), tooLow ? ColourRole.DANGER : ColourRole.NEUTRAL,
			req > 1 ? "Requires Slayer level " + req + " to damage." : null);
	}

	/** Slayer XP awarded per kill, without a trailing {@code .0}; null when absent. */
	String slayerXp()
	{
		double xp = m.getSlayerExperience();
		return xp > 0 ? StatFormat.number(xp) : null;
	}

	/**
	 * The Slayer assignment categories, de-junked (Bucket carries a few {@code "No"} placeholders;
	 * {@code "None"} is already dropped at parse) and deduplicated in order. Empty when none. E.g. {@code ["Blue dragons","Bosses"]}.
	 */
	List<String> slayerCategories()
	{
		List<String> c = m.getSlayerCategory();
		if (c == null)
		{
			return Collections.emptyList();
		}
		List<String> out = new ArrayList<>();
		for (String cat : c)
		{
			if (!cat.equalsIgnoreCase("no") && !out.contains(cat))
			{
				out.add(cat);
			}
		}
		return out;
	}

	/**
	 * The Slayer masters who assign it, as normalised lower-case keys (e.g. {@code ["duradel","nieve"]}),
	 * deduplicated in order — Bucket carries mixed casing and a few junk values. Empty when none.
	 */
	List<String> slayerMasters()
	{
		List<String> a = m.getAssignedBy();
		if (a == null)
		{
			return Collections.emptyList();
		}
		List<String> out = new ArrayList<>();
		for (String raw : a)
		{
			String key = normaliseMaster(raw);
			if (key != null && !out.contains(key))
			{
				out.add(key);
			}
		}
		return out;
	}

	/** Lower-case a raw {@code assigned_by} value to a master key, folding Konar's full name and dropping junk. */
	static String normaliseMaster(String raw)
	{
		if (raw == null)
		{
			return null;
		}
		String key = raw.trim().toLowerCase(Locale.ROOT);
		if (key.isEmpty() || key.equals("no"))
		{
			return null;
		}
		return key.startsWith("konar") ? "konar" : key;
	}

	/** Flat-armour adjustment; null when zero. Negative (takes extra damage) is good, positive bad. */
	StatField flatArmour()
	{
		int fa = m.getFlatArmour();
		if (fa == 0)
		{
			return null;
		}
		return new StatField(String.valueOf(fa), fa < 0 ? ColourRole.GOOD : ColourRole.DANGER,
			fa < 0 ? "Takes extra flat damage per hit." : "Reduces damage taken per hit.");
	}

	/** XP bonus, e.g. {@code "+77.5%"} / {@code "-50%"}; null when absent or zero. */
	StatField xpBonus()
	{
		double xp = m.getExperienceBonus();
		if (xp == 0)
		{
			return null;
		}
		boolean penalty = xp < 0;
		return new StatField((penalty ? "" : "+") + StatFormat.number(xp) + "%",
			penalty ? ColourRole.DANGER : ColourRole.GOOD, null);
	}

	/** Poisonous flag; null when the field is absent. */
	StatField poisonous()
	{
		String pois = m.getPoisonous();
		if (pois == null || pois.isEmpty())
		{
			return null;
		}
		boolean yes = StatFormat.affirmative(pois);
		return new StatField(pois, yes ? ColourRole.DANGER : ColourRole.NEUTRAL,
			yes ? "Can poison you." : null);
	}

	/** Examine text, or null. */
	String examine()
	{
		String ex = m.getExamine();
		return ex == null || ex.isEmpty() ? null : ex;
	}

	// ---- Combat info ---------------------------------------------------------

	/** Attack styles joined, or an em dash. */
	String attackStyle()
	{
		List<String> st = m.getAttackStyles();
		return st.isEmpty() ? "—" : String.join(", ", st);
	}

	/** The speed, with the wiki's footnote as the tooltip when it was recovered from the page. */
	StatField attackSpeed()
	{
		InfoboxLevels.LevelText text = m.getAttackSpeed() <= 0 ? m.getLevelRange("attack_speed") : null;
		return new StatField(StatFormat.attackSpeed(m), ColourRole.NEUTRAL, text == null ? null : text.getNote());
	}

	/** Ticks until it respawns, as "50 ticks (30.0 seconds)"; a dash when the wiki gives no plain number. */
	String respawn()
	{
		Integer t = m.getRespawnTime();
		return t == null || t <= 0 ? "—" : StatFormat.ticks(t);
	}

	// ---- Max hit -------------------------------------------------------------

	/**
	 * The max-hit list, one entry per value (Bucket returns them already split; the sanitizer
	 * cleans each). Each line is flagged when its value exceeds the player's Hitpoints level (and
	 * highlighting is on). An em dash stands in when the monster carries no max-hit data.
	 */
	List<MaxHitLine> maxHits()
	{
		List<String> lines = m.getMaxHitLines();
		boolean flag = mode != HighlightMode.OFF && playerHpLevel > 0;
		List<MaxHitLine> out = new ArrayList<>();
		if (lines.isEmpty())
		{
			out.add(new MaxHitLine("—", false));
			return out;
		}
		for (String line : lines)
		{
			out.add(new MaxHitLine(line, flag && StatFormat.maxValue(line) > playerHpLevel));
		}
		return out;
	}

	// ---- Stat grids (values only; renderers supply icons + labels) -----------

	/** Combat levels in icon order [Hitpoints, Attack, Strength, Defence, Magic, Ranged]. */
	List<StatField> combatLevels()
	{
		List<StatField> v = new ArrayList<>(6);
		v.add(level(m.getHitpoints(), null));
		v.add(level(m.getAttackLevel(), "attack_level"));
		v.add(level(m.getStrengthLevel(), "strength_level"));
		v.add(level(m.getDefenceLevel(), "defence_level"));
		v.add(level(m.getMagicLevel(), "magic_level"));
		v.add(level(m.getRangedLevel(), "ranged_level"));
		return v;
	}

	/**
	 * One combat level. Bucket's INTEGER columns cannot hold a level the wiki writes as a range —
	 * Vardorvis' Strength and Defence scale with his remaining HP ("270-360") — so the service
	 * recovers those from the page wikitext and they arrive here as text instead of a number. The
	 * wiki's own footnote rides along as the tooltip: a Defence that counts <em>down</em> (215-145)
	 * otherwise reads as a bug rather than the mechanic it is.
	 */
	private StatField level(int value, String bucketField)
	{
		InfoboxLevels.LevelText range = bucketField == null ? null : m.getLevelRange(bucketField);
		if (value <= 0 && range != null)
		{
			return new StatField(range.getValue(), ColourRole.NEUTRAL, range.getNote());
		}
		return new StatField(StatFormat.num(value), ColourRole.NEUTRAL, null);
	}

	/** Offensive bonuses in icon order [Attack, Strength, Magic, Magic dmg, Ranged, Ranged str]. */
	List<String> offensiveBonuses()
	{
		List<String> v = new ArrayList<>(6);
		v.add(StatFormat.bonus(m.getAttackBonus()));
		v.add(StatFormat.bonus(m.getStrengthBonus()));
		v.add(StatFormat.bonus(m.getMagicAttackBonus()));
		v.add(StatFormat.bonus(m.getMagicDamageBonus()));
		v.add(StatFormat.bonus(m.getRangeAttackBonus()));
		v.add(StatFormat.bonus(m.getRangeStrengthBonus()));
		return v;
	}

	/** Bucket always carries the defensive bonus fields, so the defence grids always render. */
	boolean hasDefensive()
	{
		return true;
	}

	/** Melee defence bonuses [Stab, Slash, Crush]. */
	List<String> meleeDefence()
	{
		List<String> v = new ArrayList<>(3);
		v.add(StatFormat.bonus(m.getStabDefenceBonus()));
		v.add(StatFormat.bonus(m.getSlashDefenceBonus()));
		v.add(StatFormat.bonus(m.getCrushDefenceBonus()));
		return v;
	}

	/** Magic-defence bonus. */
	String magicDefence()
	{
		return StatFormat.bonus(m.getMagicDefenceBonus());
	}

	/** Ranged defence bonuses [Light, Standard, Heavy]. */
	List<String> rangedDefence()
	{
		List<String> v = new ArrayList<>(3);
		v.add(StatFormat.bonus(m.getLightRangeDefenceBonus()));
		v.add(StatFormat.bonus(m.getStandardRangeDefenceBonus()));
		v.add(StatFormat.bonus(m.getHeavyRangeDefenceBonus()));
		return v;
	}

	/**
	 * Melee defence rolls [Stab, Slash, Crush], or null when the wiki lacks an input — the
	 * renderers then fall back to the bonuses.
	 */
	List<String> meleeDefenceRoll()
	{
		return rolls(DefenceRolls.Style.STAB, DefenceRolls.Style.SLASH, DefenceRolls.Style.CRUSH);
	}

	/** Magic defence roll, or null when the wiki lacks an input. */
	String magicDefenceRoll()
	{
		List<String> v = rolls(DefenceRolls.Style.MAGIC);
		return v == null ? null : v.get(0);
	}

	/** Ranged defence rolls [Light, Standard, Heavy], or null when the wiki lacks an input. */
	List<String> rangedDefenceRoll()
	{
		return rolls(DefenceRolls.Style.LIGHT, DefenceRolls.Style.STANDARD, DefenceRolls.Style.HEAVY);
	}

	/**
	 * Which defence rolls to highlight, by the same ranking as the chat summary's Weakness line:
	 * its first answer {@link ColourRole#GOOD}, the free style it names second {@link ColourRole#NEXT}.
	 * Styles it doesn't name are absent, as is everything when no style stands out.
	 */
	Map<DefenceRolls.Style, ColourRole> defenceRollRoles()
	{
		return DefenceRolls.roles(m);
	}

	private List<String> rolls(DefenceRolls.Style... styles)
	{
		if (!m.hasDefenceRollInputs())
		{
			return null;
		}
		Map<DefenceRolls.Style, Integer> rolls = DefenceRolls.rolls(m);
		List<String> v = new ArrayList<>(styles.length);
		for (DefenceRolls.Style style : styles)
		{
			v.add(StatFormat.roll(rolls.get(style)));
		}
		return v;
	}

	/** The elemental weakness element (e.g. {@code "Fire"}), or null if none. */
	String weaknessElement()
	{
		String e = m.getWeaknessElement();
		return e == null || e.isEmpty() ? null : e;
	}

	/** The weakness severity as {@code "N%"}, or an em dash when there is no weakness. */
	String weaknessSeverity()
	{
		return weaknessElement() != null ? m.getWeaknessPercent() + "%" : "—";
	}

	// ---- Immunities ----------------------------------------------------------

	/** Burn-immunity label (e.g. {@code "Immune (weak)"}), or null. */
	String burn()
	{
		return m.getBurnLabel();
	}

	/** Cannon immunity, as a danger field; null when not immune. */
	StatField cannon()
	{
		return MonsterData.isImmune(m.getCannonImmune()) ? new StatField("Immune", ColourRole.DANGER, null) : null;
	}

	/** Thrall immunity, as a danger field; null when not immune. */
	StatField thrall()
	{
		return MonsterData.isImmune(m.getThrallImmune()) ? new StatField("Immune", ColourRole.DANGER, null) : null;
	}

	/** Poison resistance; null when the monster can be poisoned normally. */
	StatField poison()
	{
		return resistance(m.getPoisonResistance());
	}

	/** Venom resistance; null when the monster can be envenomed normally. */
	StatField venom()
	{
		return resistance(m.getVenomResistance());
	}

	/**
	 * The wiki's resistance value ({@code 0}, {@code 100}, {@code 200}, or venom's {@code Poisons})
	 * as a field: 100 reads "Immune", 200 is shown as the wiki writes it, and {@code Poisons} is
	 * "Converts to poison" (still poisoned, never envenomed). Null for 0 or anything unrecognised,
	 * so only a resistance takes up a row.
	 */
	static StatField resistance(String raw)
	{
		String r = raw == null ? "" : raw.trim();
		if (r.equalsIgnoreCase("poisons"))
		{
			return new StatField("Converts to poison", ColourRole.NEUTRAL, null);
		}
		if (r.equals("100"))
		{
			return new StatField("Immune", ColourRole.DANGER, null);
		}
		return r.matches("[1-9][0-9]*") ? new StatField(r + "% resistance", ColourRole.DANGER, null) : null;
	}
}
