package com.bettermonsterexamine;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.util.LinkBrowser;

/**
 * Searchable monster stats panel. Renders a monster's stats as a wiki-style infobox in a
 * single vertical scroll. Fields the bundled dataset lacks (aggressive, poisonous, xp
 * bonus, full max-hit list, immunities) are filled in asynchronously from the wiki.
 */
public class BetterMonsterExaminePanel extends PluginPanel
{
	/** Uniform on-screen size every stat-grid icon is scaled to. */
	private static final int ICON_BOX = 22;

	/** Config group the persisted Recent/Favorites JSON lives under (alongside the typed config). */
	private static final String CONFIG_GROUP = "bettermonsterexamine";
	private static final String HISTORY_KEY = "historyData";

	private final MonsterIcons overlay;
	private final MonsterDataService data;
	private final WikiInfoboxService wiki;
	private final BetterMonsterExamineConfig config;
	private final ConfigManager configManager;
	/** Current player combat level (-1 when unknown, e.g. logged out), for tooltip-style colouring. */
	private final IntSupplier playerCombatLevel;
	/** Current player Hitpoints level (-1 when unknown), to flag max hits that exceed it. */
	private final IntSupplier playerHpLevel;

	private final IconTextField searchField = new IconTextField();
	private final JPanel resultsPanel = new JPanel();
	private final JPanel cardPanel = new JPanel();
	/** Recent/Favorites list view, shown in place of results + card while a mode is active. */
	private final JPanel listPanel = new JPanel();

	/** Which view the panel is showing: normal search/card, or one of the two list modes. */
	private enum Mode
	{
		NORMAL, RECENT, FAVORITES
	}

	private final LookupHistory history;
	private Mode mode = Mode.NORMAL;
	private JButton recentTabButton;
	private JButton favoritesTabButton;

	private List<MonsterData> currentVariants;
	private MonsterData currentSelection;

	/**
	 * Notified with the monster whenever a card renders, so the in-game overlay can mirror what
	 * the side panel is showing. The plugin sets this; left null when there's no overlay to feed.
	 */
	private Consumer<MonsterData> selectionListener;

