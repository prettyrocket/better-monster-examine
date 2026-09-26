package com.bettermonsterexamine.slayer;

import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * One recommended-equipment setup from the wiki — a style ("Melee", "Phase 2 Magic") with its slots,
 * each slot's options ranked best first as the wiki lists them.
 */
@Getter
@RequiredArgsConstructor
public class GearSetup
{
	/** The wiki page the setup comes from, e.g. {@code "Slayer task/Abyssal demons"}. */
	private final String page;
	/** The setup's own label; blank when the page has a single untitled setup. */
	private final String style;
	private final List<Slot> slots;

	@Getter
	@RequiredArgsConstructor
	public static class Slot
	{
		/** The wiki's slot key: {@code head}, {@code weapon}, {@code special}, … */
		private final String name;
		private final List<Option> options;
	}

	/** One ranked choice for a slot — usually one item, sometimes interchangeable ones ("A / B"). */
	@Getter
	@RequiredArgsConstructor
	public static class Option
	{
		private final List<Item> items;
		/** A qualifier the wiki attaches, e.g. {@code "(on task)"}; blank when there is none. */
		private final String note;
	}

	@Getter
	@RequiredArgsConstructor
	public static class Item
	{
		/** The link text the wiki shows. */
		private final String label;
		/** The wiki page the item links to. */
		private final String link;
		/**
		 * The item's inventory-image name, which names the actual item more reliably than the link
		 * does: a link can point at a disambiguation page ("Mitre") where the image is the item
		 * ("Saradomin mitre"). Used to resolve the client item id.
		 */
		private final String imageName;
	}
}
