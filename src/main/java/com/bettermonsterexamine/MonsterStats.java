package com.bettermonsterexamine;

import java.util.ArrayList;
import java.util.List;

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

	MonsterStats(MonsterData m, HighlightMode mode, int playerHpLevel)
	{
		this.m = m;
		this.mode = mode;
		this.playerHpLevel = playerHpLevel;
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

	String attackSpeed()
	{
		return StatFormat.attackSpeed(m);
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
	List<String> combatLevels()
	{
		List<String> v = new ArrayList<>(6);
		v.add(StatFormat.num(m.getHitpoints()));
		v.add(StatFormat.num(m.getAttackLevel()));
		v.add(StatFormat.num(m.getStrengthLevel()));
		v.add(StatFormat.num(m.getDefenceLevel()));
		v.add(StatFormat.num(m.getMagicLevel()));
		v.add(StatFormat.num(m.getRangedLevel()));
		return v;
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
}
