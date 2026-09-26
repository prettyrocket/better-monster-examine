package com.bettermonsterexamine.slayer;

import java.util.Arrays;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import org.junit.Test;

/** Exercises {@link Guides#parse} against the wiki's "This article has a … guide" message boxes. */
public class GuidesTest
{
	private static final String STRATEGY = "<span class=\"messagebox-title\"><b>This article has a "
		+ "<a href=\"/w/Kree%27arra/Strategies\" title=\"Kree&#039;arra/Strategies\">strategy guide</a>.</b></span>";
	private static final String TASK = "<span class=\"messagebox-title\"><b>This article has a Slayer task guide: "
		+ "<a href=\"/w/Slayer_task/Hellhounds\" title=\"Slayer task/Hellhounds\">Hellhounds</a>.</b></span>";

	@Test
	public void readsBothGuidesByTitle()
	{
		Guides g = Guides.parse("<p>x</p>" + STRATEGY + TASK);
		assertEquals("Kree'arra/Strategies", g.getStrategyPage());
		assertEquals("Slayer task/Hellhounds", g.getTaskPage());
		assertEquals(Arrays.asList("Kree'arra/Strategies", "Slayer task/Hellhounds"), g.pages());
	}

	@Test
	public void ignoresOtherLinksToGuides()
	{
		// A strategy page merely linked from prose isn't this monster's guide.
		String prose = "<p>See <a href=\"/w/Cerberus/Strategies\" title=\"Cerberus/Strategies\">strategy guide</a>.</p>";
		assertSame(Guides.NONE, Guides.parse(prose));
		assertNull(Guides.parse(null));
		assertNull(Guides.parse(TASK).getStrategyPage());
	}
}
