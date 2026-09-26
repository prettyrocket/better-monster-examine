package com.bettermonsterexamine.slayer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** One row of a monster page's Locations table: where it spawns, at which levels, and how many. */
@Getter
@RequiredArgsConstructor
public class SpawnLocation
{
	private static final Pattern INT = Pattern.compile("\\d+");

	private final String location;
	/** The combat levels found there as the wiki writes them — {@code "124"}, {@code "56, 76"}. */
	private final String levels;
	/** Null when the page doesn't say. */
	private final Boolean members;
	/** The spawn count as written; blank or {@code "N/A"} on some rows. */
	private final String spawns;

	/** True when {@code level} is one of the combat levels listed for this location. */
	public boolean hasLevel(int level)
	{
		Matcher m = INT.matcher(levels);
		while (m.find())
		{
			if (Integer.parseInt(m.group()) == level)
			{
				return true;
			}
		}
		return false;
	}
}
