package com.bettermonsterexamine;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.LinkBrowser;

/**
 * The wiki-style stats infobox, as a self-contained Swing component. Given a monster, its
 * variants, and (possibly null) wiki fields, it builds the card — name + variant dropdown +
 * links header, then attribute / combat / max-hit / stat / immunity blocks — colour-coding
 * player-relevant values via {@link StatColors}. It owns only the visuals: favouriting and
 * variant switching are surfaced as callbacks so the host panel keeps that state.
 */
class MonsterCard extends JPanel
{
	private static final String DPS_CALC_URL = "https://tools.runescape.wiki/osrs-dps/";
	/** Uniform on-screen size every stat-grid icon is scaled to. */
	private static final int ICON_BOX = 22;

	private final MonsterIcons icons;
	private final BetterMonsterExamineConfig config;
	private final IntSupplier playerCombatLevel;
	private final IntSupplier playerHpLevel;
	private final Predicate<MonsterData> isFavorite;
	private final Consumer<MonsterData> onToggleFavorite;
	private final Consumer<MonsterData> onSelectVariant;

	MonsterCard(MonsterIcons icons, BetterMonsterExamineConfig config, IntSupplier playerCombatLevel,
		IntSupplier playerHpLevel, Predicate<MonsterData> isFavorite, Consumer<MonsterData> onToggleFavorite,
		Consumer<MonsterData> onSelectVariant)
	{
		this.icons = icons;
		this.config = config;
		this.playerCombatLevel = playerCombatLevel;
		this.playerHpLevel = playerHpLevel;
		this.isFavorite = isFavorite;
		this.onToggleFavorite = onToggleFavorite;
		this.onSelectVariant = onSelectVariant;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setAlignmentX(LEFT_ALIGNMENT);
	}

