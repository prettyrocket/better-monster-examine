package com.bettermonsterexamine.loot;

import com.google.gson.annotations.SerializedName;
import java.util.Locale;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

/**
 * One drop line from the OSRS Wiki Bucket {@code dropsline} dataset — a single (monster-variant ×
 * item) row. Its fields are mapped straight from the row's {@code drop_json} blob (Bucket nests the
 * drop as a JSON string; {@link DropTableService} parses that string into this DTO).
 *
 * <p>Several values come from the enclosing Bucket row rather than the {@code drop_json} blob and are
 * assigned after parsing: {@link #item} (falls back to the row's {@code item_name}), the
 * {@link #pageNameSub} variant key, the {@link #rareDropTable} flag, and the resolved client
 * {@link #itemId}. The flag follows the same sentinel-string convention as
 * {@code MonsterData.default_version}: Bucket serialises it as {@code ""} when set and omits it
 * otherwise, so presence — not a boolean — is the signal (see {@link #isRareDropTable()}).
 */
@Getter
public class DropRow
{
	@SerializedName("Dropped item")
	@Setter
	private String item;
	@SerializedName("Rarity")
	private String rarity;
	@SerializedName("Rarity Notes")
	private String rarityNotes;
	@SerializedName("Name Notes")
	private String nameNotes;

	/** Display quantity as the wiki renders it, e.g. {@code "2"}, {@code "2–3"}, {@code "60 (noted)"}. */
	@SerializedName("Drop Quantity")
	private String quantityDisplay;
	@SerializedName("Quantity Low")
	private int quantityLow;
	@SerializedName("Quantity High")
	private int quantityHigh;

	@SerializedName("Rolls")
	private int rolls;
	/** Wiki section this drop belongs to, e.g. {@code "combat"} or {@code "tertiary"} — free grouping. */
	@SerializedName("Drop type")
	private String dropType;
	/** Grand Exchange value of the stack (captured for a later phase; not rendered in v1). */
	@SerializedName("Drop Value")
	private int value;
	/** The {@code "<Page>#<Variant>"} the drop was tabled under; the variant is the part after {@code '#'}. */
	@SerializedName("Dropped from")
	private String droppedFrom;

	/** The {@code page_name#version_anchor} variant key; set by the service from the enclosing row. */
	@Setter
	private String pageNameSub;
	/** Sentinel string ({@code ""}/null) from the enclosing row; see {@link #isRareDropTable()}. */
	@Getter(AccessLevel.NONE)
	@Setter
	private String rareDropTable;
	/** The RuneLite client item id, resolved from the {@code item_id} map; null when unknown. */
	@Setter
	private Integer itemId;

	/** True when this drop always occurs (rarity {@code "Always"}), rather than rolling a chance. */
	public boolean isAlways()
	{
		return rarity != null && rarity.trim().equalsIgnoreCase("Always");
	}

	/** True when the item is dropped noted (the wiki tags the display quantity with {@code (noted)}). */
	public boolean isNoted()
	{
		return quantityDisplay != null && quantityDisplay.toLowerCase(Locale.ROOT).contains("(noted)");
	}

	/** True when this drop is one of the shared rare-drop-table rolls rather than the monster's own table. */
	public boolean isRareDropTable()
	{
		return rareDropTable != null;
	}

	/**
	 * The monster variant this drop was tabled under — the text after {@code '#'} in
	 * {@code "Dropped from"} (e.g. {@code "Post-quest"} for {@code "Vorkath#Post-quest"}), or {@code ""}
	 * for a single-variant page. This is the key drops are grouped by; see {@link DropTable#group}.
	 */
	public String variant()
	{
		if (droppedFrom == null)
		{
			return "";
		}
		int hash = droppedFrom.indexOf('#');
		return hash < 0 ? "" : droppedFrom.substring(hash + 1);
	}

	/** The deterministic drop section this row belongs to; see {@link DropTable} for the ordering. */
	public String section()
	{
		if (isAlways())
		{
			return DropTable.SECTION_HUNDRED;
		}
		if (isRareDropTable())
		{
			return DropTable.SECTION_RARE_DROP_TABLE;
		}
		return DropTable.SECTION_OTHER;
	}

	/**
	 * The rarity as a probability in {@code (0, 1]} for sorting/display: {@code "Always"} → 1.0, an
	 * {@code "a/b"} fraction → a/b (a leading {@code '~'} and thousands commas tolerated), and anything
	 * unparseable ({@code "Varies"}, blank, malformed) → {@code -1.0} so it sorts to the bottom.
	 */
	public double rarityProbability()
	{
		return probabilityOf(rarity);
	}

	/** See {@link #rarityProbability()}. Pure and static so the parsing shapes stay unit-testable. */
	static double probabilityOf(String rarity)
	{
		if (rarity == null)
		{
			return -1.0;
		}
		String r = rarity.trim();
		if (r.startsWith("~"))
		{
			r = r.substring(1).trim();
		}
		r = r.replace(",", "");
		if (r.equalsIgnoreCase("Always"))
		{
			return 1.0;
		}
		int slash = r.indexOf('/');
		if (slash >= 0)
		{
			try
			{
				double num = Double.parseDouble(r.substring(0, slash).trim());
				double den = Double.parseDouble(r.substring(slash + 1).trim());
				return den == 0 ? -1.0 : num / den;
			}
			catch (NumberFormatException e)
			{
				return -1.0;
			}
		}
		try
		{
			return Double.parseDouble(r);
		}
		catch (NumberFormatException e)
		{
			return -1.0;
		}
	}

	/**
	 * The rarity expressed as a probability in {@code (0, 1]}, or null when it isn't a simple
	 * {@code numerator/denominator} fraction. {@code "Always"} returns null (test {@link #isAlways()}
	 * instead); thousands separators in the denominator (e.g. {@code "1/4,096"}) are tolerated.
	 */
	public Double getRarityFraction()
	{
		return parseFraction(rarity);
	}

	/** Parse an {@code "n/d"} rarity fraction to a probability, tolerating commas; null when not a fraction. */
	static Double parseFraction(String rarity)
	{
		if (rarity == null)
		{
			return null;
		}
		int slash = rarity.indexOf('/');
		if (slash < 0)
		{
			return null;
		}
		try
		{
			double num = Double.parseDouble(rarity.substring(0, slash).replace(",", "").trim());
			double den = Double.parseDouble(rarity.substring(slash + 1).replace(",", "").trim());
			return den == 0 ? null : num / den;
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}
}
