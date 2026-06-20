package com.bettermonsterexamine;

import java.awt.image.BufferedImage;
import java.util.Locale;
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
	final BufferedImage maxHitIcon;
	final BufferedImage attackStyleIcon;
	final BufferedImage flatArmourIcon;
	final BufferedImage attackIcon;
	final BufferedImage strengthIcon;
	final BufferedImage defenceIcon;
	final BufferedImage hitpointsIcon;
	final BufferedImage rangedIcon;
	final BufferedImage magicDamageIcon;
	final BufferedImage rangedStrengthIcon;
	final BufferedImage magicDefenceIcon;

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
		maxHitIcon = ImageUtil.loadImageResource(getClass(), "/Damage_hitsplat_(max_hit).png");
		attackStyleIcon = ImageUtil.loadImageResource(getClass(), "/Combat_icon.png");
		flatArmourIcon = ImageUtil.loadImageResource(getClass(), "/Defence_icon.png");
		attackIcon = ImageUtil.loadImageResource(getClass(), "/Attack_icon.png");
		strengthIcon = ImageUtil.loadImageResource(getClass(), "/Strength_icon.png");
		defenceIcon = ImageUtil.loadImageResource(getClass(), "/Defence_icon.png");
		hitpointsIcon = ImageUtil.loadImageResource(getClass(), "/Hitpoints_icon.png");
		rangedIcon = ImageUtil.loadImageResource(getClass(), "/Ranged_icon.png");
		magicDamageIcon = ImageUtil.loadImageResource(getClass(), "/Magic_Damage_icon.png");
		rangedStrengthIcon = ImageUtil.loadImageResource(getClass(), "/Ranged_Strength_icon.png");
		magicDefenceIcon = ImageUtil.loadImageResource(getClass(), "/Magic_defence_icon.png");
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
