package com.bettermonsterexamine;

/**
 * How much detail the compact block appended to a monster's Examine response carries. Whether it
 * appears at all is the separate {@code examineSummaryEnabled} checkbox.
 */
public enum ExamineSummaryMode
{
	WEAKNESSES("Weaknesses only"),
	ALL_DEFENCES("All defences");

	private final String label;

	ExamineSummaryMode(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
