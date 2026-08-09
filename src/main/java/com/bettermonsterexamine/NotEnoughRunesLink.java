package com.bettermonsterexamine;

import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginMessage;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;

/**
 * The outbound half of the Not Enough Runes link: hands an item off to that plugin's panel.
 *
 * <p>Plugin-hub plugins each load in their own classloader, parented to the client's, so NER's
 * classes are invisible from here — its plugin type can't be named, and a shared event class would
 * be a different {@code Class} on each side and never dispatch. The only channel is the core
 * {@link PluginMessage} event: a namespace, a name, and a map of core types. NER already subscribes
 * to {@code notenoughrunes}/{@code displayItemById} with an {@code Integer} {@code itemId}.
 *
 * <p>Posting when NER is absent is harmless (the event simply has no subscribers), so
 * {@link #isActive()} exists to decide what the UI should <em>offer</em>, not to make the post safe.
 */
@Slf4j
public class NotEnoughRunesLink
{
	/** Matched by name because the class itself can't be referenced across the classloader boundary. */
	private static final String PLUGIN_CLASS = "com.notenoughrunes.NotEnoughRunesPlugin";
	private static final String NAMESPACE = "notenoughrunes";
	private static final String DISPLAY_ITEM_BY_ID = "displayItemById";
	private static final String ITEM_ID = "itemId";

	private final EventBus eventBus;
	private final PluginManager pluginManager;
	private final BetterMonsterExamineConfig config;

	public NotEnoughRunesLink(EventBus eventBus, PluginManager pluginManager, BetterMonsterExamineConfig config)
	{
		this.eventBus = eventBus;
		this.pluginManager = pluginManager;
		this.config = config;
	}

	/**
	 * True when the link is switched on and NER is running, so a post would actually be received.
	 * Resolved per call rather than cached, so installing or enabling NER mid-session takes effect
	 * without a restart.
	 */
	public boolean isActive()
	{
		return config.notEnoughRunesLink() && isRunning();
	}

	/**
	 * Ask NER to show this item. No-op when the link is inactive.
	 *
	 * <p>Call from the EDT: {@link EventBus#post} runs subscribers inline on the calling thread, and
	 * NER's handler touches Swing.
	 */
	public void displayItem(int itemId)
	{
		if (!isActive())
		{
			return;
		}
		log.debug("Handing item {} to Not Enough Runes", itemId);
		Map<String, Object> data = new HashMap<>();
		data.put(ITEM_ID, itemId);
		eventBus.post(new PluginMessage(NAMESPACE, DISPLAY_ITEM_BY_ID, data));
	}

	/**
	 * Whether NER is loaded <em>and started</em>. Deliberately not {@code isPluginEnabled}, which only
	 * reads the "start this on boot" config flag — a plugin is registered with the event bus in
	 * {@code startPlugin}, so {@code isPluginActive} is what predicts delivery.
	 */
	private boolean isRunning()
	{
		// getPlugins() is a CopyOnWriteArrayList, so iterating it off the client thread is safe.
		for (Plugin plugin : pluginManager.getPlugins())
		{
			if (PLUGIN_CLASS.equals(plugin.getClass().getName()))
			{
				return pluginManager.isPluginActive(plugin);
			}
		}
		return false;
	}
}
