package com.bettermonsterexamine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The neutral view-model behind both renderers ({@link MonsterCard} and
 * {@link MonsterCardOverlay}): given a monster, its (possibly null) wiki fields, the highlight
 * mode and the player's Hitpoints level, it resolves <em>which fields to show, their values, and
 * their {@link ColourRole}</em> — with zero rendering. It owns the content/semantics that were
 * previously duplicated across the panel's {@code buildWiki} and the overlay's tab builders; the
 * renderers keep their own labels, icons and layout and read values/roles from here.
 *
 * <p>Pure and immutable — built per render from current state on whichever thread is rendering
 * (panel: EDT; overlay: client thread), so it never shares mutable state across threads.
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
	private final WikiInfo wi;
	private final HighlightMode mode;
	private final int playerHpLevel;
	private final String ver;

	MonsterStats(MonsterData m, WikiInfo wi, HighlightMode mode, int playerHpLevel)
	{
		this.m = m;
		this.wi = wi;
		this.mode = mode;
		this.playerHpLevel = playerHpLevel;
		this.ver = m.getVersion();
	}

	/** True once the async wiki fields have landed (so renderers can show their placeholders). */
	boolean wikiLoaded()
	{
		return wi != null;
	}

	// ---- Attributes / Info ---------------------------------------------------

	/** Size and attribute names on one line, e.g. {@code "7x7, Draconic, Undead"}; null if none. */
	String sizeAttr()
	{
		String sizeText = m.getSize() > 0 ? m.getSize() + "x" + m.getSize() : null;
		String attrText = m.getAttributes() != null && !m.getAttributes().isEmpty()
			? StatFormat.attributeNames(m.getAttributes()) : null;
		return StatFormat.join(", ", sizeText, attrText);
	}

	boolean slayerMonster()
	{
		return m.isSlayerMonster();
	}

	/** Flat-armour adjustment; null when zero. Negative (takes extra damage) is good, positive bad. */
	StatField flatArmour()
	{
		MonsterData.Defensive d = m.getDefensive();
		if (d == null || d.getFlatArmour() == 0)
		{
			return null;
		}
		int fa = d.getFlatArmour();
		return new StatField(String.valueOf(fa), fa < 0 ? ColourRole.GOOD : ColourRole.DANGER,
			fa < 0 ? "Takes extra flat damage per hit." : "Reduces damage taken per hit.");
	}

	/** XP bonus, e.g. {@code "+77.5%"} / {@code "-50%"}; null when absent or zero. */
	StatField xpBonus()
	{
		String xp = wi != null ? wi.get("xpbonus", ver) : null;
		if (xp == null || xp.trim().isEmpty() || StatFormat.isZero(xp))
		{
			return null;
		}
		String t = xp.trim();
		boolean penalty = t.startsWith("-");
		return new StatField((penalty ? "" : "+") + t + "%",
			penalty ? ColourRole.DANGER : ColourRole.GOOD, null);
	}

	/** Aggressive flag; null when the wiki field is absent. */
	StatField aggressive()
	{
		String aggr = wi != null ? wi.get("aggressive", ver) : null;
		if (aggr == null)
		{
			return null;
		}
		boolean yes = StatFormat.affirmative(aggr);
		return new StatField(aggr.trim(), yes ? ColourRole.DANGER : ColourRole.NEUTRAL,
			yes ? "Attacks on sight." : null);
	}

	/** Poisonous flag; null when the wiki field is absent. */
	StatField poisonous()
	{
		String pois = wi != null ? wi.get("poisonous", ver) : null;
		if (pois == null)
		{
			return null;
		}
		boolean yes = StatFormat.affirmative(pois);
		return new StatField(pois.trim(), yes ? ColourRole.DANGER : ColourRole.NEUTRAL,
			yes ? "Can poison you." : null);
	}

	/** Examine text, or null. */
	String examine()
	{
		String ex = wi != null ? wi.get("examine", ver) : null;
		return ex == null || ex.trim().isEmpty() ? null : ex.trim();
	}

	// ---- Combat info ---------------------------------------------------------

	/** Attack styles joined, or an em dash. */
	String attackStyle()
	{
		List<String> st = m.getStyle();
		return st == null || st.isEmpty() ? "—" : String.join(", ", st);
	}

	String attackSpeed()
	{
		return StatFormat.attackSpeed(m);
	}

	// ---- Max hit -------------------------------------------------------------

	/**
	 * The max-hit list, one entry per value. The wiki carries the full per-style list (split on
	 * {@code ", "}); otherwise the dataset's single value is used. Each line is flagged when its
	 * value exceeds the player's Hitpoints level (and highlighting is on).
	 */
	List<MaxHitLine> maxHits()
	{
		String wikiMax = wi != null ? wi.get("max hit", ver) : null;
		String text = wikiMax != null ? wikiMax : StatFormat.nz(m.getMaxHit());
		boolean flag = mode != HighlightMode.OFF && playerHpLevel > 0;
		List<MaxHitLine> out = new ArrayList<>();
		for (String line : text.split(", "))
		{
			out.add(new MaxHitLine(line, flag && StatFormat.maxValue(line) > playerHpLevel));
		}
		return out;
	}

	// ---- Stat grids (values only; renderers supply icons + labels) -----------

	/** Combat levels in icon order [Hitpoints, Attack, Strength, Defence, Magic, Ranged]; empty if absent. */
	List<String> combatLevels()
	{
		MonsterData.Skills s = m.getSkills();
		if (s == null)
		{
			return Collections.emptyList();
		}
		List<String> v = new ArrayList<>(6);
		v.add(StatFormat.num(s.getHp()));
		v.add(StatFormat.num(s.getAtk()));
		v.add(StatFormat.num(s.getStr()));
		v.add(StatFormat.num(s.getDef()));
		v.add(StatFormat.num(s.getMagic()));
		v.add(StatFormat.num(s.getRanged()));
		return v;
	}

	/** Offensive bonuses in icon order [Attack, Strength, Magic, Magic dmg, Ranged, Ranged str]; empty if absent. */
	List<String> offensiveBonuses()
	{
		MonsterData.Offensive o = m.getOffensive();
		if (o == null)
		{
			return Collections.emptyList();
		}
		List<String> v = new ArrayList<>(6);
		v.add(StatFormat.bonus(o.getAtk()));
		v.add(StatFormat.bonus(o.getStr()));
		v.add(StatFormat.bonus(o.getMagic()));
		v.add(StatFormat.bonus(o.getMagicStr()));
		v.add(StatFormat.bonus(o.getRanged()));
		v.add(StatFormat.bonus(o.getRangedStr()));
		return v;
	}

	/** True when the monster carries defensive bonuses (so the defence grids should render). */
	boolean hasDefensive()
	{
		return m.getDefensive() != null;
	}

	/** Melee defence bonuses [Stab, Slash, Crush]; empty if absent. */
	List<String> meleeDefence()
	{
		MonsterData.Defensive d = m.getDefensive();
		if (d == null)
		{
			return Collections.emptyList();
		}
		List<String> v = new ArrayList<>(3);
		v.add(StatFormat.bonus(d.getStab()));
		v.add(StatFormat.bonus(d.getSlash()));
		v.add(StatFormat.bonus(d.getCrush()));
		return v;
	}

	/** Magic-defence bonus, or null if absent. */
	String magicDefence()
	{
		MonsterData.Defensive d = m.getDefensive();
		return d == null ? null : StatFormat.bonus(d.getMagic());
	}

	/** Ranged defence bonuses [Light, Standard, Heavy]; empty if absent. */
	List<String> rangedDefence()
	{
		MonsterData.Defensive d = m.getDefensive();
		if (d == null)
		{
			return Collections.emptyList();
		}
		List<String> v = new ArrayList<>(3);
		v.add(StatFormat.bonus(d.getLight()));
		v.add(StatFormat.bonus(d.getStandard()));
		v.add(StatFormat.bonus(d.getHeavy()));
		return v;
	}

	/** The elemental weakness element (e.g. {@code "fire"}), or null if none. */
	String weaknessElement()
	{
		return m.getWeakness() != null ? m.getWeakness().getElement() : null;
	}

	/** The weakness severity as {@code "N%"}, or an em dash when there is no weakness. */
	String weaknessSeverity()
	{
		MonsterData.Weakness w = m.getWeakness();
		return w != null && w.getElement() != null ? w.getSeverity() + "%" : "—";
	}

	// ---- Immunities ----------------------------------------------------------

	/** Burn-immunity tier from the dataset, or null. */
	String burn()
	{
		return StatFormat.burnImmunity(m);
	}

	/** Poison resistance label (e.g. "Immune", "50% resistance"), as a danger field; null if none. */
	StatField poison()
	{
		String label = wi != null ? StatFormat.resistanceLabel(wi.get("poisonresistance", ver)) : null;
		return label == null ? null : new StatField(label, ColourRole.DANGER, null);
	}

	/** Venom resistance label, as a danger field; null if none. */
	StatField venom()
	{
		String label = wi != null ? StatFormat.resistanceLabel(wi.get("venomresistance", ver)) : null;
		return label == null ? null : new StatField(label, ColourRole.DANGER, null);
	}

	/** Cannon immunity, as a danger field; null when not immune. */
	StatField cannon()
	{
		return wi != null && StatFormat.yes(wi.get("immunecannon", ver))
			? new StatField("Immune", ColourRole.DANGER, null) : null;
	}

	/** Thrall immunity, as a danger field; null when not immune. */
	StatField thrall()
	{
		return wi != null && StatFormat.yes(wi.get("immunethrall", ver))
			? new StatField("Immune", ColourRole.DANGER, null) : null;
	}
}
