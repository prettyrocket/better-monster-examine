package com.bettermonsterexamine;

import java.util.function.BooleanSupplier;

/**
 * Decides what the in-game overlay shows: a Stats click shows a monster or, repeated, closes it;
 * the side panel mirrors its selection in; and a monster the user closed stays closed until they
 * ask for it again, rather than being re-fed by the panel.
 *
 * <p>Called from the client thread (menu clicks, config), the EDT (panel selection) and the mouse
 * thread (the close button), so the keys are volatile.
 */
class OverlayController
{
	private final MonsterCardOverlay overlay;
	private final BooleanSupplier overlayIsTarget;
	// The monster on screen, or null when hidden.
	private volatile String shownKey;
	// The monster the user closed, which the panel's mirroring must not reopen.
	private volatile String dismissedKey;

	OverlayController(MonsterCardOverlay overlay, BooleanSupplier overlayIsTarget)
	{
		this.overlay = overlay;
		this.overlayIsTarget = overlayIsTarget;
	}

	/** Show {@code m}, or close it if it's already the monster on screen. */
	void toggle(MonsterData m)
	{
		if (key(m).equals(shownKey))
		{
			dismiss();
			return;
		}
		show(m);
	}

	/** Show {@code m} from its first tab, whatever is on screen now. */
	void show(MonsterData m)
	{
		overlay.setMonster(m);
		shownKey = key(m);
		dismissedKey = null;
	}

	/**
	 * Follow the side panel's selection, when the overlay is a render target. A monster the user
	 * closed stays closed while it remains selected.
	 */
	void mirror(MonsterData m)
	{
		if (m == null || !overlayIsTarget.getAsBoolean())
		{
			return;
		}
		String key = key(m);
		if (key.equals(dismissedKey) || key.equals(shownKey))
		{
			return;
		}
		overlay.setMonster(m);
		shownKey = key;
		dismissedKey = null;
	}

	/** Close the overlay at the user's request, remembering the monster so it doesn't reopen. */
	void dismiss()
	{
		overlay.clear();
		dismissedKey = shownKey;
		shownKey = null;
	}

	/** Clear the overlay for a reason that isn't the user's, forgetting any dismissal. */
	void hide()
	{
		overlay.clear();
		shownKey = null;
		dismissedKey = null;
	}

	private static String key(MonsterData m)
	{
		return m.getName() + ' ' + m.getVersion();
	}
}
