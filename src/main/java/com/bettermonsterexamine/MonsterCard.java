package com.bettermonsterexamine;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.function.IntSupplier;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import static com.bettermonsterexamine.PanelStyle.block;
import static com.bettermonsterexamine.PanelStyle.capHeight;
import static com.bettermonsterexamine.PanelStyle.headerLabel;
import static com.bettermonsterexamine.PanelStyle.rowX;
import static com.bettermonsterexamine.PanelStyle.sectionHeader;
import static com.bettermonsterexamine.PanelStyle.wrappedLabel;

/**
 * The wiki-style stats infobox body, as a self-contained Swing component: the attribute / combat /
 * max-hit / stat / immunity / slayer blocks, colour-coding player-relevant values via
 * {@link StatColors}. The monster-identity header (name, variant selector, links) is drawn
 * separately by {@link MonsterHeader}, which sits above the Stats|Drops tab strip so it stays put
 * while this body swaps with the drops list.
 */
class MonsterCard extends JPanel
{
	/** Uniform on-screen size every stat-grid icon is scaled to. */
	private static final int ICON_BOX = 22;

	private final MonsterIcons icons;
	private final BetterMonsterExamineConfig config;
	private final IntSupplier playerHpLevel;
	private final IntSupplier playerSlayerLevel;

	MonsterCard(MonsterIcons icons, BetterMonsterExamineConfig config,
		IntSupplier playerHpLevel, IntSupplier playerSlayerLevel)
	{
		this.icons = icons;
		this.config = config;
		this.playerHpLevel = playerHpLevel;
		this.playerSlayerLevel = playerSlayerLevel;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setAlignmentX(LEFT_ALIGNMENT);
	}

	/** Render the stat blocks for {@code m} (the header is drawn separately by {@link MonsterHeader}). */
	void show(MonsterData m)
	{
		removeAll();
		MonsterStats stats = new MonsterStats(m, config.statHighlighting(), playerHpLevel.getAsInt(), playerSlayerLevel.getAsInt());
		buildWiki(stats);
		revalidate();
		repaint();
	}

	/** Replace the card with a plain wrapping message (e.g. the empty-state hint). */
	void showMessage(String text)
	{
		removeAll();
		JLabel l = new JLabel("<html><body style='width:180px'>" + text + "</body></html>");
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		l.setAlignmentX(LEFT_ALIGNMENT);
		add(l);
		revalidate();
		repaint();
	}

	void clear()
	{
		removeAll();
		revalidate();
		repaint();
	}

	// ---- Wiki: faithful to the OSRS Wiki monster infobox --------------------

