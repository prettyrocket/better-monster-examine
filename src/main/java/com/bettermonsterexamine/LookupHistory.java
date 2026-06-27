package com.bettermonsterexamine;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure model behind the side panel's Recent + Favorites lists. Holds two ordered lists of
 * {@link Entry} (monster base name + variant version), with dedup/move-to-front, a fixed Recent
 * cap, favourite toggling (newest-pinned first), and lenient JSON (de)serialisation for
 * {@code ConfigManager} persistence. Deliberately Swing-free so it can be unit-tested like
 * {@link MonsterDataService#matchNames}.
 */
class LookupHistory
{
	/** Newest-first Recent cap; older entries fall off the end. */
	static final int RECENT_CAP = 15;

	private static final Gson GSON = new Gson();

	/**
	 * A looked-up monster: base name + variant version. {@code version} is empty for single-form
	 * monsters. Identity is name + version, compared case-insensitively so a lookup recorded from
	 * the panel, the overlay, or the dataset all dedup to one entry.
	 */
	static final class Entry
	{
		final String name;
		final String version;

		Entry(String name, String version)
		{
			this.name = name == null ? "" : name;
			this.version = version == null ? "" : version;
		}

		boolean matches(String otherName, String otherVersion)
		{
			return name.equalsIgnoreCase(otherName == null ? "" : otherName)
				&& version.equalsIgnoreCase(otherVersion == null ? "" : otherVersion);
		}
	}

	private final List<Entry> recent = new ArrayList<>();
	private final List<Entry> favorites = new ArrayList<>();

	List<Entry> recent()
	{
		return Collections.unmodifiableList(recent);
	}

	List<Entry> favorites()
	{
		return Collections.unmodifiableList(favorites);
	}

	/** Record a lookup: dedup + move-to-front in Recent, capped at {@link #RECENT_CAP}. */
	void record(String name, String version)
	{
		if (name == null || name.isEmpty())
		{
			return;
		}
		remove(recent, name, version);
		recent.add(0, new Entry(name, version));
		while (recent.size() > RECENT_CAP)
		{
			recent.remove(recent.size() - 1);
		}
	}

	boolean isFavorite(String name, String version)
	{
		return indexOf(favorites, name, version) >= 0;
	}

	/** Toggle favourite state; a newly-pinned entry goes to the front. Returns the new state. */
	boolean toggleFavorite(String name, String version)
	{
		if (name == null || name.isEmpty())
		{
			return false;
		}
		int i = indexOf(favorites, name, version);
		if (i >= 0)
		{
			favorites.remove(i);
			return false;
		}
		favorites.add(0, new Entry(name, version));
		return true;
	}

	void clearRecent()
	{
		recent.clear();
	}

	void clearFavorites()
	{
		favorites.clear();
	}

	/** Drop every entry (Recent and Favorites) for a name that no longer resolves. */
	boolean removeAllByName(String name)
	{
		if (name == null)
		{
			return false;
		}
		boolean changed = recent.removeIf(e -> e.name.equalsIgnoreCase(name));
		changed |= favorites.removeIf(e -> e.name.equalsIgnoreCase(name));
		return changed;
	}

	private static void remove(List<Entry> list, String name, String version)
	{
		int i = indexOf(list, name, version);
		if (i >= 0)
		{
			list.remove(i);
		}
	}

	private static int indexOf(List<Entry> list, String name, String version)
	{
		for (int i = 0; i < list.size(); i++)
		{
			if (list.get(i).matches(name, version))
			{
				return i;
			}
		}
		return -1;
	}

	// ---------------------------------------------------------- persistence

	/** Serialise both lists to a compact JSON string for {@code ConfigManager}. */
	String toJson()
	{
		return GSON.toJson(new Persisted(recent, favorites));
	}

	/**
	 * Parse from a {@code ConfigManager} string. Malformed, legacy, or empty input degrades to
	 * empty lists rather than throwing — this runs during panel construction on the EDT.
	 */
	static LookupHistory fromJson(String json)
	{
		LookupHistory h = new LookupHistory();
		if (json == null || json.trim().isEmpty())
		{
			return h;
		}
		try
		{
			Persisted p = GSON.fromJson(json, Persisted.class);
			if (p != null)
			{
				copyValid(p.recent, h.recent, RECENT_CAP);
				copyValid(p.favorites, h.favorites, Integer.MAX_VALUE);
			}
		}
		catch (JsonSyntaxException e)
		{
			// Garbage in -> empty out; never propagate onto the EDT.
			h.recent.clear();
			h.favorites.clear();
		}
		return h;
	}

	/** Copy parsed entries, skipping blanks/dupes and normalising via the constructor, up to a cap. */
	private static void copyValid(List<Entry> src, List<Entry> dst, int cap)
	{
		if (src == null)
		{
			return;
		}
		for (Entry e : src)
		{
			if (e == null || e.name == null || e.name.isEmpty())
			{
				continue;
			}
			if (indexOf(dst, e.name, e.version) >= 0)
			{
				continue;
			}
			dst.add(new Entry(e.name, e.version));
			if (dst.size() >= cap)
			{
				break;
			}
		}
	}

	/** Gson DTO mirror of the two lists. */
	private static final class Persisted
	{
		private final List<Entry> recent;
		private final List<Entry> favorites;

		Persisted(List<Entry> recent, List<Entry> favorites)
		{
			this.recent = recent;
			this.favorites = favorites;
		}
	}
}
