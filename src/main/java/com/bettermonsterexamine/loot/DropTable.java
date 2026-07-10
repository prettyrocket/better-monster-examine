package com.bettermonsterexamine.loot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * One monster variant's drops, grouped into the deterministic sections the design mandates —
 * <b>100%</b> → <b>Other</b> → <b>Rare drop table</b> — with each section's rows sorted most-common
 * first. The grouping is a pure flag lookup ({@link DropRow#section()}), never inferred: a row is
 * {@code "100%"} when its rarity is {@code "Always"}, {@code "Rare drop table"} when Bucket sets the
 * {@code rare_drop_table} flag, and {@code "Other"} otherwise. Nothing here is tunable, so nothing can
 * drift.
 *
 * <p>{@link DropTableService} fetches a page's drops all at once; {@link #group} splits them into one
 * {@code DropTable} per variant (first-seen order) so the UI renders a block per variant.
 */
@Getter
public class DropTable
{
	public static final String SECTION_HUNDRED = "100%";
	public static final String SECTION_OTHER = "Other";
	public static final String SECTION_RARE_DROP_TABLE = "Rare drop table";

	/** Section display order — everything else is derived from it. */
	private static final List<String> SECTION_ORDER =
		Arrays.asList(SECTION_HUNDRED, SECTION_OTHER, SECTION_RARE_DROP_TABLE);

	/** The variant key (the part after {@code '#'} in {@code "Dropped from"}); {@code ""} for a lone form. */
	private final String variant;
	private final List<Section> sections;

	DropTable(String variant, List<DropRow> rows)
	{
		this.variant = variant == null ? "" : variant;
		this.sections = buildSections(rows);
	}

	/** The label to show for this variant: its name, or {@code "All drops"} for a single-variant page. */
	public String displayName()
	{
		return variant.isEmpty() ? "All drops" : variant;
	}

	/** True when this variant contributed no drop rows. */
	public boolean isEmpty()
	{
		return sections.isEmpty();
	}

	/**
	 * Split a page's rows into one table per variant, in first-seen order (so the wiki's variant
	 * order is preserved). Pure over its argument, so the grouping is unit-testable.
	 */
	public static List<DropTable> group(List<DropRow> rows)
	{
		Map<String, List<DropRow>> byVariant = new LinkedHashMap<>();
		for (DropRow row : rows)
		{
			byVariant.computeIfAbsent(row.variant(), k -> new ArrayList<>()).add(row);
		}
		List<DropTable> out = new ArrayList<>(byVariant.size());
		for (Map.Entry<String, List<DropRow>> e : byVariant.entrySet())
		{
			out.add(new DropTable(e.getKey(), e.getValue()));
		}
		return out;
	}

	/** Group one variant's rows by section (fixed order), each section sorted most-common first. */
	private static List<Section> buildSections(List<DropRow> rows)
	{
		Map<String, List<DropRow>> bySection = new LinkedHashMap<>();
		for (DropRow row : rows)
		{
			bySection.computeIfAbsent(row.section(), k -> new ArrayList<>()).add(row);
		}
		List<Section> built = new ArrayList<>();
		for (String label : SECTION_ORDER)
		{
			List<DropRow> secRows = bySection.get(label);
			if (secRows == null || secRows.isEmpty())
			{
				continue;
			}
			// Stable sort: equal-probability rows keep the wiki's row order.
			secRows.sort(Comparator.comparingDouble(DropRow::rarityProbability).reversed());
			built.add(new Section(label, Collections.unmodifiableList(secRows)));
		}
		return Collections.unmodifiableList(built);
	}

	/** One section within a variant: its label ({@code "100%"} / {@code "Other"} / …) and sorted rows. */
	@Getter
	@RequiredArgsConstructor
	public static final class Section
	{
		private final String label;
		private final List<DropRow> rows;
	}
}
