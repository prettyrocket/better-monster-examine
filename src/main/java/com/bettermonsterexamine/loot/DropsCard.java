package com.bettermonsterexamine.loot;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import net.runelite.client.util.LinkBrowser;
import net.runelite.http.api.item.ItemPrice;

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
	private static final Pattern LEADING_INT = Pattern.compile("(\\d[\\d,]*)");

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
		private final String itemName;
		private final int quantity;
		private final boolean noted;
		private final JLabel icon;
		private final JComponent row;

		private PriceCell(String itemName, int quantity, boolean noted, JLabel icon, JComponent row)
		{
			this.itemName = itemName;
			this.quantity = quantity;
			this.noted = noted;
			this.icon = icon;
			this.row = row;
		}
	}

	/**
	 * Render a monster's drops. {@code table} is null while the page is still loading, empty when the
	 * monster has no drops; otherwise one block per wiki section, in the page's order.
	 */
	public void show(DropTable table)
	{
		removeAll();
		if (table == null)
		{
			renderMessage("Loading drops…");
			revalidate();
			repaint();
			return;
		}
		if (table.isEmpty())
		{
			renderMessage("No drops recorded for this monster.");
			revalidate();
			repaint();
			return;
		}

		List<PriceCell> cells = new ArrayList<>();
		for (DropTable.Section section : table.getSections())
		{
			if (getComponentCount() > 0)
			{
				add(Box.createRigidArea(new Dimension(0, 6)));
			}
			add(sectionBlock(section, cells));
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
		JPanel r = new JPanel(new BorderLayout(8, 0));
		r.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		r.setAlignmentX(LEFT_ALIGNMENT);
		r.setBorder(new EmptyBorder(3, 2, 3, 2));

		JLabel icon = iconLabel(row.getItem());
		r.add(icon, BorderLayout.WEST);

		// Two lines: item name + quantity on top, the drop odds below. GE / High Alch go in the row's
		// hover tooltip rather than on screen, to keep each row uncluttered.
		JPanel centre = new JPanel();
		centre.setLayout(new BoxLayout(centre, BoxLayout.Y_AXIS));
		centre.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		centre.setAlignmentX(LEFT_ALIGNMENT);

		JPanel nameLine = new JPanel();
		nameLine.setLayout(new BoxLayout(nameLine, BoxLayout.X_AXIS));
		nameLine.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		nameLine.setAlignmentX(LEFT_ALIGNMENT);

		JLabel name = new JLabel(row.getItem() == null ? "?" : row.getItem());
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(Color.WHITE);
		// Let the name shrink and truncate rather than overflow the row width.
		name.setMinimumSize(new Dimension(24, name.getPreferredSize().height));
		nameLine.add(name);

		String qty = DropFormat.quantity(row);
		if (!qty.isEmpty() && !"1".equals(qty))
		{
			JLabel q = new JLabel("×" + qty);
			q.setFont(FontManager.getRunescapeSmallFont());
			q.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			nameLine.add(Box.createRigidArea(new Dimension(6, 0)));
			nameLine.add(q);
		}
		nameLine.add(Box.createHorizontalGlue());
		capHeight(nameLine);
		centre.add(nameLine);

		// Line 2: the drop odds, directly under the name.
		JLabel rarity = new JLabel(DropFormat.rarity(row));
		rarity.setFont(FontManager.getRunescapeSmallFont());
		rarity.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		rarity.setAlignmentX(LEFT_ALIGNMENT);
		centre.add(Box.createRigidArea(new Dimension(0, 1)));
		centre.add(rarity);

		r.add(centre, BorderLayout.CENTER);
		capHeight(r);

		if (row.getItem() != null && !row.getItem().isEmpty())
		{
			cells.add(new PriceCell(row.getItem(), iconQuantity(qty), isNoted(qty), icon, r));
			makeClickable(r, row.getItem());
		}
		return r;
	}

	/** True when the wiki rendered this drop as noted (its quantity string carries "(noted)"). */
	private static boolean isNoted(String qty)
	{
		return qty.toLowerCase(Locale.ROOT).contains("noted");
	}

	/** Make the whole row open the item's OSRS Wiki page on click (hand cursor + hover tooltip). */
	private static void makeClickable(JComponent row, String item)
	{
		String url = "https://oldschool.runescape.wiki/w/" + item.replace(' ', '_');
		MouseAdapter open = new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (e.getButton() == MouseEvent.BUTTON1)
				{
					LinkBrowser.browse(url);
				}
			}
		};
		// Swing doesn't bubble mouse events, so attach to the row and every child it contains.
		applyClick(row, open);
		setRowTooltip(row, tooltip(item, ""));
	}

	private static void applyClick(Component c, MouseAdapter open)
	{
		c.addMouseListener(open);
		c.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				applyClick(child, open);
			}
		}
	}

	/** Set one tooltip across a component and all its descendants (Swing shows the child's, not the row's). */
	private static void setRowTooltip(Component c, String tip)
	{
		if (c instanceof JComponent)
		{
			((JComponent) c).setToolTipText(tip);
		}
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				setRowTooltip(child, tip);
			}
		}
	}

	/** The row's hover tooltip: item name, GE / High Alch (once priced), and the wiki hint. */
	private static String tooltip(String item, String priceLine)
	{
		StringBuilder sb = new StringBuilder("<html>").append(esc(item));
		if (!priceLine.isEmpty())
		{
			sb.append("<br>").append(esc(priceLine));
		}
		return sb.append("<br><span style='color:#9a9a9a'>Click to open on the OSRS Wiki</span></html>").toString();
	}

	private static String esc(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	/** The leading integer of a quantity string (for the icon's stack number), or 1 when there's none. */
	private static int iconQuantity(String qty)
	{
		Matcher m = LEADING_INT.matcher(qty);
		if (m.find())
		{
			try
			{
				return Math.max(1, Integer.parseInt(m.group(1).replace(",", "")));
			}
			catch (NumberFormatException e)
			{
				return 1;
			}
		}
		return 1;
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
				Integer id = resolveId(c.itemName);
				if (id == null)
				{
					continue;
				}
				ItemComposition comp = itemManager.getItemComposition(id);
				int ge = itemManager.getItemPrice(id);
				int ha = comp == null ? 0 : comp.getHaPrice();
				// A noted drop renders the item's noted graphic — a separate, stackable item id.
				int iconId = id;
				boolean stackable = comp != null && comp.isStackable();
				if (c.noted && comp != null && comp.getLinkedNoteId() > 0)
				{
					iconId = comp.getLinkedNoteId();
					stackable = true;
				}
				AsyncBufferedImage img = itemManager.getImage(iconId, c.quantity, stackable);
				String tip = tooltip(c.itemName, DropFormat.priceLine(ge, ha));
				SwingUtilities.invokeLater(() ->
				{
					if (img != null)
					{
						img.addTo(c.icon);
					}
					setRowTooltip(c.row, tip);
					revalidate();
					repaint();
				});
			}
		});
	}

	/**
	 * Resolve an item name to a client item id, on the client thread. The bulk Bucket {@code item_id}
	 * map is tried first (it covers untradeables the client's search index misses); then a small hand
	 * map for items the bucket can't pin to one id (clue scrolls, whose bucket id is {@code "N/A"});
	 * finally {@link ItemManager#search} on an exact name match (covers tradeables the bucket misses,
	 * e.g. dose potions like {@code "Strength potion(2)"}).
	 */
	private Integer resolveId(String name)
	{
		Integer id = itemIds.idFor(name);
		if (id != null)
		{
			return id;
		}
		Integer known = KNOWN_IDS.get(name);
		if (known != null)
		{
			return known;
		}
		try
		{
			for (ItemPrice match : itemManager.search(name))
			{
				if (name.equalsIgnoreCase(match.getName()))
				{
					return match.getId();
				}
			}
		}
		catch (RuntimeException e)
		{
			return null;
		}
		return null;
	}

	/** Items the Bucket {@code item_id} map returns {@code "N/A"} for (many ids) — pinned by hand. */
	private static final Map<String, Integer> KNOWN_IDS = new HashMap<>();

	static
	{
		KNOWN_IDS.put("Clue scroll (beginner)", 23182);
		KNOWN_IDS.put("Clue scroll (easy)", 2677);
		KNOWN_IDS.put("Clue scroll (medium)", 2801);
		KNOWN_IDS.put("Clue scroll (hard)", 2722);
		KNOWN_IDS.put("Clue scroll (elite)", 12073);
		KNOWN_IDS.put("Clue scroll (master)", 19835);
	}

	// ------------------------------------------------------------ small helpers

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
