package com.bettermonsterexamine;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class RenderTargetTest
{
	@Test
	public void panelShowsOnlyThePanel()
	{
		assertTrue(RenderTarget.PANEL.showsPanel());
		assertFalse(RenderTarget.PANEL.showsOverlay());
	}

	@Test
	public void overlayShowsOnlyTheOverlay()
	{
		assertFalse(RenderTarget.OVERLAY.showsPanel());
		assertTrue(RenderTarget.OVERLAY.showsOverlay());
	}

	@Test
	public void bothShowsEach()
	{
		assertTrue(RenderTarget.BOTH.showsPanel());
		assertTrue(RenderTarget.BOTH.showsOverlay());
	}
}
