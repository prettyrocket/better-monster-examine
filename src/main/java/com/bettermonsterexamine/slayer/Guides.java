package com.bettermonsterexamine.slayer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The guide pages a monster's article points at, read from the page's own message boxes ("This article
 * has a strategy guide", "This article has a Slayer task guide: Trolls"). The article names them
 * exactly, which beats deriving them from {@code slayer_category}: task pages don't follow one naming
 * rule ("Slayer task/Kurasks" for the Kurask category, "Slayer task/Suqah" for Suqahs).
 */
@Getter
@RequiredArgsConstructor
public class Guides
{
	public static final Guides NONE = new Guides(null, null);

	private static final Pattern STRATEGY = Pattern.compile(
		"This article has a <a href=\"[^\"]*\" title=\"([^\"]+)\">strategy guide</a>");
	private static final Pattern TASK = Pattern.compile(
		"This article has a Slayer task guide: <a href=\"[^\"]*\" title=\"([^\"]+)\"");

	/** e.g. {@code "Cerberus/Strategies"}; null when the article has none. */
	private final String strategyPage;
	/** e.g. {@code "Slayer task/Hellhounds"}; null when the article has none. */
	private final String taskPage;

	/** The guides a rendered article links; {@link #NONE} when it has neither, null with no HTML. */
	public static Guides parse(String html)
	{
		if (html == null)
		{
			return null;
		}
		String strategy = first(STRATEGY, html);
		String task = first(TASK, html);
		return strategy == null && task == null ? NONE : new Guides(strategy, task);
	}

	/** The pages to ask for recommended gear, the monster's own strategy guide first. */
	public List<String> pages()
	{
		List<String> out = new ArrayList<>();
		if (strategyPage != null)
		{
			out.add(strategyPage);
		}
		if (taskPage != null)
		{
			out.add(taskPage);
		}
		return out;
	}

	private static String first(Pattern p, String html)
	{
		Matcher m = p.matcher(html);
		return m.find() ? LocationParser.text(m.group(1)) : null;
	}
}
