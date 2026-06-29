package com.bettermonsterexamine;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.ComponentConstants;

/**
 * A compact, tabbed in-game overlay modelled on the Monster Examine spell's interface. It
 * draws the monster's stats split across four clickable tabs — Combat, Aggressive, Defensive,
 * Info — directly with {@link Graphics2D} so it reads as a native overlay rather than a blitted
 * panel snapshot. The plugin pushes the selected monster in via {@link #setMonster}; the wiki
 * fields land later via {@link #setWiki} and simply appear on the next frame.
 *
 * <p>Tab clicks are routed here from the plugin's mouse listener: {@link #tabAt} hit-tests a
 * canvas point against the tab strip (using the bounds the overlay renderer maintains) and
 * {@link #setActiveTab} switches the visible tab.
 */
class MonsterCardOverlay extends Overlay
{
	private static final int WIDTH = 184;
	private static final int PAD = 6;
	/** On-screen size each stat-row icon is scaled to (preserving aspect). */
	private static final int ICON_SIZE = 16;
	/** Minimum gap between an inline label and a right-aligned wrapping value. */
	private static final int LABEL_GAP = 8;
	private static final String[] TAB_LABELS = {"Combat", "Aggro", "Def", "Info"};

	private final BetterMonsterExamineConfig config;
	private final MonsterIcons icons;
	private final IntSupplier playerCombatLevel;
	private final IntSupplier playerHpLevel;

	// monster/wiki are swapped in from the client thread or an OkHttp callback and read on the
	// render thread; activeTab is written from the mouse (AWT) thread. Volatile is enough — each
	// is an independent reference/int, no compound invariant.
	private volatile MonsterData monster;
	private volatile WikiInfo wiki;
	private volatile int activeTab;
	// The tab rectangles and close-button rectangle from the last render, relative to the overlay
	// origin, for hit-testing mouse clicks.
	private volatile Rectangle[] tabBounds;
	private volatile Rectangle closeBounds;

