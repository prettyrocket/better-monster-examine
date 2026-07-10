package com.bettermonsterexamine.loot;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.api.ItemComposition;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * The drops list for the currently-viewed monster, as a self-contained Swing component (the
 * Drops-tab counterpart to {@code MonsterCard}). Given a page's {@link DropTable}s (one per variant)
 * it renders one row per drop — item icon + name + <code>×qty</code> on the left, rarity on the
 * right, and a small grey <code>GE · Alch</code> caption underneath — grouped into the deterministic
 * sections (100% → Other → Rare drop table), one block per variant.
 *
 * <p>Drop data (names, quantities, rarities, sections) is synchronous from the cached Bucket layer.
 * Item id comes from {@link ItemIdService}; price, high-alch and icon come from the RuneLite client
 * with zero network. The structure is built on the EDT with blank icon/price cells, then a single
 * {@link ClientThread} hop reads {@code ItemManager}/{@code ItemComposition} by id and fills them
 * back on the EDT ({@code getImage} returns an {@link AsyncBufferedImage} that repaints on load).
 */
public class DropsCard extends JPanel
{
	/** On-screen size of each item icon (native OSRS item sprites are ~36×32). */
	private static final int ICON_BOX = 28;

	private final ItemManager itemManager;
	private final ClientThread clientThread;
	private final ItemIdService itemIds;

	public DropsCard(ItemManager itemManager, ClientThread clientThread, ItemIdService itemIds)
	{
		this.itemManager = itemManager;
		this.clientThread = clientThread;
		this.itemIds = itemIds;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setAlignmentX(LEFT_ALIGNMENT);
	}

	/** One item's client-supplied cells, filled on the client thread after the structure is built. */
	private static final class PriceCell
	{
		private final int itemId;
		private final int quantity;
		private final JLabel icon;
		private final JLabel price;

		private PriceCell(int itemId, int quantity, JLabel icon, JLabel price)
		{
			this.itemId = itemId;
			this.quantity = quantity;
			this.icon = icon;
			this.price = price;
		}
	}

	/**
	 * Render a page's drops. {@code tables} is null while the page is still loading; empty (or all
	 * empty) when the monster has no drops; otherwise one block per variant, with a variant header
	 * only when there's more than one.
	 */
	public void show(List<DropTable> tables)
	{
		removeAll();
		if (tables == null)
		{
			renderMessage("Loading drops…");
			revalidate();
			repaint();
			return;
		}

		int variants = 0;
		for (DropTable t : tables)
		{
			if (!t.isEmpty())
			{
				variants++;
			}
		}
		if (variants == 0)
		{
			renderMessage("No drops recorded for this monster.");
			revalidate();
			repaint();
			return;
		}

		List<PriceCell> cells = new ArrayList<>();
		boolean multi = variants > 1;
		for (DropTable table : tables)
		{
			if (table.isEmpty())
			{
				continue;
			}
			if (multi)
			{
				if (getComponentCount() > 0)
				{
					add(Box.createRigidArea(new Dimension(0, 6)));
				}
				add(variantHeader(table.displayName()));
			}
			for (DropTable.Section section : table.getSections())
			{
				add(Box.createRigidArea(new Dimension(0, 6)));
				add(sectionBlock(section, cells));
			}
		}

		fill(cells);
		revalidate();
		repaint();
	}

	/** Replace the list with a plain wrapping message (loading / empty / offline states). */
	public void showMessage(String text)
	{
		removeAll();
		renderMessage(text);
		revalidate();
		repaint();
	}

	public void clear()
	{
		removeAll();
		revalidate();
		repaint();
	}

	/** A titled block of drop rows for one section, collecting each row's price cell to fill later. */
	private JComponent sectionBlock(DropTable.Section section, List<PriceCell> cells)
	{
		JPanel block = block();
		block.add(sectionHeader(section.getLabel() + " (" + section.getRows().size() + ")"));
		for (DropRow row : section.getRows())
		{
			block.add(Box.createRigidArea(new Dimension(0, 3)));
			block.add(dropRow(row, cells));
		}
		capHeight(block);
		return block;
	}

