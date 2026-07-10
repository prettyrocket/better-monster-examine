package com.bettermonsterexamine.loot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * A monster's drops grouped into the wiki's own drop-table sections, <b>in the order the wiki page
 * lists them</b> — 100% / Weapons and armour / Runes / Herbs / Gem drop table / Rare drop table /
 * Catacombs tertiary / Wilderness Slayer Cave tertiary / Tertiary / … . The section labels and their
 * order come straight from the parsed page headings ({@link DropRow#getSection()}); nothing here is
 * inferred, so the grouping matches what a player sees on the wiki.
 */
@Getter
public class DropTable
{
	private final List<Section> sections;

	private DropTable(List<Section> sections)
	{
		this.sections = sections;
	}

	public boolean isEmpty()
	{
		return sections.isEmpty();
	}

	/**
	 * Group parsed rows into sections, preserving the wiki page's section order (first-seen) and the
	 * row order within each section. Pure over its argument, so the grouping is unit-testable.
	 */
	public static DropTable of(List<DropRow> rows)
	{
		Map<String, List<DropRow>> bySection = new LinkedHashMap<>();
		for (DropRow row : rows)
		{
			String label = row.getSection() == null || row.getSection().isEmpty() ? "Other" : row.getSection();
			bySection.computeIfAbsent(label, k -> new ArrayList<>()).add(row);
		}
		List<Section> built = new ArrayList<>(bySection.size());
		for (Map.Entry<String, List<DropRow>> e : bySection.entrySet())
		{
			built.add(new Section(e.getKey(), Collections.unmodifiableList(e.getValue())));
		}
		return new DropTable(Collections.unmodifiableList(built));
	}

	/** One drop-table section: its wiki heading and the rows under it, in page order. */
	@Getter
	@RequiredArgsConstructor
	public static final class Section
	{
		private final String label;
		private final List<DropRow> rows;
	}
}