	/** Render {@code m}, with its {@code variants} (for the dropdown) and async {@code wi} fields. */
	void show(MonsterData m, List<MonsterData> variants, WikiInfo wi)
	{
		removeAll();
		add(header(m, variants, wi));
		add(Box.createRigidArea(new Dimension(0, 6)));
		buildWiki(m, wi);
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

	private JComponent header(MonsterData m, List<MonsterData> variants, WikiInfo wi)
	{
		JPanel block = block();

		// Name on the left, combat level on the right of the same row — smaller and colour-coded
		// against the player's level like the in-game monster hover.
		JPanel nameRow = rowX();
		JLabel name = new JLabel(m.getName());
		name.setFont(FontManager.getRunescapeBoldFont());
		name.setForeground(Color.WHITE);
		nameRow.add(name);
		// Favourite toggle sits right next to the name (only when the feature is enabled).
		if (config.enableHistory())
		{
			nameRow.add(Box.createRigidArea(new Dimension(4, 0)));
			nameRow.add(makeStarButton(m));
		}
		if (m.getLevel() > 0)
		{
			nameRow.add(Box.createHorizontalGlue());
			int pl = playerCombatLevel.getAsInt();
			// In colour-blind mode a level above yours also gets an up-triangle, since orange
			// alone can't be told apart from blue.
			boolean above = config.statHighlighting() == HighlightMode.COLOUR_BLIND
				&& pl > 0 && m.getLevel() > pl;
			JLabel lvl = new JLabel("(level-" + m.getLevel() + ")" + (above ? " ▲" : ""));
			lvl.setFont(FontManager.getRunescapeSmallFont());
			lvl.setForeground(levelColor(pl, m.getLevel()));
			if (above)
			{
				lvl.setToolTipText("Combat level above yours (" + pl + ").");
			}
			nameRow.add(lvl);
		}
		capHeight(nameRow);
		block.add(nameRow);

		// Examine text (wiki, async): subtle italic line directly under the name.
		String examine = wi != null ? wi.get("examine", m.getVersion()) : null;
		if (examine != null && !examine.trim().isEmpty())
		{
			block.add(Box.createRigidArea(new Dimension(0, 2)));
			block.add(wrappedLabel(examine.trim(), ColorScheme.LIGHT_GRAY_COLOR, true));
		}

		// Variant selector (only when >1 form shares the name)
		if (variants != null && variants.size() > 1)
		{
			JComboBox<String> combo = new JComboBox<>();
			for (MonsterData v : variants)
			{
				String v2 = (v.getVersion() == null || v.getVersion().isEmpty()) ? "Standard" : v.getVersion();
				combo.addItem(v2);
			}
			combo.setSelectedIndex(variants.indexOf(m));
			combo.setFont(FontManager.getRunescapeSmallFont());
			combo.setAlignmentX(LEFT_ALIGNMENT);
			combo.addActionListener(e ->
			{
				int i = combo.getSelectedIndex();
				// setSelectedIndex above runs before this listener is attached, so rebuilding the
				// card never re-fires it — only a real user change reaches the host.
				if (i >= 0 && i < variants.size())
				{
					onSelectVariant.accept(variants.get(i));
				}
			});
			capHeight(combo);
			block.add(Box.createRigidArea(new Dimension(0, 4)));
			block.add(combo);
		}

		// Wiki + DPS-calc links, side by side. DPS calc deep-links the monster by NPC id.
		JButton wikiBtn = new JButton("Wiki");
		wikiBtn.setFont(FontManager.getRunescapeSmallFont());
		wikiBtn.addActionListener(e -> LinkBrowser.browse(wikiUrl(m)));

		JButton dpsBtn = new JButton("DPS Calc");
		dpsBtn.setFont(FontManager.getRunescapeSmallFont());
		// The link only sets the target monster. Gear comes from the separate WikiSync plugin,
		// which the user loads by clicking the calc's own "RuneLite" button — not via our URL.
		dpsBtn.setToolTipText("<html>Opens the DPS calculator for this monster.<br>"
			+ "To load your current gear, click the 'RuneLite' button in the<br>"
			+ "calculator (requires the WikiSync plugin).</html>");
		dpsBtn.addActionListener(e -> LinkBrowser.browse(DPS_CALC_URL + "?monster=" + m.getId()));

		JPanel buttons = new JPanel(new GridLayout(1, 2, 4, 0));
		buttons.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		buttons.setAlignmentX(LEFT_ALIGNMENT);
		buttons.add(wikiBtn);
		buttons.add(dpsBtn);
		capHeight(buttons);
		block.add(Box.createRigidArea(new Dimension(0, 6)));
		block.add(buttons);

		capHeight(block);
		return block;
	}

	private JButton makeStarButton(MonsterData m)
	{
		boolean fav = isFavorite.test(m);
		JButton b = new JButton(fav ? "★" : "☆");
		b.setToolTipText(fav ? "Remove from favorites" : "Add to favorites");
		b.setForeground(fav ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
		b.setFocusable(false);
		b.setMargin(new Insets(0, 4, 0, 4));
		b.addActionListener(e -> onToggleFavorite.accept(m));
		return b;
	}

	private static String wikiUrl(MonsterData m)
	{
		return "https://oldschool.runescape.wiki/w/" + m.getName().replace(' ', '_');
	}

	// ---- Wiki: faithful to the OSRS Wiki monster infobox --------------------

	private void buildWiki(MonsterData m, WikiInfo wi)
	{
		MonsterData.Skills s = m.getSkills();
		MonsterData.Offensive o = m.getOffensive();
		MonsterData.Defensive d = m.getDefensive();
		String ver = m.getVersion();

		// ATTRIBUTES — dataset attributes/size/slayer/flat armour + wiki xp/aggressive/poisonous.
		JPanel props = block();
		props.add(sectionHeader("Attributes"));
		boolean anyProp = false;
		// Size and attributes share one line, size first: e.g. "7x7, Draconic, Undead, Fiery".
		// The box header already says "Attributes", so the names need no label of their own.
		String sizeText = m.getSize() > 0 ? m.getSize() + "x" + m.getSize() : null;
		String attrText = (m.getAttributes() != null && !m.getAttributes().isEmpty())
			? StatFormat.attributeNames(m.getAttributes()) : null;
		String sizeAttr = StatFormat.join(", ", sizeText, attrText);
		if (sizeAttr != null)
		{
			props.add(wrappedLabel(sizeAttr, Color.WHITE, false));
			anyProp = true;
		}
		if (m.isSlayerMonster())
		{
			props.add(kv("Slayer monster", "Yes", Color.WHITE));
			anyProp = true;
		}
		// Flat armour: a flat damage adjustment (negative means it takes extra), 0 for most
		// monsters — show whenever non-zero, signed so negatives like Naguas' -4 read right.
		if (d != null && d.getFlatArmour() != 0)
		{
			int fa = d.getFlatArmour();
			// Negative flat armour means the monster takes extra damage (good for you) → green;
			// positive means it shrugs damage off (bad for you) → red.
			props.add(kv("Flat armour", String.valueOf(fa),
				fa < 0 ? good() : danger(),
				fa < 0 ? "Takes extra flat damage per hit." : "Reduces damage taken per hit."));
			anyProp = true;
		}
		String xp = wi != null ? wi.get("xpbonus", ver) : null;
		if (xp != null && !xp.trim().isEmpty() && !StatFormat.isZero(xp))
		{
			String xpTrimmed = xp.trim();
			boolean xpPenalty = xpTrimmed.startsWith("-");
			props.add(kv("XP bonus", (xpPenalty ? "" : "+") + xpTrimmed + "%", xpPenalty ? danger() : good()));
			anyProp = true;
		}
		String aggr = wi != null ? wi.get("aggressive", ver) : null;
		if (aggr != null)
		{
			boolean yes = StatFormat.yes(aggr);
			props.add(kv("Aggressive", aggr.trim(), yes ? danger() : Color.WHITE,
				yes ? "Attacks on sight." : null));
			anyProp = true;
		}
		String pois = wi != null ? wi.get("poisonous", ver) : null;
		if (pois != null)
		{
			boolean yes = StatFormat.yes(pois);
			props.add(kv("Poisonous", pois.trim(), yes ? danger() : Color.WHITE,
				yes ? "Can poison you." : null));
			anyProp = true;
		}
		if (wi == null)
		{
			props.add(kv("", "loading wiki data…", ColorScheme.LIGHT_GRAY_COLOR));
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
		combatInfo.add(kvWrappedRight("Attack style", styleString(m)));
		combatInfo.add(kv("Attack speed", StatFormat.attackSpeed(m), Color.WHITE));
		capHeight(combatInfo);
		add(combatInfo);
		add(Box.createRigidArea(new Dimension(0, 6)));

		// MAX HIT — its own box; multi-hit monsters list several values, one per line. Values
		// above the player's Hitpoints level are flagged red (it could take more than your level).
		String wikiMax = wi != null ? wi.get("max hit", ver) : null;
		String maxHitText = wikiMax != null ? wikiMax.replace(", ", "\n") : StatFormat.nz(m.getMaxHit());
		JPanel maxHit = block();
		maxHit.add(sectionHeader("Max hit"));
		maxHit.add(maxHitLabel(maxHitText, playerHpLevel.getAsInt()));
		capHeight(maxHit);
		add(maxHit);
		add(Box.createRigidArea(new Dimension(0, 6)));

		// Combat stats: the monster's six levels as an icon-over-value row
		if (s != null)
		{
			JPanel combat = gridBlock("Combat stats",
				new BufferedImage[]{icons.hitpointsIcon, icons.attackIcon, icons.strengthIcon, icons.defenceIcon, icons.magicIcon, icons.rangedIcon},
				new String[]{StatFormat.num(s.getHp()), StatFormat.num(s.getAtk()), StatFormat.num(s.getStr()), StatFormat.num(s.getDef()), StatFormat.num(s.getMagic()), StatFormat.num(s.getRanged())},
				new String[]{"Hitpoints", "Attack", "Strength", "Defence", "Magic", "Ranged"});
			add(combat);
			add(Box.createRigidArea(new Dimension(0, 6)));
		}

		// Aggressive stats (order: Attack, Strength, Magic, Magic str, Ranged, Ranged str)
		if (o != null)
		{
			JPanel aggro = gridBlock("Aggressive stats",
				new BufferedImage[]{icons.attackIcon, icons.strengthIcon, icons.magicIcon, icons.magicDamageIcon, icons.rangedIcon, icons.rangedStrengthIcon},
				new String[]{StatFormat.bonus(o.getAtk()), StatFormat.bonus(o.getStr()), StatFormat.bonus(o.getMagic()), StatFormat.bonus(o.getMagicStr()), StatFormat.bonus(o.getRanged()), StatFormat.bonus(o.getRangedStr())},
				new String[]{"Attack", "Strength", "Magic", "Magic damage", "Ranged", "Ranged strength"});
			add(aggro);
			add(Box.createRigidArea(new Dimension(0, 6)));
		}

		// Defensive bonuses, grouped like the wiki
		if (d != null)
		{
			add(gridBlock("Melee defence",
				new BufferedImage[]{icons.stabIcon, icons.slashIcon, icons.crushIcon},
				new String[]{StatFormat.bonus(d.getStab()), StatFormat.bonus(d.getSlash()), StatFormat.bonus(d.getCrush())},
				new String[]{"Stab", "Slash", "Crush"}));
			add(Box.createRigidArea(new Dimension(0, 6)));

			String weakLabel = m.getWeakness() != null && m.getWeakness().getElement() != null
				? StatFormat.cap(m.getWeakness().getElement()) + " weakness" : "Elemental weakness";
			add(gridBlock("Magic defence",
				new BufferedImage[]{icons.magicDefenceIcon, weaknessIcon(m)},
				new String[]{StatFormat.bonus(d.getMagic()), m.getWeakness() != null ? m.getWeakness().getSeverity() + "%" : "—"},
				new String[]{"Magic defence", weakLabel}));
			add(Box.createRigidArea(new Dimension(0, 6)));

			add(gridBlock("Ranged defence",
				new BufferedImage[]{icons.lightIcon, icons.standardIcon, icons.heavyIcon},
				new String[]{StatFormat.bonus(d.getLight()), StatFormat.bonus(d.getStandard()), StatFormat.bonus(d.getHeavy())},
				new String[]{"Light", "Standard", "Heavy"}));
		}

		// Immunities — burn from the dataset, the rest (poison/venom/cannon/thrall) from wiki.
		JPanel imm = block();
		imm.add(sectionHeader("Immunities"));
		boolean anyImm = false;
		String burn = StatFormat.burnImmunity(m);
		if (burn != null)
		{
			imm.add(kv("Burn", burn, Color.WHITE));
			anyImm = true;
		}
		if (wi != null)
		{
			String poison = StatFormat.resistanceLabel(wi.get("poisonresistance", ver));
			if (poison != null)
			{
				imm.add(kv("Poison", poison, danger()));
				anyImm = true;
			}
			String venom = StatFormat.resistanceLabel(wi.get("venomresistance", ver));
			if (venom != null)
			{
				imm.add(kv("Venom", venom, danger()));
				anyImm = true;
			}
			if (StatFormat.yes(wi.get("immunecannon", ver)))
			{
				imm.add(kv("Cannon", "Immune", danger()));
				anyImm = true;
			}
			if (StatFormat.yes(wi.get("immunethrall", ver)))
			{
				imm.add(kv("Thrall", "Immune", danger()));
				anyImm = true;
			}
		}
		if (anyImm)
		{
			add(Box.createRigidArea(new Dimension(0, 6)));
			capHeight(imm);
			add(imm);
		}
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
		vl.setForeground(Color.WHITE);
		vl.setAlignmentX(CENTER_ALIGNMENT);

		cell.add(ic);
		cell.add(Box.createRigidArea(new Dimension(0, 3)));
		cell.add(vl);
		return cell;
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

	private BufferedImage weaknessIcon(MonsterData m)
	{
		if (m.getWeakness() == null || m.getWeakness().getElement() == null)
		{
			return icons.elementalIcon;
		}
		return icons.getElementalWeaknessIcon(StatFormat.cap(m.getWeakness().getElement()));
	}

	private static String styleString(MonsterData m)
	{
		List<String> st = m.getStyle();
		return st == null || st.isEmpty() ? "—" : String.join(", ", st);
	}

	// --------------------------------------------------------------- layout helpers

	private JPanel block()
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(new EmptyBorder(6, 8, 6, 8));
		p.setAlignmentX(LEFT_ALIGNMENT);
		return p;
	}

	private JPanel rowX()
	{
		JPanel r = new JPanel();
		r.setLayout(new BoxLayout(r, BoxLayout.X_AXIS));
		r.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		r.setAlignmentX(LEFT_ALIGNMENT);
		r.setBorder(new EmptyBorder(1, 0, 1, 0));
		return r;
	}

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
	private JLabel maxHitLabel(String text, int hpLevel)
	{
		HighlightMode mode = config.statHighlighting();
		boolean cb = mode == HighlightMode.COLOUR_BLIND;
		boolean highlight = mode != HighlightMode.OFF && hpLevel > 0;
		String hex = cb ? "#e69f00" : "#ff4040";

		StringBuilder sb = new StringBuilder("<html><body style='width:200px'>");
		String[] lines = text.split("\n");
		boolean anyOver = false;
		for (int i = 0; i < lines.length; i++)
		{
			if (i > 0)
			{
				sb.append("<br>");
			}
			if (highlight && StatFormat.maxValue(lines[i]) > hpLevel)
			{
				anyOver = true;
				// In colour-blind mode add a warning sign so the cue survives without colour.
				String line = StatFormat.esc(lines[i]) + (cb ? " ⚠" : "");
				sb.append("<span style='color:").append(hex).append("'>").append(line).append("</span>");
			}
			else
			{
				sb.append(StatFormat.esc(lines[i]));
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
			l.setToolTipText("Max hit exceeds your hitpoints level (" + hpLevel + ").");
		}
		return l;
	}

	/** A full-width, wrapping value label (used for examine text and the attribute list). */
	private JLabel wrappedLabel(String text, Color color, boolean italic)
	{
		JLabel l = new JLabel("<html><body style='width:200px'>" + StatFormat.esc(text).replace("\n", "<br>") + "</body></html>");
		Font f = FontManager.getRunescapeSmallFont();
		l.setFont(italic ? f.deriveFont(Font.ITALIC) : f);
		l.setForeground(color);
		l.setAlignmentX(LEFT_ALIGNMENT);
		return l;
	}

	private JLabel sectionHeader(String text)
	{
		JLabel h = new JLabel(text.toUpperCase(Locale.ROOT));
		h.setFont(FontManager.getRunescapeSmallFont());
		h.setForeground(ColorScheme.BRAND_ORANGE);
		h.setAlignmentX(LEFT_ALIGNMENT);
		h.setBorder(new EmptyBorder(0, 0, 3, 0));
		return h;
	}

	private void capHeight(JComponent c)
	{
		c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
		c.setAlignmentX(LEFT_ALIGNMENT);
	}

	// ---- player-relevant colours (delegate to the shared palette) -----------

	/** The "danger" highlight colour for the active mode; white when highlighting is off. */
	private Color danger()
	{
		return StatColors.danger(config.statHighlighting());
	}

	/** The "good for the player" highlight colour for the active mode; white when off. */
	private Color good()
	{
		return StatColors.good(config.statHighlighting());
	}

	/** Combat-level colour for the active mode: the in-game gradient (Standard) or orange/blue (CB). */
	private Color levelColor(int playerLevel, int npcLevel)
	{
		return StatColors.levelColor(config.statHighlighting(), playerLevel, npcLevel);
	}
}
