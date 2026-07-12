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
 * Catacombs tertiary / Wilderness Slayer Cave tertiary / Tertiary / … . The labels and their order
 * come straight from the parsed page headings; nothing here is inferred, so the grouping matches what
 * a player sees on the wiki.
 *
 * <p>Sections nest inside a {@link Group}, mirroring the page's two heading levels. Most monsters have
 * a single unlabelled group holding every section, but where the wiki splits a monster's drops by
 * location or combat level — Cyclops (Warriors' Guild top floor vs basement), Abyssal demon (Catacombs
 * vs Wilderness Slayer Cave), Giant frog (level 13 vs 99) — each of those becomes its own group.
 * Keeping them apart is load-bearing: the same section name ({@code "100%"}, {@code "Herbs"}) recurs
 * under each with <b>different</b> drops and rates, so flattening them merges tables that a player
 * never sees together and implies drops a monster doesn't have.
 */
@Getter
public class DropTable
{
	private final List<Group> groups;

	private DropTable(List<Group> groups)
	{
		this.groups = groups;
	}

	public boolean isEmpty()
	{
		return groups.isEmpty();
	}

	/**
	 * Group parsed rows by their group, then their section, preserving the wiki page's order at both
	 * levels (first-seen) and the row order within each section. Sections are only merged within the
	 * same group, so like-named tables under different groups stay distinct. Pure over its argument,
	 * so the grouping is unit-testable.
	 */
	public static DropTable of(List<DropRow> rows)
	{
		Map<String, Map<String, List<DropRow>>> byGroup = new LinkedHashMap<>();
		for (DropRow row : rows)
		{
			String group = row.getGroup() == null ? "" : row.getGroup();
			String section = row.getSection() == null || row.getSection().isEmpty() ? "Other" : row.getSection();
			byGroup.computeIfAbsent(group, k -> new LinkedHashMap<>())
				.computeIfAbsent(section, k -> new ArrayList<>())
				.add(row);
		}

		List<Group> built = new ArrayList<>(byGroup.size());
		for (Map.Entry<String, Map<String, List<DropRow>>> g : byGroup.entrySet())
		{
			List<Section> sections = new ArrayList<>(g.getValue().size());
			for (Map.Entry<String, List<DropRow>> s : g.getValue().entrySet())
			{
				sections.add(new Section(s.getKey(), Collections.unmodifiableList(s.getValue())));
			}
			built.add(new Group(g.getKey(), Collections.unmodifiableList(sections)));
		}
		return new DropTable(Collections.unmodifiableList(built));
	}

	/**
	 * One location/combat-level grouping of drop tables, or the single unlabelled group ({@code ""})
	 * holding every section on a page that doesn't split its drops.
	 */
	@Getter
	@RequiredArgsConstructor
	public static final class Group
	{
		private final String label;
		private final List<Section> sections;
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