	private void buildWiki(MonsterStats stats)
	{
		// ATTRIBUTES — size/attributes/slayer/flat armour/XP bonus/poisonous.
		JPanel props = block();
		props.add(sectionHeader("Attributes"));
		boolean anyProp = false;
		// Size and attributes share one line, size first: e.g. "7x7, Draconic, Undead, Fiery".
		// The box header already says "Attributes", so the names need no label of their own.
		String sizeAttr = stats.sizeAttr();
		if (sizeAttr != null)
		{
			props.add(wrappedLabel(sizeAttr, Color.WHITE, false));
			anyProp = true;
		}
		MonsterStats.StatField flatArmour = stats.flatArmour();
		if (flatArmour != null)
		{
			props.add(kv("Flat armour", flatArmour.value(), resolve(flatArmour.role()), flatArmour.tooltip()));
			anyProp = true;
		}
		MonsterStats.StatField xp = stats.xpBonus();
		if (xp != null)
		{
			props.add(kv("XP bonus", xp.value(), resolve(xp.role())));
			anyProp = true;
		}
		MonsterStats.StatField pois = stats.poisonous();
		if (pois != null)
		{
			props.add(kv("Poisonous", pois.value(), resolve(pois.role()), pois.tooltip()));
			anyProp = true;
		}
		if (anyProp)
		{
			capHeight(props);
			add(props);
			add(Box.createRigidArea(new Dimension(0, 6)));
		}

		// COMBAT INFO — attack style + speed (dataset).
		JPanel combatInfo = block();
		combatInfo.add(sectionHeader("Combat info"));
		combatInfo.add(kvWrappedRight("Attack style", stats.attackStyle()));
		combatInfo.add(kv("Attack speed", stats.attackSpeed(), Color.WHITE));
		capHeight(combatInfo);
		add(combatInfo);
		add(Box.createRigidArea(new Dimension(0, 6)));

		// MAX HIT — its own box; multi-hit monsters list several values, one per line. Values
		// above the player's Hitpoints level are flagged red (it could take more than your level).
		JPanel maxHit = block();
		maxHit.add(sectionHeader("Max hit"));
		maxHit.add(maxHitLabel(stats.maxHits()));
		capHeight(maxHit);
		add(maxHit);
		add(Box.createRigidArea(new Dimension(0, 6)));

		// Combat stats: the monster's six levels as an icon-over-value row
		List<MonsterStats.StatField> combat = stats.combatLevels();
		if (!combat.isEmpty())
		{
			add(levelBlock("Combat stats",
				new BufferedImage[]{icons.hitpointsIcon, icons.attackIcon, icons.strengthIcon, icons.defenceIcon, icons.magicIcon, icons.rangedIcon},
				combat,
				new String[]{"Hitpoints", "Attack", "Strength", "Defence", "Magic", "Ranged"}));
			add(Box.createRigidArea(new Dimension(0, 6)));
		}

		// Aggressive stats (order: Attack, Strength, Magic, Magic str, Ranged, Ranged str)
		List<String> off = stats.offensiveBonuses();
		if (!off.isEmpty())
		{
			add(gridBlock("Aggressive stats",
				new BufferedImage[]{icons.attackIcon, icons.strengthIcon, icons.magicIcon, icons.magicDamageIcon, icons.rangedIcon, icons.rangedStrengthIcon},
				off.toArray(new String[0]),
				new String[]{"Attack", "Strength", "Magic", "Magic damage", "Ranged", "Ranged strength"}));
			add(Box.createRigidArea(new Dimension(0, 6)));
		}

		// Defensive bonuses, grouped like the wiki
		if (stats.hasDefensive())
		{
			add(gridBlock("Melee defence",
				new BufferedImage[]{icons.stabIcon, icons.slashIcon, icons.crushIcon},
				stats.meleeDefence().toArray(new String[0]),
				new String[]{"Stab", "Slash", "Crush"}));
			add(Box.createRigidArea(new Dimension(0, 6)));

			String element = stats.weaknessElement();
			String weakLabel = element != null ? StatFormat.cap(element) + " weakness" : "Elemental weakness";
			add(gridBlock("Magic defence",
				new BufferedImage[]{icons.magicDefenceIcon, weaknessIcon(element)},
				new String[]{stats.magicDefence(), stats.weaknessSeverity()},
				new String[]{"Magic defence", weakLabel}));
			add(Box.createRigidArea(new Dimension(0, 6)));

			add(gridBlock("Ranged defence",
				new BufferedImage[]{icons.lightIcon, icons.standardIcon, icons.heavyIcon},
				stats.rangedDefence().toArray(new String[0]),
				new String[]{"Light", "Standard", "Heavy"}));
		}

		// Immunities — burn / cannon / thrall, all from Bucket.
		JPanel imm = block();
		imm.add(sectionHeader("Immunities"));
		boolean anyImm = false;
		String burn = stats.burn();
		if (burn != null)
		{
			imm.add(kv("Burn", burn, Color.WHITE));
			anyImm = true;
		}
		MonsterStats.StatField cannon = stats.cannon();
		if (cannon != null)
		{
			imm.add(kv("Cannon", cannon.value(), resolve(cannon.role())));
			anyImm = true;
		}
		MonsterStats.StatField thrall = stats.thrall();
		if (thrall != null)
		{
			imm.add(kv("Thrall", thrall.value(), resolve(thrall.role())));
			anyImm = true;
		}
		if (anyImm)
		{
			add(Box.createRigidArea(new Dimension(0, 6)));
			capHeight(imm);
			add(imm);
		}

		// SLAYER — last block, only for Slayer targets: the required level (red when above your
		// Slayer level) sits inline on the header row next to the Slayer icon; XP is its own line;
		// the assignment categories are plain text and the masters show as chatheads.
		if (stats.slayerMonster())
		{
			JPanel slayer = block();

			MonsterStats.StatField req = stats.slayerRequirement();
			String reqTip = req.tooltip() != null ? req.tooltip() : "Slayer level";
			JPanel head = rowX();
			head.add(headerLabel("Slayer"));
			head.add(Box.createHorizontalGlue());
			JLabel slayerLvlIcon = new JLabel(uniformIcon(icons.slayerIcon, 16));
			slayerLvlIcon.setToolTipText(reqTip);
			JLabel lvl = new JLabel(req.value());
			lvl.setFont(FontManager.getRunescapeSmallFont());
			lvl.setForeground(resolve(req.role()));
			lvl.setToolTipText(reqTip);
			head.add(slayerLvlIcon);
			head.add(Box.createRigidArea(new Dimension(3, 0)));
			head.add(lvl);
			capHeight(head);
			slayer.add(head);
			slayer.add(Box.createRigidArea(new Dimension(0, 3)));

			String slayerXp = stats.slayerXp();
			if (slayerXp != null)
			{
				slayer.add(kv("Slayer XP", slayerXp, Color.WHITE));
			}

			List<String> categories = stats.slayerCategories();
			if (!categories.isEmpty())
			{
				slayer.add(kvWrappedRight("Category", String.join(", ", categories)));
			}

			List<String> masters = stats.slayerMasters();
			if (!masters.isEmpty())
			{
				slayer.add(Box.createRigidArea(new Dimension(0, 4)));
				slayer.add(caption("Assigned by"));
				slayer.add(masterIconStrip(masters));
			}

			capHeight(slayer);
			add(Box.createRigidArea(new Dimension(0, 6)));
			add(slayer);
		}
	}

