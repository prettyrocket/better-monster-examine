package com.bettermonsterexamine;

/**
 * The compact combat information appended to a monster's Examine response.
 */
public enum ExamineSummaryMode
{
	OFF("Off"),
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
