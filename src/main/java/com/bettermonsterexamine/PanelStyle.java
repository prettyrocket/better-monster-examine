package com.bettermonsterexamine;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.Locale;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * Shared Swing building blocks for the side-panel cards, so {@link MonsterCard}, {@link MonsterHeader}
 * and the drops card assemble their blocks, rows and headers from one place and stay visually in
 * lock-step (same dark theme, padding and alignment). All helpers are stateless.
 */
public final class PanelStyle
{
	/** Fixed body width (px) used by the wrapping value labels, so their HTML lays out consistently. */
	private static final int WRAP_WIDTH = 200;

	private PanelStyle()
	{
	}

	/** A vertical, dark-theme block with the standard card padding. */
	public static JPanel block()
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(new EmptyBorder(6, 8, 6, 8));
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	/** A horizontal, dark-theme row (typically label + glue + value). */
	public static JPanel rowX()
	{
		JPanel r = new JPanel();
		r.setLayout(new BoxLayout(r, BoxLayout.X_AXIS));
		r.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		r.setAlignmentX(Component.LEFT_ALIGNMENT);
		r.setBorder(new EmptyBorder(1, 0, 1, 0));
		return r;
	}

	/** The orange uppercase section-title label, with the trailing gap under it. */
	public static JLabel sectionHeader(String text)
	{
		JLabel h = headerLabel(text);
		h.setBorder(new EmptyBorder(0, 0, 3, 0));
		return h;
	}

	/** The orange uppercase section-title label, without the trailing gap (for inline header rows). */
	public static JLabel headerLabel(String text)
	{
		JLabel h = new JLabel(text.toUpperCase(Locale.ROOT));
		h.setFont(FontManager.getRunescapeSmallFont());
		h.setForeground(ColorScheme.BRAND_ORANGE);
		h.setAlignmentX(Component.LEFT_ALIGNMENT);
		return h;
	}

	/** A full-width, wrapping value label (used for examine text and the attribute list). */
	public static JLabel wrappedLabel(String text, Color color, boolean italic)
	{
		return wrappedLabel(text, color, italic, WRAP_WIDTH);
	}

	/** A wrapping label at a given width, for rows that share their line with something else. */
	public static JLabel wrappedLabel(String text, Color color, boolean italic, int width)
	{
		JLabel l = new JLabel("<html><body style='width:" + width + "px'>"
			+ StatFormat.esc(text).replace("\n", "<br>") + "</body></html>");
		Font f = FontManager.getRunescapeSmallFont();
		l.setFont(italic ? f.deriveFont(Font.ITALIC) : f);
		l.setForeground(color);
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	/** Pin a component's max height to its preferred height, so a Y-axis BoxLayout won't stretch it. */
	public static void capHeight(JComponent c)
	{
		c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
		c.setAlignmentX(Component.LEFT_ALIGNMENT);
	}
}