	/**
	 * The combat-levels grid. Same cells as {@link #gridBlock}, but a level can carry a footnote:
	 * the few Bucket can't hold render as the wiki's range ("270-360"), and the hover then explains
	 * why — Vardorvis' Defence counting down as he loses HP is a mechanic, not a typo.
	 */
	private JPanel levelBlock(String title, BufferedImage[] cellIcons, List<MonsterStats.StatField> levels,
		String[] labels)
	{
		String[] values = new String[levels.size()];
		String[] tips = new String[levels.size()];
		for (int i = 0; i < levels.size(); i++)
		{
			MonsterStats.StatField level = levels.get(i);
			values[i] = level.value();
			tips[i] = level.tooltip() == null ? labels[i] : labels[i] + " — " + level.tooltip();
		}
		return gridBlock(title, cellIcons, values, tips);
	}

	/** A titled block whose stats are laid out as icon-over-value cells (wiki style). */
	private JPanel gridBlock(String title, BufferedImage[] cellIcons, String[] values, String[] labels)
	{
		JPanel b = block();
		b.add(sectionHeader(title));

		// GridLayout gives every cell an equal column and centres its content, so groups
		// with few values (melee/magic defence) spread evenly across the width (space-around).
		JPanel grid = new JPanel(new GridLayout(1, cellIcons.length, 4, 0));
		grid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		grid.setAlignmentX(LEFT_ALIGNMENT);
		for (int i = 0; i < cellIcons.length; i++)
		{
			grid.add(statCell(cellIcons[i], values[i], labels[i]));
		}
		capHeight(grid);
		b.add(grid);
		b.setAlignmentX(LEFT_ALIGNMENT);
		return b;
	}

	private JComponent statCell(BufferedImage icon, String value, String tooltip)
	{
		return statCell(icon, value, Color.WHITE, tooltip);
	}

	private JComponent statCell(BufferedImage icon, String value, Color valueColor, String tooltip)
	{
		JPanel cell = new JPanel();
		cell.setLayout(new BoxLayout(cell, BoxLayout.Y_AXIS));
		cell.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		// Hovering the cell just names the stat ("Strength", "Stab", "Light").
		cell.setToolTipText(tooltip);

		// Every icon is normalised to the same box, so cells share an icon height and the
		// values below them line up on a common baseline regardless of native icon size.
		JLabel ic = new JLabel(uniformIcon(icon, ICON_BOX));
		ic.setAlignmentX(CENTER_ALIGNMENT);

		JLabel vl = new JLabel(value);
		vl.setFont(FontManager.getRunescapeSmallFont());
		vl.setForeground(valueColor);
		vl.setAlignmentX(CENTER_ALIGNMENT);

		cell.add(ic);
		cell.add(Box.createRigidArea(new Dimension(0, 3)));
		cell.add(vl);
		return cell;
	}

	/** A small left-aligned caption label (the light-grey key style used for sub-labels). */
	private JLabel caption(String text)
	{
		JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		l.setAlignmentX(LEFT_ALIGNMENT);
		return l;
	}

	/** The Slayer masters as a left-flowing row of chatheads (name on hover); text fallback if unbundled. */
	private JPanel masterIconStrip(List<String> masters)
	{
		JPanel strip = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
		strip.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		strip.setAlignmentX(LEFT_ALIGNMENT);
		for (String master : masters)
		{
			String name = StatFormat.masterName(master);
			BufferedImage icon = icons.masterIcon(master);
			JLabel l = icon != null ? new JLabel(uniformIcon(icon, ICON_BOX)) : new JLabel(name);
			if (icon == null)
			{
				l.setFont(FontManager.getRunescapeSmallFont());
				l.setForeground(Color.WHITE);
			}
			l.setToolTipText(name);
			strip.add(l);
		}
		capHeight(strip);
		return strip;
	}

