package com.bettermonsterexamine;

import com.bettermonsterexamine.loot.ItemIdService;
import com.bettermonsterexamine.slayer.GearSetup;
import com.bettermonsterexamine.slayer.SpawnLocation;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.LinkBrowser;
import static com.bettermonsterexamine.PanelStyle.block;
import static com.bettermonsterexamine.PanelStyle.capHeight;
import static com.bettermonsterexamine.PanelStyle.rowX;
import static com.bettermonsterexamine.PanelStyle.sectionHeader;
import static com.bettermonsterexamine.PanelStyle.wrappedLabel;

/**
 * The Slayer tab body: the Slayer block (level, XP, category, masters), the page's spawn locations,
 * and the wiki's recommended gear. Locations arrive with the drop page and gear with its own Bucket
 * query, so both land async and {@link #show} is re-called as they do; a null list reads as loading.
 * Item icons come from the client by id, filled with one {@link ClientThread} hop as the drops are.
 */
class SlayerCard extends JPanel
{
	private static final String WIKI = "https://oldschool.runescape.wiki/w/";
	private static final int ICON_W = 36;
	private static final int ICON_H = 32;
	private static final int NAME_WIDTH = 110;

	private final MonsterCard stats;
	private final ItemManager itemManager;
	private final ClientThread clientThread;
	private final ItemIdService itemIds;

	/** The gear setup picked for {@link #gearPage}, kept across the re-renders async loads trigger. */
	private int selectedSetup;
	private String gearPage;

	SlayerCard(MonsterCard stats, ItemManager itemManager, ClientThread clientThread, ItemIdService itemIds)
	{
		this.stats = stats;
		this.itemManager = itemManager;
		this.clientThread = clientThread;
		this.itemIds = itemIds;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setAlignmentX(LEFT_ALIGNMENT);
	}

	void show(MonsterData m, List<SpawnLocation> locations, List<GearSetup> gear)
	{
		removeAll();
		if (!m.getName().equalsIgnoreCase(gearPage))
		{
			gearPage = m.getName();
			selectedSetup = 0;
		}

		JComponent slayer = stats.slayerBlock(m);
		if (slayer != null)
		{
			add(slayer);
		}
		else
		{
			add(note("Not a Slayer assignment."));
		}

		add(Box.createRigidArea(new Dimension(0, 6)));
		add(locationsBlock(m, locations));

		JComponent gearBlock = gearBlock(gear);
		if (gearBlock != null)
		{
			add(Box.createRigidArea(new Dimension(0, 6)));
			add(gearBlock);
		}
		revalidate();
		repaint();
	}

	void clear()
	{
		removeAll();
		revalidate();
		repaint();
	}

	// ---------------------------------------------------------------- locations

	/**
	 * Where it spawns, one row per location with the spawn count on the right. When a page lists
	 * several levels, the rows that aren't the selected variant's are dimmed, so the variant dropdown
	 * doubles as a "where do I find this one" filter.
	 */
	private JComponent locationsBlock(MonsterData m, List<SpawnLocation> locations)
	{
		JPanel b = block();
		b.add(sectionHeader("Locations"));
		if (locations == null)
		{
			b.add(note("Loading…"));
		}
		else if (locations.isEmpty())
		{
			b.add(note("No spawn locations listed."));
		}
		else
		{
			boolean anyMatch = locations.stream().anyMatch(l -> l.hasLevel(m.getLevel()));
			for (SpawnLocation l : locations)
			{
				boolean dim = anyMatch && !l.hasLevel(m.getLevel());
				b.add(locationRow(l, dim));
			}
		}
		capHeight(b);
		return b;
	}

