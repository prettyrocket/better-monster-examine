package com.bettermonsterexamine;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.util.ImageUtil;

/**
 * Loads and holds the stat icons used by the side panel (attack-type, skill, and
 * equipment-bonus icons bundled under {@code resources/}).
 */
@Singleton
public class MonsterIcons
{
	/** The Slayer masters we bundle a chathead for (keyed by the lower-case name Bucket uses). */
	static final String[] SLAYER_MASTERS =
		{"turael", "spria", "mazchna", "vannaka", "chaeldar", "nieve", "steve", "duradel", "kuradal", "konar", "krystilia"};

	final BufferedImage stabIcon;
	final BufferedImage crushIcon;
	final BufferedImage slashIcon;
	final BufferedImage standardIcon;
	final BufferedImage heavyIcon;
	final BufferedImage lightIcon;
	final BufferedImage elementalIcon;
	final BufferedImage magicIcon;
	final BufferedImage fireIcon;
	final BufferedImage waterIcon;
	final BufferedImage airIcon;
	final BufferedImage earthIcon;
	final BufferedImage attackIcon;
	final BufferedImage strengthIcon;
	final BufferedImage defenceIcon;
	final BufferedImage hitpointsIcon;
	final BufferedImage rangedIcon;
	final BufferedImage magicDamageIcon;
	final BufferedImage rangedStrengthIcon;
	final BufferedImage magicDefenceIcon;
	final BufferedImage slayerIcon;
	final BufferedImage slayerXpIcon;
	private final Map<String, BufferedImage> masterIcons = new HashMap<>();

	@Inject
	MonsterIcons()
	{
		stabIcon = ImageUtil.loadImageResource(getClass(), "/White_dagger.png");
		crushIcon = ImageUtil.loadImageResource(getClass(), "/White_warhammer.png");
		slashIcon = ImageUtil.loadImageResource(getClass(), "/White_scimitar.png");
		standardIcon = ImageUtil.loadImageResource(getClass(), "/Steel_arrow_5.png");
		heavyIcon = ImageUtil.loadImageResource(getClass(), "/Steel_bolts_5.png");
		lightIcon = ImageUtil.loadImageResource(getClass(), "/Steel_dart.png");
		elementalIcon = ImageUtil.loadImageResource(getClass(), "/Pure_essence.png");
		magicIcon = ImageUtil.loadImageResource(getClass(), "/Magic_icon.png");
		fireIcon = ImageUtil.loadImageResource(getClass(), "/Fire_rune.png");
		waterIcon = ImageUtil.loadImageResource(getClass(), "/Water_rune.png");
		airIcon = ImageUtil.loadImageResource(getClass(), "/Air_rune.png");
		earthIcon = ImageUtil.loadImageResource(getClass(), "/Earth_rune.png");
		attackIcon = ImageUtil.loadImageResource(getClass(), "/Attack_icon.png");
		strengthIcon = ImageUtil.loadImageResource(getClass(), "/Strength_icon.png");
		defenceIcon = ImageUtil.loadImageResource(getClass(), "/Defence_icon.png");
		hitpointsIcon = ImageUtil.loadImageResource(getClass(), "/Hitpoints_icon.png");
		rangedIcon = ImageUtil.loadImageResource(getClass(), "/Ranged_icon.png");
		magicDamageIcon = ImageUtil.loadImageResource(getClass(), "/Magic_Damage_icon.png");
		rangedStrengthIcon = ImageUtil.loadImageResource(getClass(), "/Ranged_Strength_icon.png");
		magicDefenceIcon = ImageUtil.loadImageResource(getClass(), "/Magic_defence_icon.png");
		slayerIcon = ImageUtil.loadImageResource(getClass(), "/Slayer_icon.png");
		slayerXpIcon = ImageUtil.loadImageResource(getClass(), "/Antique_lamp.png");
		for (String master : SLAYER_MASTERS)
		{
			masterIcons.put(master, ImageUtil.loadImageResource(getClass(), "/slayer/" + master + ".png"));
		}
	}

	/** The chathead for a Slayer master (by its lower-case name), or null when we bundle none. */
	BufferedImage masterIcon(String master)
	{
		return master == null ? null : masterIcons.get(master);
	}

	public BufferedImage getElementalWeaknessIcon(String elementalWeakness)
	{
		if (elementalWeakness == null)
		{
			return elementalIcon;
		}
		switch (elementalWeakness.toLowerCase(Locale.ROOT))
		{
			case "air":
				return airIcon;
			case "water":
				return waterIcon;
			case "fire":
				return fireIcon;
			case "earth":
				return earthIcon;
			default:
				return elementalIcon;
		}
	}
}