	/** Scale to fit a fixed square (preserving aspect) and centre it, so all icons match. */
	private static ImageIcon uniformIcon(BufferedImage img, int box)
	{
		double scale = Math.min((double) box / img.getWidth(), (double) box / img.getHeight());
		int w = Math.max(1, (int) Math.round(img.getWidth() * scale));
		int h = Math.max(1, (int) Math.round(img.getHeight() * scale));
		Image scaled = img.getScaledInstance(w, h, Image.SCALE_SMOOTH);
		BufferedImage canvas = new BufferedImage(box, box, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = canvas.createGraphics();
		g.drawImage(scaled, (box - w) / 2, (box - h) / 2, null);
		g.dispose();
		return new ImageIcon(canvas);
	}

	private BufferedImage weaknessIcon(String element)
	{
		if (element == null)
		{
			return icons.elementalIcon;
		}
		return icons.getElementalWeaknessIcon(StatFormat.cap(element));
	}

	// --------------------------------------------------------------- layout helpers

	private JPanel kv(String k, String v, Color valueColor)
	{
		return kv(k, v, valueColor, null);
	}

	private JPanel kv(String k, String v, Color valueColor, String tooltip)
	{
		JPanel r = rowX();
		JLabel kl = new JLabel(k);
		kl.setFont(FontManager.getRunescapeSmallFont());
		kl.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		JLabel vl = new JLabel(v);
		vl.setFont(FontManager.getRunescapeSmallFont());
		vl.setForeground(valueColor);
		// Tooltip on both labels (not just the row) so it shows wherever you hover the line.
		if (tooltip != null)
		{
			r.setToolTipText(tooltip);
			kl.setToolTipText(tooltip);
			vl.setToolTipText(tooltip);
		}
		r.add(kl);
		r.add(Box.createHorizontalGlue());
		r.add(vl);
		capHeight(r);
		return r;
	}

	/**
	 * Label on the left with a right-aligned value that wraps across as many lines as needed,
	 * kept tight (used for the attack-style list). The value fills the width left by the label.
	 */
	private JPanel kvWrappedRight(String k, String v)
	{
		JPanel r = new JPanel(new BorderLayout());
		r.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		r.setBorder(new EmptyBorder(1, 0, 1, 0));
		r.setAlignmentX(LEFT_ALIGNMENT);

		JLabel kl = new JLabel(k);
		kl.setFont(FontManager.getRunescapeSmallFont());
		kl.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		kl.setVerticalAlignment(JLabel.TOP);

		// The value takes a fixed body width on the right (BorderLayout.EAST honours it), so it
		// wraps and right-aligns within the space left by the label. A fixed width avoids relying
		// on the label's off-screen preferred size, which can measure short before it's realised.
		int valueW = 112;
		JLabel vl = new JLabel("<html><body style='width:" + valueW + "px; text-align: right'>"
			+ StatFormat.esc(v).replace("\n", "<br>") + "</body></html>");
		vl.setFont(FontManager.getRunescapeSmallFont());
		vl.setForeground(Color.WHITE);
		vl.setVerticalAlignment(JLabel.TOP);

		r.add(kl, BorderLayout.WEST);
		r.add(vl, BorderLayout.EAST);
		capHeight(r);
		return r;
	}

	/**
	 * The max-hit list as a wrapping label, with any line whose value exceeds the player's
	 * Hitpoints level flagged (red in Standard, orange + a warning sign in colour-blind mode).
	 * {@code hpLevel <= 0} (unknown) or the Off mode disables the highlight.
	 */
	private JLabel maxHitLabel(List<MonsterStats.MaxHitLine> lines)
	{
		boolean cb = config.statHighlighting() == HighlightMode.COLOUR_BLIND;
		String hex = cb ? "#e69f00" : "#ff4040";

		StringBuilder sb = new StringBuilder("<html><body style='width:200px'>");
		boolean anyOver = false;
		for (int i = 0; i < lines.size(); i++)
		{
			if (i > 0)
			{
				sb.append("<br>");
			}
			MonsterStats.MaxHitLine line = lines.get(i);
			if (line.overHp())
			{
				anyOver = true;
				// In colour-blind mode add a warning sign so the cue survives without colour.
				String text = StatFormat.esc(line.text()) + (cb ? " ⚠" : "");
				sb.append("<span style='color:").append(hex).append("'>").append(text).append("</span>");
			}
			else
			{
				sb.append(StatFormat.esc(line.text()));
			}
		}
		sb.append("</body></html>");

		JLabel l = new JLabel(sb.toString());
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(Color.WHITE);
		l.setAlignmentX(LEFT_ALIGNMENT);
		// The highlight marks a hit larger than your Hitpoints level.
		if (anyOver)
		{
			l.setToolTipText("Max hit exceeds your hitpoints level (" + playerHpLevel.getAsInt() + ").");
		}
		return l;
	}

	// ---- player-relevant colours (delegate to the shared palette) -----------

	/** Resolve a view-model {@link ColourRole} to a concrete colour for the active mode. */
	private Color resolve(ColourRole role)
	{
		return StatColors.resolve(role, config.statHighlighting());
	}
}
