package com.bettermonsterexamine.slayer;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** One row of a monster page's Locations table: where it spawns, at which levels, and how many. */
@Getter
@RequiredArgsConstructor
public class SpawnLocation
{
	private final String location;
	/** The combat levels found there as the wiki writes them — {@code "124"}, {@code "56, 76"}. */
	private final String levels;
	/** Null when the page doesn't say. */
	private final Boolean members;
	/** The spawn count as written; blank or {@code "N/A"} on some rows. */
	private final String spawns;
}