	private JComponent dropRow(DropRow row, List<PriceCell> cells)
	{
		JPanel r = new JPanel(new BorderLayout(6, 0));
		r.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		r.setAlignmentX(LEFT_ALIGNMENT);
		r.setBorder(new EmptyBorder(1, 0, 1, 0));

		JLabel icon = iconLabel(row.getItem());
		r.add(icon, BorderLayout.WEST);

		// Centre column: name + ×qty + rarity on line 1, the GE/Alch caption (async) on line 2.
		JPanel centre = new JPanel();
		centre.setLayout(new BoxLayout(centre, BoxLayout.Y_AXIS));
		centre.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		centre.setAlignmentX(LEFT_ALIGNMENT);

		JPanel line1 = new JPanel();
		line1.setLayout(new BoxLayout(line1, BoxLayout.X_AXIS));
		line1.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		line1.setAlignmentX(LEFT_ALIGNMENT);

		JLabel name = new JLabel(row.getItem() == null ? "?" : row.getItem());
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(Color.WHITE);
		line1.add(name);

		// Quantity trails the name as a subtle "×N" — but a plain single item (qty 1) needs no count.
		String qty = DropFormat.quantity(row);
		if (!qty.isEmpty() && !"1".equals(qty))
		{
			JLabel q = new JLabel(" ×" + qty);
			q.setFont(FontManager.getRunescapeSmallFont());
			q.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			line1.add(q);
		}

		line1.add(Box.createRigidArea(new Dimension(6, 0)));
		line1.add(Box.createHorizontalGlue());

		JLabel rarity = new JLabel(DropFormat.rarity(row));
		rarity.setFont(FontManager.getRunescapeSmallFont());
		rarity.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		line1.add(rarity);
		capHeight(line1);
		centre.add(line1);

		// GE/Alch caption: hidden until the client thread fills it (blank for untradeable + non-alch).
		JLabel price = new JLabel();
		price.setFont(FontManager.getRunescapeSmallFont());
		price.setForeground(GE_COLOR);
		price.setAlignmentX(LEFT_ALIGNMENT);
		price.setVisible(false);
		centre.add(price);

		r.add(centre, BorderLayout.CENTER);
		capHeight(r);

		Integer id = itemIds.idFor(row.getItem());
		if (id != null)
		{
			cells.add(new PriceCell(id, Math.max(1, row.getQuantityLow()), icon, price));
		}
		return r;
	}

	/**
	 * Fill the item icons and prices on the client thread (where {@code ItemManager}/{@code
	 * ItemComposition} must be read), then apply them back on the EDT. A blank price line means the
	 * client returned 0 for both GE and High Alch (untradeable and non-alchable).
	 */
	private void fill(List<PriceCell> cells)
	{
		if (cells.isEmpty())
		{
			return;
		}
		clientThread.invoke(() ->
		{
			for (PriceCell c : cells)
			{
				ItemComposition comp = itemManager.getItemComposition(c.itemId);
				int ge = itemManager.getItemPrice(c.itemId);
				int ha = comp == null ? 0 : comp.getHaPrice();
				boolean stackable = comp != null && comp.isStackable();
				AsyncBufferedImage img = itemManager.getImage(c.itemId, c.quantity, stackable);
				SwingUtilities.invokeLater(() ->
				{
					String line = DropFormat.priceLine(ge, ha);
					if (!line.isEmpty())
					{
						c.price.setText(line);
						c.price.setVisible(true);
					}
					if (img != null)
					{
						img.addTo(c.icon);
					}
					revalidate();
					repaint();
				});
			}
		});
	}

	// ------------------------------------------------------------ small helpers

	/** The soft green used for coin values, matching the client's price colouring. */
	private static final Color GE_COLOR = new Color(0x7e, 0xc7, 0x7e);

	/** A fixed-size icon slot for the item, left blank (but width-preserving) when there's no id. */
	private JLabel iconLabel(String itemName)
	{
		JLabel l = new JLabel();
		Dimension d = new Dimension(ICON_BOX, ICON_BOX);
		l.setPreferredSize(d);
		l.setMinimumSize(d);
		l.setMaximumSize(d);
		l.setToolTipText(itemName);
		return l;
	}

	private JComponent variantHeader(String name)
	{
		JLabel l = new JLabel(name.toUpperCase(Locale.ROOT));
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(ColorScheme.BRAND_ORANGE);
		l.setAlignmentX(LEFT_ALIGNMENT);
		l.setBorder(new EmptyBorder(2, 2, 0, 0));
		capHeight(l);
		return l;
	}

	private void renderMessage(String text)
	{
		JLabel l = new JLabel("<html><body style='width:180px'>" + text + "</body></html>");
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		l.setAlignmentX(LEFT_ALIGNMENT);
		add(l);
	}

	private JPanel block()
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(new EmptyBorder(6, 8, 6, 8));
		p.setAlignmentX(LEFT_ALIGNMENT);
		return p;
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
}
