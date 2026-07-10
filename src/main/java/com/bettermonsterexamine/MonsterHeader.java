package com.bettermonsterexamine;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import javax.swing.Box;
import javax.swing.BoxLayout;
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
 * The monster-identity header shared by the side panel's Stats and Drops tabs: name + favourite
 * star + combat level, examine text, the variant selector, and the Wiki / DPS-calc links. It sits
 * <em>above</em> the tab strip so the selected monster and variant stay put while the body below
 * swaps between {@link MonsterCard} (stats) and the drops list. Favouriting and variant switching
 * are surfaced as callbacks so the host panel keeps that state.
 */
class MonsterHeader extends JPanel
{
	private static final String DPS_CALC_URL = "https://tools.runescape.wiki/osrs-dps/";

	private final BetterMonsterExamineConfig config;
	private final IntSupplier playerCombatLevel;
	private final Predicate<MonsterData> isFavorite;
	private final Consumer<MonsterData> onToggleFavorite;
	private final Consumer<MonsterData> onSelectVariant;

	/** The variant dropdown wrapper (when the monster has >1 form), so it can be hidden on the Drops tab. */
	private JComponent variantSelector;
	private boolean showVariantSelector = true;

	MonsterHeader(BetterMonsterExamineConfig config, IntSupplier playerCombatLevel,
		Predicate<MonsterData> isFavorite, Consumer<MonsterData> onToggleFavorite,
		Consumer<MonsterData> onSelectVariant)
	{
		this.config = config;
		this.playerCombatLevel = playerCombatLevel;
		this.isFavorite = isFavorite;
		this.onToggleFavorite = onToggleFavorite;
		this.onSelectVariant = onSelectVariant;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setAlignmentX(LEFT_ALIGNMENT);
	}

	/** Render the header for {@code m}, with its {@code variants} (for the dropdown). */
	void show(MonsterData m, List<MonsterData> variants)
	{
		removeAll();
		add(header(m, variants));
		revalidate();
		repaint();
	}

	void clear()
	{
		removeAll();
		revalidate();
		repaint();
	}

	/** Show or hide the variant dropdown — hidden on the Drops tab, which doesn't use it. */
	void setVariantSelectorVisible(boolean visible)
	{
		showVariantSelector = visible;
		if (variantSelector != null)
		{
			variantSelector.setVisible(visible);
			revalidate();
			repaint();
		}
	}

	private JComponent header(MonsterData m, List<MonsterData> variants)
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

		// Examine text: subtle italic line directly under the name.
		String examine = m.getExamine();
		if (examine != null && !examine.isEmpty())
		{
			block.add(Box.createRigidArea(new Dimension(0, 2)));
			block.add(wrappedLabel(examine, ColorScheme.LIGHT_GRAY_COLOR, true));
		}

		// Variant selector (only when >1 form shares the name); hidden on the Drops tab, which shows
		// every variant's drops regardless, so the dropdown doesn't apply there.
		variantSelector = null;
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
				// header never re-fires it — only a real user change reaches the host.
				if (i >= 0 && i < variants.size())
				{
					onSelectVariant.accept(variants.get(i));
				}
			});
			capHeight(combo);

			JPanel selector = new JPanel();
			selector.setLayout(new BoxLayout(selector, BoxLayout.Y_AXIS));
			selector.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			selector.setAlignmentX(LEFT_ALIGNMENT);
			selector.add(Box.createRigidArea(new Dimension(0, 4)));
			selector.add(combo);
			capHeight(selector);
			selector.setVisible(showVariantSelector);
			variantSelector = selector;
			block.add(selector);
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

	/** A full-width, wrapping value label (used for the examine text). */
	private JLabel wrappedLabel(String text, Color color, boolean italic)
	{
		JLabel l = new JLabel("<html><body style='width:200px'>" + StatFormat.esc(text).replace("\n", "<br>") + "</body></html>");
		Font f = FontManager.getRunescapeSmallFont();
		l.setFont(italic ? f.deriveFont(Font.ITALIC) : f);
		l.setForeground(color);
		l.setAlignmentX(LEFT_ALIGNMENT);
		return l;
	}

	private void capHeight(JComponent c)
	{
		c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
		c.setAlignmentX(LEFT_ALIGNMENT);
	}

	private Color levelColor(int playerLevel, int npcLevel)
	{
		return StatColors.levelColor(config.statHighlighting(), playerLevel, npcLevel);
	}
}