	MonsterCardOverlay(BetterMonsterExamineConfig config, MonsterIcons icons, IntSupplier playerCombatLevel, IntSupplier playerHpLevel)
	{
		this.config = config;
		this.icons = icons;
		this.playerCombatLevel = playerCombatLevel;
		this.playerHpLevel = playerHpLevel;
		setPosition(OverlayPosition.TOP_LEFT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	/** Show {@code m}, with {@code wi} (possibly null until the wiki fetch lands), from the first tab. */
	void setMonster(MonsterData m, WikiInfo wi)
	{
		this.monster = m;
		this.wiki = wi;
		this.activeTab = 0;
	}

	/** Patch in the wiki fields once they arrive, keeping the current tab. */
	void setWiki(WikiInfo wi)
	{
		this.wiki = wi;
	}

	/** Hide the overlay. */
	void clear()
	{
		this.monster = null;
	}

	boolean isShowing()
	{
		return monster != null;
	}

	void setActiveTab(int tab)
	{
		if (monster != null && tab >= 0 && tab < TAB_LABELS.length)
		{
			this.activeTab = tab;
		}
	}

	/** Whether a canvas point is over the close button. */
	boolean closeAt(int canvasX, int canvasY)
	{
		Rectangle bounds = getBounds();
		Rectangle close = closeBounds;
		if (monster == null || bounds == null || close == null)
		{
			return false;
		}
		return close.contains(canvasX - bounds.x, canvasY - bounds.y);
	}

	/** The tab index at a canvas point, or -1 if it isn't over the tab strip. */
	int tabAt(int canvasX, int canvasY)
	{
		Rectangle bounds = getBounds();
		Rectangle[] tabs = tabBounds;
		if (monster == null || bounds == null || tabs == null)
		{
			return -1;
		}
		int lx = canvasX - bounds.x;
		int ly = canvasY - bounds.y;
		for (int i = 0; i < tabs.length; i++)
		{
			if (tabs[i] != null && tabs[i].contains(lx, ly))
			{
				return i;
			}
		}
		return -1;
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		MonsterData m = monster;
		if (m == null)
		{
			return null;
		}

		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setFont(FontManager.getRunescapeSmallFont());
		FontMetrics fm = g.getFontMetrics();
		int lineH = fm.getHeight();
		int titleH = lineH + 2;
		int tabH = lineH + 4;
		int contentW = WIDTH - 2 * PAD;

		List<Row> rows = rowsFor(activeTab, m);

		int contentH = 0;
		for (Row r : rows)
		{
			contentH += r.height(fm, contentW, lineH);
		}

		int total = PAD + titleH + tabH + 1 + 3 + contentH + PAD;

		// Background + subtle border.
		g.setColor(ComponentConstants.STANDARD_BACKGROUND_COLOR);
		g.fillRect(0, 0, WIDTH, total);
		g.setColor(new Color(0, 0, 0, 120));
		g.drawRect(0, 0, WIDTH - 1, total - 1);

		int y = PAD;
		drawTitle(g, fm, m, y);
		y += titleH;
		drawTabs(g, fm, y, tabH);
		y += tabH + 1 + 3;

		for (Row r : rows)
		{
			y += r.draw(g, fm, PAD, y, contentW, lineH);
		}

		return new Dimension(WIDTH, total);
	}

	private void drawTitle(Graphics2D g, FontMetrics fm, MonsterData m, int y)
	{
		int baseline = y + fm.getAscent();

		// Close (✕) glyph at the far right, drawn as two strokes so it needs no font glyph.
		int cs = fm.getAscent();
		int cx = WIDTH - PAD - cs;
		int cy = y + (fm.getHeight() - cs) / 2;
		g.setColor(new Color(0xFF6060));
		g.drawLine(cx, cy, cx + cs - 1, cy + cs - 1);
		g.drawLine(cx, cy + cs - 1, cx + cs - 1, cy);
		closeBounds = new Rectangle(cx - 3, cy - 3, cs + 6, cs + 6);

		int rightEdge = cx - 6;
		String lvl = m.getLevel() > 0 ? "(lvl-" + m.getLevel() + ")" : "";
		if (!lvl.isEmpty())
		{
			int lvlW = fm.stringWidth(lvl);
			g.setColor(StatColors.levelColor(config.statHighlighting(), playerCombatLevel.getAsInt(), m.getLevel()));
			g.drawString(lvl, rightEdge - lvlW, baseline);
			rightEdge -= lvlW + 6;
		}

		g.setColor(Color.WHITE);
		g.drawString(truncate(fm, m.getName(), rightEdge - PAD), PAD, baseline);
	}

	private void drawTabs(Graphics2D g, FontMetrics fm, int y, int tabH)
	{
		int tabW = WIDTH / TAB_LABELS.length;
		Rectangle[] tabs = new Rectangle[TAB_LABELS.length];
		int active = activeTab;
		for (int i = 0; i < TAB_LABELS.length; i++)
		{
			int tx = i * tabW;
			int tw = i == TAB_LABELS.length - 1 ? WIDTH - tx : tabW;
			tabs[i] = new Rectangle(tx, y, tw, tabH);

			if (i == active)
			{
				g.setColor(ColorScheme.BRAND_ORANGE);
				g.fillRect(tx, y, tw, tabH);
			}
			String label = TAB_LABELS[i];
			int lx = tx + (tw - fm.stringWidth(label)) / 2;
			int baseline = y + (tabH + fm.getAscent() - fm.getDescent()) / 2;
			g.setColor(i == active ? Color.BLACK : ColorScheme.LIGHT_GRAY_COLOR);
			g.drawString(label, lx, baseline);
		}
		tabBounds = tabs;
		g.setColor(ColorScheme.BRAND_ORANGE);
		g.fillRect(0, y + tabH, WIDTH, 1);
	}

	// ------------------------------------------------------------------ tab content

	private List<Row> rowsFor(int tab, MonsterData m)
	{
		HighlightMode mode = config.statHighlighting();
		MonsterStats stats = new MonsterStats(m, wiki, mode, playerHpLevel.getAsInt());
		List<Row> rows = new ArrayList<>();

		switch (tab)
		{
			case 0:
				combatTab(rows, stats, mode);
				break;
			case 1:
				aggressiveTab(rows, stats, mode);
				break;
			case 2:
				defensiveTab(rows, stats);
				break;
			default:
				infoTab(rows, stats, mode);
				break;
		}

		if (rows.isEmpty())
		{
			rows.add(Row.plain("No data.", ColorScheme.LIGHT_GRAY_COLOR));
		}
		return rows;
	}

	private void combatTab(List<Row> rows, MonsterStats stats, HighlightMode mode)
	{
		Color white = Color.WHITE;
		// The combat level is already shown in the title row, so it's not repeated here.
		List<String> levels = stats.combatLevels();
		if (!levels.isEmpty())
		{
			rows.add(Row.stat(icons.hitpointsIcon, "Hitpoints", levels.get(0), white));
			rows.add(Row.stat(icons.attackIcon, "Attack", levels.get(1), white));
			rows.add(Row.stat(icons.strengthIcon, "Strength", levels.get(2), white));
			rows.add(Row.stat(icons.defenceIcon, "Defence", levels.get(3), white));
			rows.add(Row.stat(icons.magicIcon, "Magic", levels.get(4), white));
			rows.add(Row.stat(icons.rangedIcon, "Ranged", levels.get(5), white));
		}
		rows.add(Row.kv("Speed", stats.attackSpeed(), white));
		rows.add(Row.kvWrap("Style", stats.attackStyle(), white));

		// Max hits: the view-model splits the wiki list (or dataset fallback) and flags any line
		// over the player's HP; in colour-blind mode it also gets a ⚠ glyph (parity with the panel).
		boolean cb = mode == HighlightMode.COLOUR_BLIND;
		boolean firstMax = true;
		for (MonsterStats.MaxHitLine line : stats.maxHits())
		{
			Color c = StatColors.resolve(line.overHp() ? ColourRole.DANGER : ColourRole.NEUTRAL, mode);
			String text = line.text() + (line.overHp() && cb ? " ⚠" : "");
			// Only the first row is labelled "Max hit"; the rest just list their values.
			rows.add(Row.kv(firstMax ? "Max hit" : "", text, c));
			firstMax = false;
		}
	}

	private void aggressiveTab(List<Row> rows, MonsterStats stats, HighlightMode mode)
	{
		Color white = Color.WHITE;
		if (stats.wikiLoaded())
		{
			MonsterStats.StatField pois = stats.poisonous();
			if (pois != null)
			{
				rows.add(Row.kv("Poisonous", pois.value(), StatColors.resolve(pois.role(), mode)));
			}
		}
		else
		{
			rows.add(Row.plain("loading wiki data…", ColorScheme.LIGHT_GRAY_COLOR));
		}

		List<String> off = stats.offensiveBonuses();
		if (!off.isEmpty())
		{
			rows.add(Row.stat(icons.attackIcon, "Attack", off.get(0), white));
			rows.add(Row.stat(icons.strengthIcon, "Strength", off.get(1), white));
			rows.add(Row.stat(icons.magicIcon, "Magic", off.get(2), white));
			rows.add(Row.stat(icons.magicDamageIcon, "Magic dmg", off.get(3), white));
			rows.add(Row.stat(icons.rangedIcon, "Ranged", off.get(4), white));
			rows.add(Row.stat(icons.rangedStrengthIcon, "Ranged str", off.get(5), white));
		}
	}

	private void defensiveTab(List<Row> rows, MonsterStats stats)
	{
		Color white = Color.WHITE;
		if (stats.hasDefensive())
		{
			// Grouped like the wiki: melee, then magic defence + elemental weakness, then ranged.
			List<String> melee = stats.meleeDefence();
			rows.add(Row.stat(icons.stabIcon, "Stab", melee.get(0), white));
			rows.add(Row.stat(icons.slashIcon, "Slash", melee.get(1), white));
			rows.add(Row.stat(icons.crushIcon, "Crush", melee.get(2), white));

			rows.add(Row.stat(icons.magicDefenceIcon, "Magic", stats.magicDefence(), white));
			addWeakness(rows, stats, white);

			List<String> ranged = stats.rangedDefence();
			rows.add(Row.stat(icons.lightIcon, "Light", ranged.get(0), white));
			rows.add(Row.stat(icons.standardIcon, "Standard", ranged.get(1), white));
			rows.add(Row.stat(icons.heavyIcon, "Heavy", ranged.get(2), white));
		}
		else
		{
			addWeakness(rows, stats, white);
		}
	}

	private void addWeakness(List<Row> rows, MonsterStats stats, Color white)
	{
		String element = stats.weaknessElement();
		if (element != null)
		{
			rows.add(Row.stat(icons.getElementalWeaknessIcon(element),
				StatFormat.cap(element), stats.weaknessSeverity(), white));
		}
		else
		{
			rows.add(Row.kv("Weakness", "—", white));
		}
	}

	private void infoTab(List<Row> rows, MonsterStats stats, HighlightMode mode)
	{
		Color white = Color.WHITE;
		String sizeAttr = stats.sizeAttr();
		if (sizeAttr != null)
		{
			rows.add(Row.plain(sizeAttr, white));
		}
		if (stats.slayerMonster())
		{
			rows.add(Row.kv("Slayer", "Yes", white));
		}
		MonsterStats.StatField aggr = stats.aggressive();
		if (aggr != null)
		{
			rows.add(Row.kv("Aggressive", aggr.value(), StatColors.resolve(aggr.role(), mode)));
		}
		// Flat armour: a flat damage adjustment, 0 for most monsters — shown only when non-zero,
		// green when negative (takes extra damage), red when positive (shrugs damage off).
		MonsterStats.StatField flatArmour = stats.flatArmour();
		if (flatArmour != null)
		{
			rows.add(Row.kv("Flat armour", flatArmour.value(), StatColors.resolve(flatArmour.role(), mode)));
		}
		MonsterStats.StatField xp = stats.xpBonus();
		if (xp != null)
		{
			rows.add(Row.kv("XP bonus", xp.value(), StatColors.resolve(xp.role(), mode)));
		}

		String burn = stats.burn();
		if (burn != null)
		{
			rows.add(Row.kv("Burn", burn, white));
		}
		if (stats.wikiLoaded())
		{
			MonsterStats.StatField poison = stats.poison();
			if (poison != null)
			{
				rows.add(Row.kv("Poison", poison.value(), StatColors.resolve(poison.role(), mode)));
			}
			MonsterStats.StatField venom = stats.venom();
			if (venom != null)
			{
				rows.add(Row.kv("Venom", venom.value(), StatColors.resolve(venom.role(), mode)));
			}
			MonsterStats.StatField cannon = stats.cannon();
			if (cannon != null)
			{
				rows.add(Row.kv("Cannon", cannon.value(), StatColors.resolve(cannon.role(), mode)));
			}
			MonsterStats.StatField thrall = stats.thrall();
			if (thrall != null)
			{
				rows.add(Row.kv("Thrall", thrall.value(), StatColors.resolve(thrall.role(), mode)));
			}
		}
		else
		{
			rows.add(Row.plain("loading wiki data…", ColorScheme.LIGHT_GRAY_COLOR));
		}
	}

	private static String truncate(FontMetrics fm, String s, int maxW)
	{
		if (fm.stringWidth(s) <= maxW)
		{
			return s;
		}
		String ellipsis = "…";
		int ellW = fm.stringWidth(ellipsis);
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < s.length(); i++)
		{
			if (fm.stringWidth(sb.toString() + s.charAt(i)) + ellW > maxW)
			{
				break;
			}
			sb.append(s.charAt(i));
		}
		return sb.append(ellipsis).toString();
	}

	private static List<String> wrap(String text, FontMetrics fm, int maxW)
	{
		return wrap(text, fm, maxW, maxW);
	}

	/** Word-wrap, allowing a narrower first line (e.g. to leave room for an inline label). */
	private static List<String> wrap(String text, FontMetrics fm, int firstW, int restW)
	{
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		int maxW = firstW;
		for (String word : text.split(" "))
		{
			String candidate = line.length() == 0 ? word : line + " " + word;
			if (fm.stringWidth(candidate) > maxW && line.length() > 0)
			{
				lines.add(line.toString());
				line = new StringBuilder(word);
				maxW = restW;
			}
			else
			{
				line = new StringBuilder(candidate);
			}
		}
		if (line.length() > 0)
		{
			lines.add(line.toString());
		}
		return lines;
	}

	/**
	 * One line of tab content. A {@code kv} row is "label … value" on a single line; a {@code stat}
	 * row swaps the text label for an {@code icon} on the left; a {@code kvWrap} row keeps the
	 * label inline but lets the value wrap across several right-aligned lines; a {@code plain} row
	 * is a label-less wrapping value (for long text like the attribute list).
	 */
	private static final class Row
	{
		private final String label;
		private final BufferedImage icon;
		private final String value;
		private final Color color;
		private final boolean wrap;
		/** When wrapping, right-align the value lines and keep the label inline (vs. its own line). */
		private final boolean rightWrap;

		private Row(String label, BufferedImage icon, String value, Color color, boolean wrap, boolean rightWrap)
		{
			this.label = label;
			this.icon = icon;
			this.value = value;
			this.color = color;
			this.wrap = wrap;
			this.rightWrap = rightWrap;
		}

		static Row kv(String label, String value, Color color)
		{
			return new Row(label, null, value, color, false, false);
		}

		static Row stat(BufferedImage icon, String label, String value, Color color)
		{
			return new Row(label, icon, value, color, false, false);
		}

		static Row kvWrap(String label, String value, Color color)
		{
			return new Row(label, null, value, color, true, true);
		}

		static Row plain(String value, Color color)
		{
			return new Row(null, null, value, color, true, false);
		}

		int height(FontMetrics fm, int contentW, int lineH)
		{
			if (icon != null)
			{
				return Math.max(lineH, ICON_SIZE);
			}
			if (!wrap)
			{
				return lineH;
			}
			if (rightWrap)
			{
				int firstW = contentW - (label != null ? fm.stringWidth(label) + LABEL_GAP : 0);
				return wrap(value, fm, firstW, contentW).size() * lineH;
			}
			int lines = label != null ? 1 : 0;
			lines += wrap(value, fm, contentW).size();
			return lines * lineH;
		}

		int draw(Graphics2D g, FontMetrics fm, int x, int y, int contentW, int lineH)
		{
			if (icon != null)
			{
				int rowH = Math.max(lineH, ICON_SIZE);
				int baseline = y + (rowH + fm.getAscent() - fm.getDescent()) / 2;
				drawIcon(g, icon, x, y, rowH);
				if (label != null)
				{
					g.setColor(ColorScheme.LIGHT_GRAY_COLOR);
					g.drawString(label, x + ICON_SIZE + 4, baseline);
				}
				g.setColor(color);
				g.drawString(value, x + contentW - fm.stringWidth(value), baseline);
				return rowH;
			}

			if (!wrap)
			{
				g.setColor(ColorScheme.LIGHT_GRAY_COLOR);
				g.drawString(label, x, y + fm.getAscent());
				g.setColor(color);
				g.drawString(value, x + contentW - fm.stringWidth(value), y + fm.getAscent());
				return lineH;
			}

			if (rightWrap)
			{
				// Label stays on the first line at the left; the value wraps across right-aligned
				// lines (the first leaving room for the label), keeping a multi-style list tight.
				int firstW = contentW - (label != null ? fm.stringWidth(label) + LABEL_GAP : 0);
				if (label != null)
				{
					g.setColor(ColorScheme.LIGHT_GRAY_COLOR);
					g.drawString(label, x, y + fm.getAscent());
				}
				g.setColor(color);
				int yy = y;
				for (String ln : wrap(value, fm, firstW, contentW))
				{
					g.drawString(ln, x + contentW - fm.stringWidth(ln), yy + fm.getAscent());
					yy += lineH;
				}
				return Math.max(lineH, yy - y);
			}

			int yy = y;
			if (label != null)
			{
				g.setColor(ColorScheme.LIGHT_GRAY_COLOR);
				g.drawString(label, x, yy + fm.getAscent());
				yy += lineH;
			}
			g.setColor(color);
			for (String ln : wrap(value, fm, contentW))
			{
				g.drawString(ln, x, yy + fm.getAscent());
				yy += lineH;
			}
			return yy - y;
		}

		/** Draw {@code img} scaled to fit {@link #ICON_SIZE} (preserving aspect), vertically centred. */
		private static void drawIcon(Graphics2D g, BufferedImage img, int x, int y, int rowH)
		{
			double scale = Math.min((double) ICON_SIZE / img.getWidth(), (double) ICON_SIZE / img.getHeight());
			int w = Math.max(1, (int) Math.round(img.getWidth() * scale));
			int h = Math.max(1, (int) Math.round(img.getHeight() * scale));
			g.drawImage(img, x, y + (rowH - h) / 2, w, h, null);
		}
	}
}
