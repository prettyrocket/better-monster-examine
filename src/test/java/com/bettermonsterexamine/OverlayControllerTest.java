package com.bettermonsterexamine;

import com.google.gson.Gson;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

public class OverlayControllerTest
{
	private static final Gson GSON = new Gson();
	private static final MonsterData VORKATH = monster("Vorkath");
	private static final MonsterData ZULRAH = monster("Zulrah");

	private MonsterCardOverlay overlay;
	private boolean overlayIsTarget;
	private OverlayController controller;

	private static MonsterData monster(String name)
	{
		return GSON.fromJson("{\"name\":\"" + name + "\"}", MonsterData.class);
	}

	@Before
	public void setUp()
	{
		overlay = new MonsterCardOverlay(null, null, () -> -1, () -> -1, () -> -1);
		overlayIsTarget = true;
		controller = new OverlayController(overlay, () -> overlayIsTarget);
	}

	@Test
	public void secondToggleOfTheSameMonsterCloses()
	{
		controller.toggle(VORKATH);
		assertTrue(overlay.isShowing());
		controller.toggle(VORKATH);
		assertFalse(overlay.isShowing());
		controller.toggle(VORKATH);
		assertTrue(overlay.isShowing());
	}

	@Test
	public void toggleOfAnotherMonsterSwitchesRatherThanCloses()
	{
		controller.toggle(VORKATH);
		controller.toggle(ZULRAH);
		assertTrue(overlay.isShowing());
	}

	@Test
	public void mirrorDoesNotReopenADismissedMonster()
	{
		controller.show(VORKATH);
		controller.dismiss();
		controller.mirror(VORKATH);
		assertFalse(overlay.isShowing());
		controller.mirror(ZULRAH);
		assertTrue(overlay.isShowing());
	}

	@Test
	public void toggleClosingCountsAsADismissal()
	{
		controller.toggle(VORKATH);
		controller.toggle(VORKATH);
		controller.mirror(VORKATH);
		assertFalse(overlay.isShowing());
	}

	@Test
	public void showClearsADismissal()
	{
		controller.show(VORKATH);
		controller.dismiss();
		controller.show(VORKATH);
		controller.hide();
		controller.mirror(VORKATH);
		assertTrue(overlay.isShowing());
	}

	@Test
	public void hideForgetsTheDismissal()
	{
		controller.show(VORKATH);
		controller.dismiss();
		controller.hide();
		controller.mirror(VORKATH);
		assertTrue(overlay.isShowing());
	}

	@Test
	public void mirrorIsIgnoredWhenTheOverlayIsNotATarget()
	{
		overlayIsTarget = false;
		controller.mirror(VORKATH);
		assertFalse(overlay.isShowing());
	}
}
