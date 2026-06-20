package com.bettermonsterexamine;

import java.util.Map;

/**
 * Parsed values from a monster's OSRS Wiki infobox (the fields the Weirdgloop dataset
 * doesn't carry: aggressive, poisonous, xp bonus, full max-hit list, immunities).
 * Params can be versioned ({@code max hit1}, {@code max hit2}); {@link #get} resolves the
 * right one for a given variant.
 */
public class WikiInfo
{
	private final Map<String, String> params;
	private final Map<String, String> versionIdx; // version name (lower-case) -> index suffix

	WikiInfo(Map<String, String> params, Map<String, String> versionIdx)
	{
		this.params = params;
		this.versionIdx = versionIdx;
	}

	/** Version-aware lookup: tries {@code base+idx}, then {@code base+"1"}, then {@code base}. */
	public String get(String base, String version)
	{
		String idx = version != null ? versionIdx.get(version.toLowerCase().trim()) : null;
		if (idx != null && params.containsKey(base + idx))
		{
			return WikiInfoboxService.stripMarkup(params.get(base + idx));
		}
		if (params.containsKey(base + "1"))
		{
			return WikiInfoboxService.stripMarkup(params.get(base + "1"));
		}
		return params.containsKey(base) ? WikiInfoboxService.stripMarkup(params.get(base)) : null;
	}
}
