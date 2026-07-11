package com.bettermonsterexamine.loot;

import com.bettermonsterexamine.BetterMonsterExamineConfig;
import com.bettermonsterexamine.HighlightMode;
import static com.bettermonsterexamine.PanelStyle.block;
import static com.bettermonsterexamine.PanelStyle.capHeight;
import static com.bettermonsterexamine.PanelStyle.sectionHeader;
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
 * Drops-tab counterpart to {@code MonsterCard}). Given the page's {@link DropTable} it renders one
 * row per drop — item icon + name with the quantity right-aligned on top, the rarity/odds
 * right-aligned below — grouped into the wiki's own sections in page order (Herbs, Gem/Rare drop
 * table, Catacombs/Wilderness tables, …). Each row's GE / High Alch go in its hover tooltip.
 *
 * <p>The drop list is parsed from the monster's wiki page ({@link DropPageService}), so it arrives
 * asynchronously — {@link #show} is re-called as the page (and the bulk item-id map) land. Item id
 * comes from {@link ItemIdService}; price, high-alch and icon come from the RuneLite client with zero
 * network. The structure is built on the EDT with blank icon/price cells, then a single
 * {@link ClientThread} hop reads {@code ItemManager}/{@code ItemComposition} by id and fills them
 * back on the EDT ({@code getImage} returns an {@link AsyncBufferedImage} that repaints on load).
 */
public class DropsCard extends JPanel
{
	/** On-screen size of each item icon (native OSRS item sprites are ~36×32). */
	private static final int ICON_BOX = 28;
	private static final Pattern LEADING_INT = Pattern.compile("(\\d[\\d,]*)");

	// Rarity palette — common stays grey; rarer tiers warm up. The colour-blind set is Okabe-Ito, so
	// the four tiers stay distinguishable under red-green colour blindness.
	private static final Color STD_UNCOMMON = new Color(0x5f, 0xc9, 0x6b);   // green
	private static final Color STD_RARE = new Color(0x5a, 0xa9, 0xe6);       // blue
	private static final Color STD_ULTRA = new Color(0xbb, 0x7f, 0xe0);      // purple
	private static final Color CB_UNCOMMON = new Color(0x56, 0xb4, 0xe9);    // sky blue
	private static final Color CB_RARE = new Color(0xe6, 0x9f, 0x00);        // orange
	private static final Color CB_ULTRA = new Color(0xcc, 0x79, 0xa7);       // reddish purple

	private final ItemManager itemManager;
	private final ClientThread clientThread;
	private final ItemIdService itemIds;
	private final BetterMonsterExamineConfig config;

	public DropsCard(ItemManager itemManager, ClientThread clientThread, ItemIdService itemIds, BetterMonsterExamineConfig config)
	{
		this.itemManager = itemManager;
		this.clientThread = clientThread;
		this.itemIds = itemIds;
		this.config = config;
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

		// Two lines: item name (left) with quantity right-aligned on top, the drop odds right-aligned
		// below. GE / High Alch go in the row's hover tooltip, to keep each row uncluttered.
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
		nameLine.add(Box.createRigidArea(new Dimension(8, 0)));
		nameLine.add(Box.createHorizontalGlue());

		String qty = DropFormat.quantity(row);
		if (!qty.isEmpty() && !"1".equals(qty))
		{
			JLabel q = new JLabel("×" + qty);
			q.setFont(FontManager.getRunescapeSmallFont());
			q.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			nameLine.add(q);
		}
		capHeight(nameLine);
		centre.add(nameLine);

		// Line 2: the drop odds, right-aligned and colour-coded by rarity tier.
		JPanel rarityLine = new JPanel();
		rarityLine.setLayout(new BoxLayout(rarityLine, BoxLayout.X_AXIS));
		rarityLine.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		rarityLine.setAlignmentX(LEFT_ALIGNMENT);
		rarityLine.add(Box.createHorizontalGlue());
		JLabel rarity = new JLabel(DropFormat.rarity(row));
		rarity.setFont(FontManager.getRunescapeSmallFont());
		rarity.setForeground(rarityColor(row.getRarity()));
		rarityLine.add(rarity);
		capHeight(rarityLine);
		centre.add(Box.createRigidArea(new Dimension(0, 1)));
		centre.add(rarityLine);

		r.add(centre, BorderLayout.CENTER);
		capHeight(r);

		if (row.getItem() != null && !row.getItem().isEmpty())
		{
			cells.add(new PriceCell(row.getItem(), iconQuantity(qty), isNoted(qty), icon, r));
			makeClickable(r, row.getItem());
		}
		return r;
	}

	/** The leading integer of a quantity string, for the icon's stack number; 1 when there's none. */
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

	/** True when the wiki rendered this drop as noted (its quantity string carries "(noted)"). */
	private static boolean isNoted(String qty)
	{
		return qty.toLowerCase(Locale.ROOT).contains("noted");
	}

	/** The rarity-tier colour for the current highlight mode (grey when highlighting is off). */
	private Color rarityColor(String rarity)
	{
		HighlightMode mode = config.statHighlighting();
		if (mode == HighlightMode.OFF)
		{
			return ColorScheme.LIGHT_GRAY_COLOR;
		}
		boolean cb = mode == HighlightMode.COLOUR_BLIND;
		switch (DropFormat.tierOf(rarity))
		{
			case UNCOMMON:
				return cb ? CB_UNCOMMON : STD_UNCOMMON;
			case RARE:
				return cb ? CB_RARE : STD_RARE;
			case ULTRA_RARE:
				return cb ? CB_ULTRA : STD_ULTRA;
			default:
				return ColorScheme.LIGHT_GRAY_COLOR;
		}
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
		setRowTooltip(row, baseTooltip(item));
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

	/** The row's tooltip before prices resolve: item name + the wiki hint. */
	private static String baseTooltip(String item)
	{
		return "<html>" + esc(item) + wikiHint();
	}

	/** The row's tooltip once priced: name, GE / High Alch (higher one highlighted), and the wiki hint. */
	private String priceTooltip(String item, int ge, int ha)
	{
		StringBuilder sb = new StringBuilder("<html>").append(esc(item));
		String line = priceLineHtml(ge, ha);
		if (!line.isEmpty())
		{
			sb.append("<br>").append(line);
		}
		return sb.append(wikiHint()).toString();
	}

	/** "GE x · Alch y" with the larger value highlighted (colour-blind-aware); empty when neither priced. */
	private String priceLineHtml(int ge, int ha)
	{
		String geStr = DropFormat.price(ge);
		String haStr = DropFormat.price(ha);
		if (geStr.isEmpty() && haStr.isEmpty())
		{
			return "";
		}
		String hi = highlightHex();
		boolean geWins = ge >= ha;
		StringBuilder sb = new StringBuilder();
		if (!geStr.isEmpty())
		{
			sb.append("GE ").append(colorize(geStr, geWins ? hi : null));
		}
		if (!haStr.isEmpty())
		{
			if (sb.length() > 0)
			{
				sb.append(" · ");
			}
			sb.append("Alch ").append(colorize(haStr, geWins ? null : hi));
		}
		return sb.toString();
	}

	/** The highlight colour (hex) for the higher GE/Alch value, per the highlight mode; null when off. */
	private String highlightHex()
	{
		switch (config.statHighlighting())
		{
			case STANDARD:
				return "#5fc96b";      // green (money)
			case COLOUR_BLIND:
				return "#56b4e9";      // sky blue — colour-blind safe
			default:
				return null;
		}
	}

	private static String colorize(String value, String hex)
	{
		String v = esc(value);
		return hex == null ? v : "<span style='color:" + hex + "'>" + v + "</span>";
	}

	private static String wikiHint()
	{
		return "<br><span style='color:#9a9a9a'>Click to open on the OSRS Wiki</span></html>";
	}

	private static String esc(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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
			List<Runnable> updates = new ArrayList<>();
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
				// A noted drop renders the item's noted graphic — a separate, stackable item id. The
				// quantity draws the stack-number badge on the icon.
				int iconId = id;
				boolean stackable = comp != null && comp.isStackable();
				if (c.noted && comp != null && comp.getLinkedNoteId() > 0)
				{
					iconId = comp.getLinkedNoteId();
					stackable = true;
				}
				AsyncBufferedImage img = itemManager.getImage(iconId, c.quantity, stackable);
				String tip = priceTooltip(c.itemName, ge, ha);
				updates.add(() ->
				{
					if (img != null)
					{
						img.addTo(c.icon);
					}
					setRowTooltip(c.row, tip);
				});
			}
			if (updates.isEmpty())
			{
				return;
			}
			// Apply every resolved row in a single EDT hop, then lay out once.
			SwingUtilities.invokeLater(() ->
			{
				updates.forEach(Runnable::run);
				revalidate();
				repaint();
			});
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

}