	private JComponent locationRow(SpawnLocation l, boolean dim)
	{
		JPanel r = new JPanel(new BorderLayout());
		r.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		r.setBorder(new EmptyBorder(1, 0, 1, 0));
		r.setAlignmentX(LEFT_ALIGNMENT);

		Color c = dim ? ColorScheme.MEDIUM_GRAY_COLOR : Color.WHITE;
		r.add(wrappedLabel(l.getLocation(), c, false, 140), BorderLayout.CENTER);
		JLabel spawns = new JLabel(l.getSpawns());
		spawns.setFont(FontManager.getRunescapeSmallFont());
		spawns.setForeground(dim ? ColorScheme.MEDIUM_GRAY_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
		spawns.setVerticalAlignment(JLabel.TOP);
		r.add(spawns, BorderLayout.EAST);

		String tip = "<html>Level " + StatFormat.esc(l.getLevels())
			+ (l.getSpawns().isEmpty() ? "" : "<br>" + StatFormat.esc(l.getSpawns()) + " spawns")
			+ (l.getMembers() == null ? "" : "<br>" + (l.getMembers() ? "Members" : "Free-to-play"))
			+ "</html>";
		setTooltip(r, tip);
		capHeight(r);
		return r;
	}

	// --------------------------------------------------------------------- gear

	/** The recommended gear, or null when the wiki has none (most monsters) — no block at all then. */
	private JComponent gearBlock(List<GearSetup> gear)
	{
		if (gear != null && gear.isEmpty())
		{
			return null;
		}
		JPanel b = block();
		b.add(sectionHeader("Recommended gear"));
		if (gear == null)
		{
			b.add(note("Loading…"));
			capHeight(b);
			return b;
		}

		int pick = Math.min(selectedSetup, gear.size() - 1);
		if (gear.size() > 1)
		{
			JComboBox<String> combo = new JComboBox<>();
			boolean mixed = gear.stream().map(GearSetup::getPage).distinct().count() > 1;
			List<String> seen = new ArrayList<>();
			for (int i = 0; i < gear.size(); i++)
			{
				GearSetup s = gear.get(i);
				String style = s.getStyle().isEmpty() ? "Setup " + (i + 1) : s.getStyle();
				String label = mixed ? style + " (" + sourceLabel(s.getPage()) + ")" : style;
				seen.add(label);
				// A guide can repeat a style name (the Sire's two "Phase 1 Melee" setups); number the repeats.
				int n = Collections.frequency(seen, label);
				combo.addItem(n > 1 ? label + " " + n : label);
			}
			combo.setSelectedIndex(pick);
			combo.setFont(FontManager.getRunescapeSmallFont());
			combo.addActionListener(e ->
			{
				int i = combo.getSelectedIndex();
				if (i >= 0 && i != selectedSetup)
				{
					selectedSetup = i;
					// Swap just this block's contents so the scroll position stays put.
					SwingUtilities.invokeLater(() -> rebuildGear(b, gear));
				}
			});
			capHeight(combo);
			b.add(combo);
			b.add(Box.createRigidArea(new Dimension(0, 4)));
		}

		b.add(setupBody(gear.get(pick)));
		capHeight(b);
		return b;
	}

	private void rebuildGear(JPanel block, List<GearSetup> gear)
	{
		// The body is always the block's last child; everything above it (header, picker) stays.
		block.remove(block.getComponentCount() - 1);
		block.add(setupBody(gear.get(selectedSetup)));
		capHeight(block);
		revalidate();
		repaint();
	}

	private JComponent setupBody(GearSetup setup)
	{
		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		body.setAlignmentX(LEFT_ALIGNMENT);

		List<IconCell> cells = new ArrayList<>();
		for (GearSetup.Slot slot : setup.getSlots())
		{
			body.add(slotRow(slot, cells));
		}

		JLabel source = new JLabel("From the wiki's " + sourceLabel(setup.getPage()));
		source.setFont(FontManager.getRunescapeSmallFont());
		source.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		source.setBorder(new EmptyBorder(4, 0, 0, 0));
		source.setAlignmentX(LEFT_ALIGNMENT);
		String url = WIKI + setup.getPage().replace(' ', '_');
		source.setToolTipText("Open " + setup.getPage() + " on the wiki");
		source.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		source.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				LinkBrowser.browse(url);
			}
		});
		body.add(source);

		fillIcons(cells);
		capHeight(body);
		return body;
	}

	/**
	 * One slot: the top pick's icon and name, the slot on the right, and how many alternatives the wiki
	 * ranks under it. The full ranked list is the tooltip; clicking opens the top pick's wiki page.
	 */
	private JComponent slotRow(GearSetup.Slot slot, List<IconCell> cells)
	{
		GearSetup.Option best = slot.getOptions().get(0);
		GearSetup.Item top = best.getItems().get(0);

		JPanel r = rowX();
		JLabel icon = new JLabel();
		Dimension d = new Dimension(ICON_W, ICON_H);
		icon.setPreferredSize(d);
		icon.setMinimumSize(d);
		icon.setMaximumSize(d);
		cells.add(new IconCell(top.getImageName(), icon));
		r.add(icon);
		r.add(Box.createRigidArea(new Dimension(4, 0)));

		JPanel text = new JPanel();
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		text.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		text.add(wrappedLabel(optionText(best), Color.WHITE, false, NAME_WIDTH));
		int more = slot.getOptions().size() - 1;
		if (more > 0)
		{
			JLabel alt = new JLabel("+" + more + " alternative" + (more == 1 ? "" : "s"));
			alt.setFont(FontManager.getRunescapeSmallFont());
			alt.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
			text.add(alt);
		}
		r.add(text);
		r.add(Box.createHorizontalGlue());

		JLabel slotName = new JLabel(slotLabel(slot.getName()));
		slotName.setFont(FontManager.getRunescapeSmallFont());
		slotName.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		r.add(slotName);

		setTooltip(r, rankedTooltip(slot));
		String url = WIKI + top.getLink().replace(' ', '_');
		MouseAdapter open = new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				LinkBrowser.browse(url);
			}
		};
		applyClick(r, open);
		capHeight(r);
		return r;
	}

	private static String optionText(GearSetup.Option o)
	{
		StringBuilder sb = new StringBuilder();
		for (GearSetup.Item i : o.getItems())
		{
			sb.append(sb.length() > 0 ? " / " : "").append(i.getLabel());
		}
		if (!o.getNote().isEmpty())
		{
			sb.append(' ').append(o.getNote());
		}
		return sb.toString();
	}

	private static String rankedTooltip(GearSetup.Slot slot)
	{
		StringBuilder sb = new StringBuilder("<html><b>").append(StatFormat.esc(slotLabel(slot.getName()))).append("</b>");
		List<GearSetup.Option> options = slot.getOptions();
		for (int i = 0; i < options.size(); i++)
		{
			sb.append("<br>").append(i + 1).append(". ").append(StatFormat.esc(optionText(options.get(i))));
		}
		return sb.append("</html>").toString();
	}

	private static String slotLabel(String slot)
	{
		if (slot.equals("2h"))
		{
			return "Two-handed";
		}
		return slot.substring(0, 1).toUpperCase(Locale.ROOT) + slot.substring(1);
	}

	/** Which kind of wiki page a setup comes from: a monster's strategy guide or its Slayer task page. */
	private static String sourceLabel(String page)
	{
		return page.startsWith("Slayer task/") ? "task guide" : "strategy guide";
	}

	/** Resolve each top pick's id and icon on the client thread, then set them on the EDT in one hop. */
	private void fillIcons(List<IconCell> cells)
	{
		if (cells.isEmpty())
		{
			return;
		}
		clientThread.invoke(() ->
		{
			List<Runnable> updates = new ArrayList<>();
			for (IconCell c : cells)
			{
				Integer id = itemIds.resolve(itemManager, c.itemName);
				if (id == null)
				{
					continue;
				}
				AsyncBufferedImage img = itemManager.getImage(id);
				if (img != null)
				{
					updates.add(() -> img.addTo(c.label));
				}
			}
			if (!updates.isEmpty())
			{
				SwingUtilities.invokeLater(() -> updates.forEach(Runnable::run));
			}
		});
	}

	// ------------------------------------------------------------ small helpers

	private static JLabel note(String text)
	{
		JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		l.setAlignmentX(LEFT_ALIGNMENT);
		return l;
	}

	/** Swing shows the child's tooltip, not the row's, so set it everywhere. */
	private static void setTooltip(Component c, String tip)
	{
		if (c instanceof JComponent)
		{
			((JComponent) c).setToolTipText(tip);
		}
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				setTooltip(child, tip);
			}
		}
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

	private static final class IconCell
	{
		private final String itemName;
		private final JLabel label;

		private IconCell(String itemName, JLabel label)
		{
			this.itemName = itemName;
			this.label = label;
		}
	}
}