	public BetterMonsterExaminePanel(MonsterIcons overlay, MonsterDataService data, WikiInfoboxService wiki, BetterMonsterExamineConfig config, ConfigManager configManager, IntSupplier playerCombatLevel, IntSupplier playerHpLevel, BufferedImage titleIcon)
	{
		super(false);
		this.overlay = overlay;
		this.data = data;
		this.wiki = wiki;
		this.config = config;
		this.configManager = configManager;
		this.playerCombatLevel = playerCombatLevel;
		this.playerHpLevel = playerHpLevel;
		this.history = LookupHistory.fromJson(configManager.getConfiguration(CONFIG_GROUP, HISTORY_KEY));

		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBorder(new EmptyBorder(8, 8, 8, 8));

		// Search bar (clearly a search bar: magnifier icon)
		searchField.setIcon(IconTextField.Icon.SEARCH);
		searchField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchField.setPreferredSize(new Dimension(100, 30));
		searchField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		searchField.setMinimumSize(new Dimension(0, 30));
		searchField.getDocument().addDocumentListener(new DocumentListener()
		{
			public void insertUpdate(DocumentEvent e)
			{
				onSearchTextChanged();
			}

			public void removeUpdate(DocumentEvent e)
			{
				onSearchTextChanged();
			}

			public void changedUpdate(DocumentEvent e)
			{
				onSearchTextChanged();
			}
		});

		// The two mode buttons sit in the search row: ↺ Recent and ★ Favorites. Glyph text with a
		// tooltip (no bundled icons yet); the active mode is flagged in brand orange.
		recentTabButton = makeTabButton("↺", "Recent", () -> onTabClicked(Mode.RECENT));
		favoritesTabButton = makeTabButton("★", "Favorites", () -> onTabClicked(Mode.FAVORITES));

		JPanel searchRow = new JPanel();
		searchRow.setLayout(new BoxLayout(searchRow, BoxLayout.X_AXIS));
		searchRow.setAlignmentX(LEFT_ALIGNMENT);
		searchRow.add(searchField);
		searchRow.add(Box.createRigidArea(new Dimension(4, 0)));
		searchRow.add(recentTabButton);
		searchRow.add(Box.createRigidArea(new Dimension(2, 0)));
		searchRow.add(favoritesTabButton);
		searchRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		add(searchRow);
		add(Box.createRigidArea(new Dimension(0, 6)));

		// Single scroll holding results + the stats card, plus the list view (one shown at a time)
		resultsPanel.setLayout(new BoxLayout(resultsPanel, BoxLayout.Y_AXIS));
		resultsPanel.setAlignmentX(LEFT_ALIGNMENT);
		cardPanel.setLayout(new BoxLayout(cardPanel, BoxLayout.Y_AXIS));
		cardPanel.setAlignmentX(LEFT_ALIGNMENT);
		listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
		listPanel.setAlignmentX(LEFT_ALIGNMENT);
		listPanel.setVisible(false);

		JPanel scrollContent = new WidthTrackingPanel();
		scrollContent.setLayout(new BoxLayout(scrollContent, BoxLayout.Y_AXIS));
		scrollContent.add(resultsPanel);
		scrollContent.add(Box.createRigidArea(new Dimension(0, 6)));
		scrollContent.add(cardPanel);
		scrollContent.add(listPanel);
		scrollContent.add(Box.createVerticalGlue());

		JScrollPane scroll = new JScrollPane(scrollContent,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(null);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		scroll.setAlignmentX(LEFT_ALIGNMENT);
		add(scroll);

		// Fresh panel opens into Recent by default (or the plain hint when history is disabled).
		showDefaultView();
	}

	// ------------------------------------------------------------------ search

	public void search(String query, boolean selectFirst, String preferredVersion)
	{
		resultsPanel.removeAll();

		// An empty/cleared field shows no results (don't dump the whole bestiary).
		if (query == null || query.trim().isEmpty())
		{
			revalidate();
			repaint();
			return;
		}

		List<String> names = data.searchNames(query, 40);

		for (String name : names)
		{
			JButton b = new JButton(name);
			b.setAlignmentX(LEFT_ALIGNMENT);
			b.setHorizontalAlignment(JButton.LEFT);
			b.setFont(FontManager.getRunescapeSmallFont());
			b.setMaximumSize(new Dimension(Integer.MAX_VALUE, b.getPreferredSize().height));
			b.addActionListener(e -> select(name, null));
			resultsPanel.add(b);
		}

		if (selectFirst && !names.isEmpty())
		{
			select(names.get(0), preferredVersion);
		}

		revalidate();
		repaint();
	}

	private void select(String name, String preferredVersion)
	{
		currentVariants = data.variantsForName(name);
		if (currentVariants.isEmpty())
		{
			// A stored entry whose monster no longer resolves: drop it quietly (once the dataset
			// has actually loaded — an async-empty index isn't proof the monster is gone).
			if (data.isLoaded())
			{
				pruneDead(name);
			}
			return;
		}

		// Picking a monster collapses the result list so only its card remains on screen.
		resultsPanel.removeAll();

		// A real selection always lands in the normal card view, even from an open list mode.
		if (mode != Mode.NORMAL)
		{
			mode = Mode.NORMAL;
			setListVisible(false);
			updateTabButtons();
		}

		currentSelection = null;
		if (preferredVersion != null)
		{
			for (MonsterData m : currentVariants)
			{
				if (preferredVersion.equalsIgnoreCase(m.getVersion()))
				{
					currentSelection = m;
					break;
				}
			}
		}
		if (currentSelection == null)
		{
			currentSelection = chooseDefault(currentVariants);
		}
		recordSelection(currentSelection);
		renderCard();
	}

	/**
	 * Prefer the standard form with real data; otherwise (e.g. Vorkath, which only has
	 * "Dragon Slayer II" and "Post-quest") fall back to the highest-level variant.
	 */
	private MonsterData chooseDefault(List<MonsterData> variants)
	{
		for (MonsterData m : variants)
		{
			boolean emptyVersion = m.getVersion() == null || m.getVersion().isEmpty();
			boolean hasData = m.getSkills() != null && m.getSkills().getHp() > 0;
			if (emptyVersion && hasData)
			{
				return m;
			}
		}

		MonsterData best = null;
		for (MonsterData m : variants)
		{
			boolean hasData = m.getSkills() != null && m.getSkills().getHp() > 0;
			if (hasData && (best == null || m.getLevel() > best.getLevel()))
			{
				best = m;
			}
		}
		if (best != null)
		{
			return best;
		}

		for (MonsterData m : variants)
		{
			if (m.getVersion() == null || m.getVersion().isEmpty())
			{
				return m;
			}
		}
		return variants.get(0);
	}

	// ------------------------------------------------------------------ render

	/** Re-render the current card in place (e.g. after the highlight mode changes). */
	public void refresh()
	{
		if (currentSelection != null)
		{
			renderCard();
		}
	}

	private void renderCard()
	{
		cardPanel.removeAll();
		MonsterData m = currentSelection;

		cardPanel.add(header(m));
		cardPanel.add(Box.createRigidArea(new Dimension(0, 6)));

		buildWiki(m);

		revalidate();
		repaint();

		// Lazily pull the wiki-only fields (aggressive/poisonous/xp/full max hit/immunities),
		// then re-render when they land.
		if (wiki.getCached(m.getName()) == null)
		{
			wiki.fetch(m.getName(), () -> SwingUtilities.invokeLater(() ->
			{
				if (currentSelection != null && currentSelection.getName().equals(m.getName()))
				{
					renderCard();
				}
			}));
		}

		// Let the overlay mirror the panel's current monster (and pick up the wiki fields on the
		// re-render above once they land).
		if (selectionListener != null)
		{
			selectionListener.accept(m);
		}
	}

	/** Register a listener fed the current monster on each render, for the in-game overlay. */
	public void setSelectionListener(Consumer<MonsterData> listener)
	{
		this.selectionListener = listener;
	}

	// ------------------------------------------------ recent / favorites views

	/** Search-field document changes: empty returns to the default view; text shows live results. */
	private void onSearchTextChanged()
	{
		String text = searchField.getText();
		if (text == null || text.trim().isEmpty())
		{
			showDefaultView();
			return;
		}
		// Typing leaves any active list mode and shows live results.
		if (mode != Mode.NORMAL)
		{
			mode = Mode.NORMAL;
			setListVisible(false);
			updateTabButtons();
		}
		// Don't trail the previously-viewed card beneath the live results — a fresh query is a
		// clean slate until the user picks a result.
		cardPanel.removeAll();
		currentSelection = null;
		search(text, false, null);
	}

	/** The view for an empty search field: Recent by default, or the plain hint when history is off. */
	private void showDefaultView()
	{
		if (config.enableHistory())
		{
			enterMode(Mode.RECENT);
			return;
		}
		mode = Mode.NORMAL;
		setListVisible(false);
		resultsPanel.removeAll();
		// Keep whatever card is showing; only fall back to the hint when there's nothing at all.
		if (currentSelection == null && cardPanel.getComponentCount() == 0)
		{
			hint("Search a monster, or right-click one in game → Stats.");
		}
		updateTabButtons();
		revalidate();
		repaint();
	}

	/** A mode button: toggles its list view off if already active, else switches to it. */
	private void onTabClicked(Mode target)
	{
		if (mode == target)
		{
			exitListMode();
		}
		else
		{
			enterMode(target);
		}
	}

	private void enterMode(Mode target)
	{
		mode = target;
		setListVisible(true);
		renderList();
		updateTabButtons();
		revalidate();
		repaint();
	}

	private void exitListMode()
	{
		mode = Mode.NORMAL;
		setListVisible(false);
		ensureNormalContent();
		updateTabButtons();
		revalidate();
		repaint();
	}

	/** Show the list view, or the results + card pair — never both at once. */
	private void setListVisible(boolean listShown)
	{
		listPanel.setVisible(listShown);
		resultsPanel.setVisible(!listShown);
		cardPanel.setVisible(!listShown);
	}

	/** Back in the normal view with nothing to show, fall back to the search hint. */
	private void ensureNormalContent()
	{
		String text = searchField.getText();
		boolean hasSearch = text != null && !text.trim().isEmpty();
		if (!hasSearch && currentSelection == null && cardPanel.getComponentCount() == 0)
		{
			hint("Search a monster, or right-click one in game → Stats.");
		}
	}

	private void updateTabButtons()
	{
		boolean on = config.enableHistory();
		recentTabButton.setVisible(on);
		favoritesTabButton.setVisible(on);
		recentTabButton.setForeground(mode == Mode.RECENT ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
		favoritesTabButton.setForeground(mode == Mode.FAVORITES ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
	}

	private void renderList()
	{
		listPanel.removeAll();
		boolean fav = mode == Mode.FAVORITES;
		listPanel.add(listHeader(fav));
		listPanel.add(Box.createRigidArea(new Dimension(0, 4)));

		int shown = 0;
		for (LookupHistory.Entry e : (fav ? history.favorites() : history.recent()))
		{
			// Skip rows whose monster no longer resolves — but only once the dataset has loaded,
			// since an async-empty index would otherwise hide everything on a cold start.
			if (data.isLoaded() && data.variantsForName(e.name).isEmpty())
			{
				continue;
			}
			listPanel.add(listRow(e));
			shown++;
		}
		if (shown == 0)
		{
			listPanel.add(listEmptyHint(fav));
		}
		revalidate();
		repaint();
	}

	/** The list view's title row, with a Clear control on the right when the list is non-empty. */
	private JComponent listHeader(boolean fav)
	{
		JPanel r = new JPanel();
		r.setLayout(new BoxLayout(r, BoxLayout.X_AXIS));
		r.setAlignmentX(LEFT_ALIGNMENT);

		JLabel title = new JLabel((fav ? "Favorites" : "Recent").toUpperCase(Locale.ROOT));
		title.setFont(FontManager.getRunescapeSmallFont());
		title.setForeground(ColorScheme.BRAND_ORANGE);
		r.add(title);
		r.add(Box.createHorizontalGlue());

		boolean any = !(fav ? history.favorites() : history.recent()).isEmpty();
		if (any)
		{
			JButton clear = new JButton(fav ? "Clear favorites" : "Clear history");
			clear.setFont(FontManager.getRunescapeSmallFont());
			clear.setFocusable(false);
			clear.setMargin(new Insets(0, 6, 0, 6));
			clear.addActionListener(e ->
			{
				if (fav)
				{
					history.clearFavorites();
				}
				else
				{
					history.clearRecent();
				}
				persist();
				renderList();
			});
			r.add(clear);
		}
		capHeight(r);
		return r;
	}

	private JComponent listRow(LookupHistory.Entry e)
	{
		JButton b = new JButton(rowLabel(e));
		b.setAlignmentX(LEFT_ALIGNMENT);
		b.setHorizontalAlignment(JButton.LEFT);
		b.setFont(FontManager.getRunescapeSmallFont());
		b.setMaximumSize(new Dimension(Integer.MAX_VALUE, b.getPreferredSize().height));
		b.addActionListener(ev -> select(e.name, e.version));
		return b;
	}

	/** Row label: name + version when the monster has variants, plain name otherwise. */
	private String rowLabel(LookupHistory.Entry e)
	{
		if (!e.version.isEmpty() && data.variantsForName(e.name).size() > 1)
		{
			return e.name + " (" + e.version + ")";
		}
		return e.name;
	}

	private JComponent listEmptyHint(boolean fav)
	{
		String msg = fav
			? "No favorites yet. Open a monster and tap ★ to pin it here."
			: "Nothing here yet. Search a monster, or right-click one in game → Stats.";
		JLabel l = new JLabel("<html><body style='width:180px'>" + msg + "</body></html>");
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		l.setAlignmentX(LEFT_ALIGNMENT);
		return l;
	}

	private JButton makeTabButton(String glyph, String tooltip, Runnable onClick)
	{
		JButton b = new JButton(glyph);
		b.setToolTipText(tooltip);
		b.setFocusable(false);
		b.setMargin(new Insets(0, 4, 0, 4));
		Dimension d = new Dimension(30, 30);
		b.setPreferredSize(d);
		b.setMinimumSize(d);
		b.setMaximumSize(d);
		b.addActionListener(e -> onClick.run());
		return b;
	}

	private JButton makeStarButton(MonsterData m)
	{
		boolean fav = history.isFavorite(m.getName(), m.getVersion());
		JButton b = new JButton(fav ? "★" : "☆");
		b.setToolTipText(fav ? "Remove from favorites" : "Add to favorites");
		b.setForeground(fav ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
		b.setFocusable(false);
		b.setMargin(new Insets(0, 4, 0, 4));
		b.addActionListener(e ->
		{
			history.toggleFavorite(m.getName(), m.getVersion());
			persist();
			renderCard();
			if (mode == Mode.FAVORITES)
			{
				renderList();
			}
		});
		return b;
	}

	/**
	 * Record a user-initiated lookup. Called from {@link #select} and the variant dropdown, plus
	 * {@link #recordLookup} for overlay-only in-game lookups; never from programmatic re-renders.
	 */
	private void recordSelection(MonsterData m)
	{
		if (!config.enableHistory() || m == null)
		{
			return;
		}
		history.record(m.getName(), m.getVersion());
		persist();
		// Keep an open Recent view in sync (e.g. an in-game lookup while it's on screen).
		if (mode == Mode.RECENT)
		{
			renderList();
		}
	}

	/**
	 * Record an in-game "Stats" lookup that didn't open the panel (overlay-only target), so it
	 * still lands in Recent. Resolves the variant the same way the overlay does. Called on the EDT.
	 */
	public void recordLookup(String name, String version)
	{
		if (!config.enableHistory() || name == null)
		{
			return;
		}
		List<MonsterData> variants = data.variantsForName(name);
		if (variants.isEmpty())
		{
			return;
		}
		recordSelection(pickVariant(variants, version));
	}

	private MonsterData pickVariant(List<MonsterData> variants, String version)
	{
		if (version != null && !version.isEmpty())
		{
			for (MonsterData v : variants)
			{
				if (version.equalsIgnoreCase(v.getVersion()))
				{
					return v;
				}
			}
		}
		return chooseDefault(variants);
	}

	/** Drop a stored entry whose monster no longer resolves, refreshing an open list view. */
	private void pruneDead(String name)
	{
		if (history.removeAllByName(name))
		{
			persist();
			if (mode != Mode.NORMAL)
			{
				renderList();
			}
		}
	}

	private void persist()
	{
		configManager.setConfiguration(CONFIG_GROUP, HISTORY_KEY, history.toJson());
	}

	/** React to the enableHistory config toggle: hide/show the buttons and any open list view. */
	public void onHistoryConfigChanged()
	{
		if (!config.enableHistory() && mode != Mode.NORMAL)
		{
			mode = Mode.NORMAL;
			setListVisible(false);
			ensureNormalContent();
		}
		updateTabButtons();
		// A card on screen may need its star shown/hidden to match the new setting.
		if (currentSelection != null && mode == Mode.NORMAL)
		{
			renderCard();
		}
		revalidate();
		repaint();
	}

	private JComponent header(MonsterData m)
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
		WikiInfo wi = wiki.getCached(m.getName());
		String examine = wi != null ? wi.get("examine", m.getVersion()) : null;
		if (examine != null && !examine.trim().isEmpty())
		{
			block.add(Box.createRigidArea(new Dimension(0, 2)));
			block.add(wrappedLabel(examine.trim(), ColorScheme.LIGHT_GRAY_COLOR, true));
		}

		// Variant selector (only when >1 form shares the name)
		if (currentVariants != null && currentVariants.size() > 1)
		{
			JComboBox<String> combo = new JComboBox<>();
			for (MonsterData v : currentVariants)
			{
				String v2 = (v.getVersion() == null || v.getVersion().isEmpty()) ? "Standard" : v.getVersion();
				combo.addItem(v2);
			}
			combo.setSelectedIndex(currentVariants.indexOf(currentSelection));
			combo.setFont(FontManager.getRunescapeSmallFont());
			combo.setAlignmentX(LEFT_ALIGNMENT);
			combo.addActionListener(e ->
			{
				int i = combo.getSelectedIndex();
				if (i >= 0 && i < currentVariants.size())
				{
					currentSelection = currentVariants.get(i);
					// Switching variant is a user-initiated lookup of that form — record it so
					// Recent reflects the variant on screen. (Safe: setSelectedIndex above runs
					// before this listener is attached, so rebuilding the card never re-fires it.)
					recordSelection(currentSelection);
					renderCard();
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

	private static final String DPS_CALC_URL = "https://tools.runescape.wiki/osrs-dps/";

	private static String wikiUrl(MonsterData m)
	{
		return "https://oldschool.runescape.wiki/w/" + m.getName().replace(' ', '_');
	}

	// ---- Wiki: faithful to the OSRS Wiki monster infobox --------------------

	private void buildWiki(MonsterData m)
	{
		MonsterData.Skills s = m.getSkills();
		MonsterData.Offensive o = m.getOffensive();
		MonsterData.Defensive d = m.getDefensive();

		// Wiki-only fields (xp bonus, aggressive, poisonous, full max hit, examine) load async.
		WikiInfo wi = wiki.getCached(m.getName());
		String ver = m.getVersion();

		// ATTRIBUTES — dataset attributes/size/slayer/flat armour + wiki xp/aggressive/poisonous.
		JPanel props = block();
		props.add(header("Attributes"));
		boolean anyProp = false;
		// Size and attributes share one line, size first: e.g. "7x7, Draconic, Undead, Fiery".
		// The box header already says "Attributes", so the names need no label of their own.
		String sizeText = m.getSize() > 0 ? m.getSize() + "x" + m.getSize() : null;
		String attrText = (m.getAttributes() != null && !m.getAttributes().isEmpty())
			? attributeNames(m.getAttributes()) : null;
		String sizeAttr = join(", ", sizeText, attrText);
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
		if (xp != null && !xp.trim().isEmpty() && !isZero(xp))
		{
			String xpTrimmed = xp.trim();
			boolean xpPenalty = xpTrimmed.startsWith("-");
			props.add(kv("XP bonus", (xpPenalty ? "" : "+") + xpTrimmed + "%", xpPenalty ? danger() : good()));
			anyProp = true;
		}
		String aggr = wi != null ? wi.get("aggressive", ver) : null;
		if (aggr != null)
		{
			boolean yes = aggr.trim().toLowerCase(Locale.ROOT).startsWith("yes");
			props.add(kv("Aggressive", aggr.trim(), yes ? danger() : Color.WHITE,
				yes ? "Attacks on sight." : null));
			anyProp = true;
		}
		String pois = wi != null ? wi.get("poisonous", ver) : null;
		if (pois != null)
		{
			boolean yes = pois.trim().toLowerCase(Locale.ROOT).startsWith("yes");
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
			cardPanel.add(props);
			cardPanel.add(Box.createRigidArea(new Dimension(0, 6)));
		}

		// COMBAT INFO — attack style + speed (dataset).
		JPanel combatInfo = block();
		combatInfo.add(header("Combat info"));
		combatInfo.add(kvWrappedRight("Attack style", styleString(m)));
		combatInfo.add(kv("Attack speed", attackSpeed(m), Color.WHITE));
		capHeight(combatInfo);
		cardPanel.add(combatInfo);
		cardPanel.add(Box.createRigidArea(new Dimension(0, 6)));

		// MAX HIT — its own box; multi-hit monsters list several values, one per line. Values
		// above the player's Hitpoints level are flagged red (it could take more than your level).
		String wikiMax = wi != null ? wi.get("max hit", ver) : null;
		String maxHitText = wikiMax != null ? wikiMax.replace(", ", "\n") : nz(m.getMaxHit());
		JPanel maxHit = block();
		maxHit.add(header("Max hit"));
		maxHit.add(maxHitLabel(maxHitText, playerHpLevel.getAsInt()));
		capHeight(maxHit);
		cardPanel.add(maxHit);
		cardPanel.add(Box.createRigidArea(new Dimension(0, 6)));

		// Combat stats: the monster's six levels as an icon-over-value row
		if (s != null)
		{
			JPanel combat = gridBlock("Combat stats",
				new BufferedImage[]{overlay.hitpointsIcon, overlay.attackIcon, overlay.strengthIcon, overlay.defenceIcon, overlay.magicIcon, overlay.rangedIcon},
				new String[]{num(s.getHp()), num(s.getAtk()), num(s.getStr()), num(s.getDef()), num(s.getMagic()), num(s.getRanged())},
				new String[]{"Hitpoints", "Attack", "Strength", "Defence", "Magic", "Ranged"});
			cardPanel.add(combat);
			cardPanel.add(Box.createRigidArea(new Dimension(0, 6)));
		}

		// Aggressive stats (order: Attack, Strength, Magic, Magic str, Ranged, Ranged str)
		if (o != null)
		{
			JPanel aggro = gridBlock("Aggressive stats",
				new BufferedImage[]{overlay.attackIcon, overlay.strengthIcon, overlay.magicIcon, overlay.magicDamageIcon, overlay.rangedIcon, overlay.rangedStrengthIcon},
				new String[]{bonus(o.getAtk()), bonus(o.getStr()), bonus(o.getMagic()), bonus(o.getMagicStr()), bonus(o.getRanged()), bonus(o.getRangedStr())},
				new String[]{"Attack", "Strength", "Magic", "Magic damage", "Ranged", "Ranged strength"});
			cardPanel.add(aggro);
			cardPanel.add(Box.createRigidArea(new Dimension(0, 6)));
		}

		// Defensive bonuses, grouped like the wiki
		if (d != null)
		{
			cardPanel.add(gridBlock("Melee defence",
				new BufferedImage[]{overlay.stabIcon, overlay.slashIcon, overlay.crushIcon},
				new String[]{bonus(d.getStab()), bonus(d.getSlash()), bonus(d.getCrush())},
				new String[]{"Stab", "Slash", "Crush"}));
			cardPanel.add(Box.createRigidArea(new Dimension(0, 6)));

			String weakLabel = m.getWeakness() != null && m.getWeakness().getElement() != null
				? cap(m.getWeakness().getElement()) + " weakness" : "Elemental weakness";
			cardPanel.add(gridBlock("Magic defence",
				new BufferedImage[]{overlay.magicDefenceIcon, weaknessIcon(m)},
				new String[]{bonus(d.getMagic()), m.getWeakness() != null ? m.getWeakness().getSeverity() + "%" : "—"},
				new String[]{"Magic defence", weakLabel}));
			cardPanel.add(Box.createRigidArea(new Dimension(0, 6)));

			cardPanel.add(gridBlock("Ranged defence",
				new BufferedImage[]{overlay.lightIcon, overlay.standardIcon, overlay.heavyIcon},
				new String[]{bonus(d.getLight()), bonus(d.getStandard()), bonus(d.getHeavy())},
				new String[]{"Light", "Standard", "Heavy"}));
		}

		// Immunities — burn from the dataset, the rest (poison/venom/cannon/thrall) from wiki.
		JPanel imm = block();
		imm.add(header("Immunities"));
		boolean anyImm = false;
		if (burnImmunity(m) != null)
		{
			imm.add(kv("Burn", burnImmunity(m), Color.WHITE));
			anyImm = true;
		}
		if (wi != null)
		{
			String poison = resistanceLabel(wi.get("poisonresistance", ver));
			if (poison != null)
			{
				imm.add(kv("Poison", poison, danger()));
				anyImm = true;
			}
			String venom = resistanceLabel(wi.get("venomresistance", ver));
			if (venom != null)
			{
				imm.add(kv("Venom", venom, danger()));
				anyImm = true;
			}
			if (yes(wi.get("immunecannon", ver)))
			{
				imm.add(kv("Cannon", "Immune", danger()));
				anyImm = true;
			}
			if (yes(wi.get("immunethrall", ver)))
			{
				imm.add(kv("Thrall", "Immune", danger()));
				anyImm = true;
			}
		}
		if (anyImm)
		{
			cardPanel.add(Box.createRigidArea(new Dimension(0, 6)));
			capHeight(imm);
			cardPanel.add(imm);
		}
	}

	/** A titled block whose stats are laid out as icon-over-value cells (wiki style). */
	private JPanel gridBlock(String title, BufferedImage[] icons, String[] values, String[] labels)
	{
		JPanel b = block();
		b.add(header(title));

		// GridLayout gives every cell an equal column and centres its content, so groups
		// with few values (melee/magic defence) spread evenly across the width (space-around).
		JPanel grid = new JPanel(new GridLayout(1, icons.length, 4, 0));
		grid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		grid.setAlignmentX(LEFT_ALIGNMENT);
		for (int i = 0; i < icons.length; i++)
		{
			grid.add(statCell(icons[i], values[i], labels[i]));
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

	// Package-private so the in-game overlay (MonsterCardOverlay) can reuse the same formatting.
	static String bonus(int v)
	{
		return (v >= 0 ? "+" : "") + v;
	}

	// --------------------------------------------------------------- utilities

	/** A vertical content panel that fills the scroll viewport width (no horizontal clipping). */
	private static class WidthTrackingPanel extends JPanel implements Scrollable
	{
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return 16;
		}

		public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return 60;
		}

		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		public boolean getScrollableTracksViewportHeight()
		{
			return false;
		}
	}

	private BufferedImage weaknessIcon(MonsterData m)
	{
		if (m.getWeakness() == null || m.getWeakness().getElement() == null)
		{
			return overlay.elementalIcon;
		}
		return overlay.getElementalWeaknessIcon(cap(m.getWeakness().getElement()));
	}

	private String styleString(MonsterData m)
	{
		List<String> st = m.getStyle();
		return st == null || st.isEmpty() ? "—" : String.join(", ", st);
	}

	private static String nz(String s)
	{
		return s == null || s.isEmpty() ? "—" : s;
	}

	/** Join the non-null, non-empty parts with {@code sep}; null when nothing remains. */
	private static String join(String sep, String... parts)
	{
		StringBuilder sb = new StringBuilder();
		for (String p : parts)
		{
			if (p == null || p.isEmpty())
			{
				continue;
			}
			if (sb.length() > 0)
			{
				sb.append(sep);
			}
			sb.append(p);
		}
		return sb.length() == 0 ? null : sb.toString();
	}

	static String burnImmunity(MonsterData m)
	{
		return m.getImmunities() != null ? m.getImmunities().getBurn() : null;
	}

	/** Wiki poison/venom resistance: 100 → "Immune", >0 → "N% resistance", else null. */
	static String resistanceLabel(String resistance)
	{
		if (resistance == null)
		{
			return null;
		}
		try
		{
			int r = Integer.parseInt(resistance.trim().replace("%", ""));
			if (r >= 100)
			{
				return "Immune";
			}
			return r > 0 ? r + "% resistance" : null;
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	static boolean yes(String v)
	{
		return v != null && v.trim().equalsIgnoreCase("yes");
	}

	/** Map dataset attribute keys to their wiki display names (e.g. dragon → Draconic). */
	static String attributeNames(List<String> attrs)
	{
		StringBuilder sb = new StringBuilder();
		for (String a : attrs)
		{
			if (sb.length() > 0)
			{
				sb.append(", ");
			}
			sb.append(attributeName(a));
		}
		return sb.toString();
	}

	private static String attributeName(String a)
	{
		switch (a)
		{
			case "dragon":
				return "Draconic";
			case "vampyre1":
				return "Vampyre (tier 1)";
			case "vampyre2":
				return "Vampyre (tier 2)";
			case "vampyre3":
				return "Vampyre (tier 3)";
			default:
				return cap(a);
		}
	}

	/** "5 ticks (3.0 seconds)". */
	static String attackSpeed(MonsterData m)
	{
		int t = m.getSpeed();
		return t + (t == 1 ? " tick" : " ticks") + " (" + String.format("%.1f", t * 0.6) + " seconds)";
	}

	static String num(int v)
	{
		return v <= 0 ? "—" : String.valueOf(v);
	}

	static String cap(String s)
	{
		return s == null || s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
	}

	private static String esc(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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

	/** Label on its own line with a full-width, wrapping value beneath (for long text). */
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
			+ esc(v).replace("\n", "<br>") + "</body></html>");
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
			if (highlight && maxValue(lines[i]) > hpLevel)
			{
				anyOver = true;
				// In colour-blind mode add a warning sign so the cue survives without colour.
				String line = esc(lines[i]) + (cb ? " ⚠" : "");
				sb.append("<span style='color:").append(hex).append("'>").append(line).append("</span>");
			}
			else
			{
				sb.append(esc(lines[i]));
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

	private static final Pattern DIGITS = Pattern.compile("\\d+");

	/** The largest number in a max-hit line's value (the part before any "(label)"), or -1. */
	static int maxValue(String line)
	{
		int paren = line.indexOf('(');
		String value = paren >= 0 ? line.substring(0, paren) : line;
		Matcher m = DIGITS.matcher(value);
		int max = -1;
		while (m.find())
		{
			max = Math.max(max, Integer.parseInt(m.group()));
		}
		return max;
	}

	/** A full-width, wrapping value label (used for examine text and the max-hit list). */
	private JLabel wrappedLabel(String text, Color color, boolean italic)
	{
		JLabel l = new JLabel("<html><body style='width:200px'>" + esc(text).replace("\n", "<br>") + "</body></html>");
		Font f = FontManager.getRunescapeSmallFont();
		l.setFont(italic ? f.deriveFont(Font.ITALIC) : f);
		l.setForeground(color);
		l.setAlignmentX(LEFT_ALIGNMENT);
		return l;
	}

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

	/** True when a numeric wiki value (e.g. XP bonus) is zero, so it can be omitted. */
	private static boolean isZero(String v)
	{
		try
		{
			return Double.parseDouble(v.trim()) == 0;
		}
		catch (NumberFormatException e)
		{
			return false;
		}
	}

	private JLabel header(String text)
	{
		JLabel h = new JLabel(text.toUpperCase(Locale.ROOT));
		h.setFont(FontManager.getRunescapeSmallFont());
		h.setForeground(ColorScheme.BRAND_ORANGE);
		h.setAlignmentX(LEFT_ALIGNMENT);
		h.setBorder(new EmptyBorder(0, 0, 3, 0));
		return h;
	}

	private void hint(String text)
	{
		JLabel l = new JLabel("<html><body style='width:180px'>" + text + "</body></html>");
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		l.setAlignmentX(LEFT_ALIGNMENT);
		cardPanel.add(l);
	}

	private void capHeight(JComponent c)
	{
		c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
		c.setAlignmentX(LEFT_ALIGNMENT);
	}
}
