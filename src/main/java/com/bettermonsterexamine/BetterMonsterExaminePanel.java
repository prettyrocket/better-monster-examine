package com.bettermonsterexamine;

import com.bettermonsterexamine.loot.DropPageService;
import com.bettermonsterexamine.loot.DropsCard;
import com.bettermonsterexamine.loot.ItemIdService;
import com.google.gson.Gson;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
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
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;

/**
 * The searchable monster side panel. Owns the search field, the Recent/Favorites views, and the
 * selection state machine. A shared {@link MonsterHeader} sits above a {@code Stats | Drops} tab
 * strip whose body swaps between the stats {@link MonsterCard} and the {@link DropsCard}, so the
 * selected monster + variant stay put across both tabs. The Recent/Favorites model lives in
 * {@link LookupHistory}. One of four sibling regions shows at a time: live search results, the card
 * area, a list view, or the empty-state hint.
 */
public class BetterMonsterExaminePanel extends PluginPanel
{
	/** Config group the persisted Recent/Favorites JSON lives under (alongside the typed config). */
	private static final String CONFIG_GROUP = "bettermonsterexamine";
	private static final String HISTORY_KEY = "historyData";
	private static final String HINT = "Search a monster, or right-click one in game → Stats.";

	private final MonsterDataService data;
	private final BetterMonsterExamineConfig config;
	private final ConfigManager configManager;
	private final Gson gson;
	private final DropPageService drops;

	private final IconTextField searchField = new IconTextField();
	private final JPanel resultsPanel = new JPanel();
	/** Shared monster-identity header (name, variant selector, links), above the tab strip. */
	private final MonsterHeader header;
	/** The Stats tab body: the wiki-style stat blocks. */
	private final MonsterCard card;
	/** The Drops tab body: the current monster's drop list. */
	private final DropsCard dropsCard;
	/** header + Stats|Drops tab strip + swappable body; shown once a monster is selected. */
	private final JPanel cardArea = new JPanel();
	/** The body region the tab strip swaps between {@link #card} and {@link #dropsCard}. */
	private final JPanel tabDisplay = new JPanel(new BorderLayout());
	/** The Stats | Drops tab strip and its two tabs, so a menu action can open straight to one. */
	private MaterialTabGroup contentTabs;
	private MaterialTab statsTab;
	private MaterialTab dropsTab;
	/** Empty-state hint, shown in place of {@link #cardArea} when nothing is selected. */
	private final JPanel hintPanel = new JPanel();
	private final JLabel hintLabel = new JLabel();
	/** True while the Drops tab is the active one, so async drop loads know to re-render it. */
	private boolean dropsTabActive;
	/** Recent/Favorites list view, shown in place of the card area while a mode is active. */
	private final JPanel listPanel = new JPanel();

	/** Which view the panel is showing: normal search/card, or one of the two list modes. */
	private enum Mode
	{
		NORMAL, RECENT, FAVORITES
	}

	private final LookupHistory history;
	private Mode mode = Mode.NORMAL;
	private final JButton recentTabButton;
	private final JButton favoritesTabButton;

	private List<MonsterData> currentVariants;
	private MonsterData currentSelection;

	/**
	 * Notified with the monster whenever a card renders, so the in-game overlay can mirror what
	 * the side panel is showing. The plugin sets this; left null when there's no overlay to feed.
	 */
	private Consumer<MonsterData> selectionListener;

