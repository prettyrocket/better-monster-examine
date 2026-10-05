package com.bettermonsterexamine;

import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The wikitext shapes {@link InfoboxLevels} has to recover a level from — driven by Vardorvis, the
 * one monster whose levels Bucket cannot carry (its Strength and Defence are HP-scaling ranges, so
 * the wiki's {@code tonumber()} bucket write drops them and the plugin rendered a dash).
 */
public class InfoboxLevelsTest
{
	/** Vardorvis, trimmed to the parts that matter: three versions, ranged str/def, a shared footnote. */
	private static final String VARDORVIS = String.join("\n",
		"{{Otheruses|the Deadman Mode variant|Vardorvis (Deadman)|def=no}}",
		"{{Infobox Monster",
		"|version1 = Post-quest",
		"|version2 = Awakened",
		"|version3 = Quest",
		"|max hit1 = 32-43 ([[Melee]]){{efn|name=def|Scales linearly with Vardorvis' remaining HP.}}, 35 (axes)",
		"|hitpoints1 = 700",
		"|att1 = 280",
		"|att2 = 420",
		"|att3 = 260",
		"|str1 = 270-<br />360{{efn|name=def}}",
		"|str2 = 391-<br />522{{efn|name=def}}",
		"|str3 = 210-<br />280{{efn|name=def}}",
		"|def1 = 215-<br />145{{efn|name=def}}",
		"|def2 = 268-<br />181{{efn|name=def}}",
		"|def3 = 180-<br />130{{efn|name=def}}",
		"|mage1 = 215",
		"|range = 0",
		"}}");

	@Test
	public void recoversRangedLevelsPerVersion()
	{
		Map<String, Map<String, InfoboxLevels.LevelText>> levels = InfoboxLevels.parse(VARDORVIS);

		assertEquals("270-360", levels.get("post-quest").get("strength_level").getValue());
		assertEquals("215-145", levels.get("post-quest").get("defence_level").getValue());
		assertEquals("391-522", levels.get("awakened").get("strength_level").getValue());
		assertEquals("268-181", levels.get("awakened").get("defence_level").getValue());
		assertEquals("210-280", levels.get("quest").get("strength_level").getValue());
		assertEquals("180-130", levels.get("quest").get("defence_level").getValue());
	}

	/** The wiki's own footnote is what makes a Defence counting *down* legible, so it must come through. */
	@Test
	public void carriesTheFootnoteExplainingTheRange()
	{
		Map<String, Map<String, InfoboxLevels.LevelText>> levels = InfoboxLevels.parse(VARDORVIS);

		assertEquals("Scales linearly with Vardorvis' remaining HP.",
			levels.get("post-quest").get("defence_level").getNote());
		// The note is defined once (on max hit) and referenced by name from every level.
		assertEquals("Scales linearly with Vardorvis' remaining HP.",
			levels.get("awakened").get("strength_level").getNote());
	}

	/** Levels Bucket already carries are not worth recovering — only the ones it dropped. */
	@Test
	public void skipsPlainIntegerLevels()
	{
		Map<String, Map<String, InfoboxLevels.LevelText>> levels = InfoboxLevels.parse(VARDORVIS);

		assertNull(levels.get("post-quest").get("attack_level"));
		assertNull(levels.get("post-quest").get("magic_level"));
		// "|range = 0" is a real 0 the Bucket row carries, not a hole.
		assertNull(levels.get("post-quest").get("ranged_level"));
	}

	/**
	 * A blank level is genuinely unknown on the wiki (Whale, Guard, Reef snake — most of what Bucket
	 * omits). Those must keep rendering as a dash rather than as an empty value.
	 */
	@Test
	public void ignoresBlankLevels()
	{
		Map<String, Map<String, InfoboxLevels.LevelText>> levels = InfoboxLevels.parse(String.join("\n",
			"{{Infobox Monster",
			"|att = 50",
			"|str =",
			"|def = 100",
			"}}"));

		assertNull(levels.getOrDefault("", java.util.Collections.emptyMap()).get("strength_level"));
	}

	/** A page with no versions keys its levels under the single unnamed form. */
	@Test
	public void handlesAnUnversionedInfobox()
	{
		Map<String, Map<String, InfoboxLevels.LevelText>> levels = InfoboxLevels.parse(String.join("\n",
			"{{Infobox Monster",
			"|str = 100-200",
			"}}"));

		assertEquals("100-200", levels.get("").get("strength_level").getValue());
	}

	/** An en/em dash reads badly at panel size, so a range is normalised to a plain hyphen. */
	@Test
	public void normalisesDashesAndDropsThousandsCommas()
	{
		Map<String, Map<String, InfoboxLevels.LevelText>> levels = InfoboxLevels.parse(String.join("\n",
			"{{Infobox Monster",
			"|str = 1,200—1,500",
			"}}"));

		assertEquals("1200-1500", levels.get("").get("strength_level").getValue());
	}

	/** Only the infobox's own parameters count — {{Otheruses|…|def=no}} above it is not a Defence. */
	@Test
	public void ignoresParametersOutsideTheInfobox()
	{
		Map<String, Map<String, InfoboxLevels.LevelText>> levels = InfoboxLevels.parse(VARDORVIS);

		for (Map<String, InfoboxLevels.LevelText> version : levels.values())
		{
			InfoboxLevels.LevelText def = version.get("defence_level");
			assertTrue("def picked up {{Otheruses|def=no}}", def == null || !def.getValue().equals("no"));
		}
	}

	/** Bucket stores a non-numeric attack speed as 0, so the wiki's word is recovered. */
	@Test
	public void recoversANonNumericAttackSpeed()
	{
		Map<String, Map<String, InfoboxLevels.LevelText>> levels = InfoboxLevels.parse(String.join("\n",
			"{{Infobox Monster",
			"|version1 = Normal",
			"|version2 = Weakened",
			"|attack speed1 = 5",
			"|attack speed2 = Varies",
			"}}"));

		assertNull(levels.getOrDefault("normal", java.util.Collections.emptyMap()).get("attack_speed"));
		assertEquals("Varies", levels.get("weakened").get("attack_speed").getValue());
	}

	/** An impling's "N/A" means it doesn't attack: a dash, not the word. */
	@Test
	public void readsTheWikisNoValuePlaceholderAsAbsent()
	{
		for (String placeholder : new String[]{"N/A", "None", "No"})
		{
			Map<String, Map<String, InfoboxLevels.LevelText>> levels = InfoboxLevels.parse(
				"{{Infobox Monster\n|attack speed = " + placeholder + "\n}}");

			assertNull(placeholder, levels.getOrDefault("", java.util.Collections.emptyMap()).get("attack_speed"));
		}
	}

	/** Bucket never carries these, so even a plain "No" or "50" is kept. */
	@Test
	public void keepsAggressiveAndRespawnEvenWhenPlain()
	{
		Map<String, Map<String, InfoboxLevels.LevelText>> levels = InfoboxLevels.parse(String.join("\n",
			"{{Infobox Monster",
			"|aggressive = No",
			"|respawn = 50",
			"|att = 1",
			"}}"));

		assertEquals("No", levels.get("").get("aggressive").getValue());
		assertEquals("50", levels.get("").get("respawn").getValue());
		assertNull(levels.get("").get("attack_level"));
	}

	@Test
	public void readsAggressivePerVersion()
	{
		Map<String, Map<String, InfoboxLevels.LevelText>> levels = InfoboxLevels.parse(String.join("\n",
			"{{Infobox Monster",
			"|version1 = Surface",
			"|version2 = Lair",
			"|aggressive1 = No",
			"|aggressive2 = Yes",
			"|respawn = 25",
			"}}"));

		assertEquals("No", levels.get("surface").get("aggressive").getValue());
		assertEquals("Yes", levels.get("lair").get("aggressive").getValue());
		assertEquals("25", levels.get("lair").get("respawn").getValue());
	}

	/** Conditional wording keeps its spaces and link labels; a citation and a line break don't survive as text. */
	@Test
	public void cleansConditionalText()
	{
		Map<String, Map<String, InfoboxLevels.LevelText>> levels = InfoboxLevels.parse(String.join("\n",
			"{{Infobox Monster",
			"|aggressive = Yes (unless the player is holding a [[monkey greegree]])<ref>Tested.</ref>",
			"|respawn = 36 ticks,<br/>or 13 with a [[cowbell amulet|cowbell]]",
			"}}"));

		assertEquals("Yes (unless the player is holding a monkey greegree)", levels.get("").get("aggressive").getValue());
		assertEquals("36 ticks, or 13 with a cowbell", levels.get("").get("respawn").getValue());
	}

	@Test
	public void readsABlankOrNotApplicableTextValueAsAbsent()
	{
		Map<String, Map<String, InfoboxLevels.LevelText>> levels = InfoboxLevels.parse(String.join("\n",
			"{{Infobox Monster",
			"|aggressive = N/A",
			"|respawn = ",
			"}}"));

		assertTrue(levels.isEmpty());
	}

	@Test
	public void toleratesAPageWithNoInfobox()
	{
		assertTrue(InfoboxLevels.parse("Just prose.").isEmpty());
		assertTrue(InfoboxLevels.parse(null).isEmpty());
	}
}