	public BetterMonsterExaminePanel(MonsterIcons icons, MonsterDataService data, DropPageService drops, ItemIdService itemIds, DropsCard dropsCard, BetterMonsterExamineConfig config, ConfigManager configManager, Gson gson, IntSupplier playerCombatLevel, IntSupplier playerHpLevel, IntSupplier playerSlayerLevel, BufferedImage titleIcon)
	{
		super(false);
		this.data = data;
		this.drops = drops;
		this.dropsCard = dropsCard;
		this.config = config;
		this.configManager = configManager;
		this.gson = gson;
		this.history = LookupHistory.fromJson(gson, configManager.getConfiguration(CONFIG_GROUP, HISTORY_KEY));
		this.header = new MonsterHeader(config, playerCombatLevel,
			m -> history.isFavorite(m.getName(), m.getVersion()),
			this::toggleFavorite,
			this::selectVariant);
		this.card = new MonsterCard(icons, config, playerHpLevel, playerSlayerLevel);

		// Re-render the Drops tab when a page's drops — or the bulk item-id map that supplies its
		// icons/prices — land in the background (background thread → EDT).
		drops.setUpdateListener(page -> SwingUtilities.invokeLater(() -> onDropsLoaded(page)));
		itemIds.setUpdateListener(() -> SwingUtilities.invokeLater(this::onItemIdsLoaded));

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

		// Single scroll holding results + the card area, plus the list view (one shown at a time)
		resultsPanel.setLayout(new BoxLayout(resultsPanel, BoxLayout.Y_AXIS));
		resultsPanel.setAlignmentX(LEFT_ALIGNMENT);
		listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
		listPanel.setAlignmentX(LEFT_ALIGNMENT);
		listPanel.setVisible(false);

		buildCardArea();

		hintPanel.setLayout(new BoxLayout(hintPanel, BoxLayout.Y_AXIS));
		hintPanel.setAlignmentX(LEFT_ALIGNMENT);
		hintLabel.setFont(FontManager.getRunescapeSmallFont());
		hintLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		hintLabel.setAlignmentX(LEFT_ALIGNMENT);
		hintPanel.add(hintLabel);
		hintPanel.setVisible(false);

		JPanel scrollContent = new WidthTrackingPanel();
		scrollContent.setLayout(new BoxLayout(scrollContent, BoxLayout.Y_AXIS));
		scrollContent.add(resultsPanel);
		scrollContent.add(Box.createRigidArea(new Dimension(0, 6)));
		scrollContent.add(cardArea);
		scrollContent.add(hintPanel);
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
		else
		{
			showSearchResults();
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
			updateTabButtons();
		}

		currentSelection = data.variant(name, preferredVersion);
		recordSelection(currentSelection);
		renderCard();
	}

	/** The variant dropdown picked a form: switch to it, record it, and re-render. */
	private void selectVariant(MonsterData m)
	{
		currentSelection = m;
		recordSelection(m);
		renderCard();
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
		MonsterData m = currentSelection;
		if (m == null)
		{
			return;
		}
		header.show(m, currentVariants);
		card.show(m);
		// Warm this monster's drops so the Drops tab is ready; render now if it's the active tab.
		drops.request(m.getName());
		if (dropsTabActive)
		{
			renderDrops();
		}
		showCardArea();
		revalidate();
		repaint();

		// Let the overlay mirror the panel's current monster.
		if (selectionListener != null)
		{
			selectionListener.accept(m);
		}
	}

	// --------------------------------------------------------------------- drops

	/** Build the card area: shared header + the Stats|Drops tab strip over the swappable body. */
	private void buildCardArea()
	{
		cardArea.setLayout(new BoxLayout(cardArea, BoxLayout.Y_AXIS));
		cardArea.setAlignmentX(LEFT_ALIGNMENT);
		cardArea.setVisible(false);
		tabDisplay.setAlignmentX(LEFT_ALIGNMENT);

		contentTabs = new MaterialTabGroup(tabDisplay);
		contentTabs.setAlignmentX(LEFT_ALIGNMENT);
		statsTab = new MaterialTab("Stats", contentTabs, card);
		dropsTab = new MaterialTab("Drops", contentTabs, dropsCard);
		statsTab.setOnSelectEvent(() ->
		{
			dropsTabActive = false;
			header.setVariantSelectorVisible(true);
			return true;
		});
		dropsTab.setOnSelectEvent(() ->
		{
			dropsTabActive = true;
			header.setVariantSelectorVisible(false);
			renderDrops();
			return true;
		});
		contentTabs.addTab(statsTab);
		contentTabs.addTab(dropsTab);

		cardArea.add(header);
		cardArea.add(Box.createRigidArea(new Dimension(0, 6)));
		cardArea.add(contentTabs);
		cardArea.add(Box.createRigidArea(new Dimension(0, 6)));
		cardArea.add(tabDisplay);

		contentTabs.select(statsTab);
	}

	/**
	 * Open a monster's card to a specific tab — the entry point for the in-game right-click Stats /
	 * Drops menu options. Searches + selects the monster, then switches to the requested tab.
	 */
	public void openMonster(String name, String version, boolean drops)
	{
		search(name, true, version);
		if (currentSelection != null)
		{
			contentTabs.select(drops ? dropsTab : statsTab);
		}
	}

	/** Render the Drops tab for the current selection — one block per variant, or a loading hint. */
	private void renderDrops()
	{
		MonsterData m = currentSelection;
		if (m == null)
		{
			dropsCard.showMessage("Select a monster to see its drops.");
			return;
		}
		// The design renders every variant's table (split inside drop_json), so no stats-side join:
		// just request the page and show all its variant tables. A null table means "still loading".
		drops.request(m.getName());
		dropsCard.show(drops.tableFor(m.getName()));
	}

	/** A page's drops landed async: re-render the Drops tab if it's showing this monster. */
	private void onDropsLoaded(String page)
	{
		MonsterData m = currentSelection;
		if (dropsTabActive && m != null && m.getName().equalsIgnoreCase(page))
		{
			renderDrops();
		}
	}

	/** The bulk item-id map landed: re-render the Drops tab so icons and prices resolve. */
	private void onItemIdsLoaded()
	{
		if (dropsTabActive && currentSelection != null)
		{
			renderDrops();
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
			updateTabButtons();
		}
		// Don't trail the previously-viewed card beneath the live results — a fresh query is a
		// clean slate until the user picks a result.
		header.clear();
		card.clear();
		dropsCard.clear();
		currentSelection = null;
		search(text, false, null);
	}

	/** The view for an empty search field: Recent by default, or the plain hint when history is off. */
	private void showDefaultView()
	{
		resultsPanel.removeAll();
		if (config.enableHistory())
		{
			enterMode(Mode.RECENT);
			return;
		}
		mode = Mode.NORMAL;
		// Keep whatever card is showing; only fall back to the hint when there's nothing at all.
		ensureNormalContent();
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
		showList();
		renderList();
		updateTabButtons();
		revalidate();
		repaint();
	}

	private void exitListMode()
	{
		mode = Mode.NORMAL;
		ensureNormalContent();
		updateTabButtons();
		revalidate();
		repaint();
	}

	// One of four sibling regions shows at a time: the list, live results, the card area, or the hint.

	private void showList()
	{
		listPanel.setVisible(true);
		resultsPanel.setVisible(false);
		cardArea.setVisible(false);
		hintPanel.setVisible(false);
	}

	private void showSearchResults()
	{
		listPanel.setVisible(false);
		resultsPanel.setVisible(true);
		cardArea.setVisible(false);
		hintPanel.setVisible(false);
	}

	private void showCardArea()
	{
		listPanel.setVisible(false);
		resultsPanel.setVisible(false);
		cardArea.setVisible(true);
		hintPanel.setVisible(false);
	}

	private void showHint(String msg)
	{
		hintLabel.setText("<html><body style='width:180px'>" + msg + "</body></html>");
		listPanel.setVisible(false);
		resultsPanel.setVisible(false);
		cardArea.setVisible(false);
		hintPanel.setVisible(true);
	}

	/** In normal mode, show exactly one body: live results while searching, the card, else the hint. */
	private void ensureNormalContent()
	{
		String text = searchField.getText();
		boolean searching = text != null && !text.trim().isEmpty();
		if (searching)
		{
			showSearchResults();
		}
		else if (currentSelection != null)
		{
			showCardArea();
		}
		else
		{
			showHint(HINT);
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

	// ---------------------------------------------------- history bookkeeping

	/** Toggle the favourite state of the card's monster (card callback), persisting + refreshing. */
	private void toggleFavorite(MonsterData m)
	{
		history.toggleFavorite(m.getName(), m.getVersion());
		persist();
		renderCard();
		if (mode == Mode.FAVORITES)
		{
			renderList();
		}
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
		recordSelection(data.variant(name, version));
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
		configManager.setConfiguration(CONFIG_GROUP, HISTORY_KEY, history.toJson(gson));
	}

	/** React to the enableHistory config toggle: hide/show the buttons and any open list view. */
	public void onHistoryConfigChanged()
	{
		if (!config.enableHistory() && mode != Mode.NORMAL)
		{
			mode = Mode.NORMAL;
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

	private void capHeight(JComponent c)
	{
		c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
		c.setAlignmentX(LEFT_ALIGNMENT);
	}
}
