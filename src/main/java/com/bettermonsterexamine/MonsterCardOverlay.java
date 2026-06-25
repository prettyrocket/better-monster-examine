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
import java.util.Locale;
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
		String ver = m.getVersion();
		WikiInfo wi = wiki;
		List<Row> rows = new ArrayList<>();

		switch (tab)
		{
			case 0:
				combatTab(rows, m, mode);
				break;
			case 1:
				aggressiveTab(rows, m, wi, ver, mode);
				break;
			case 2:
				defensiveTab(rows, m, mode);
				break;
			default:
				infoTab(rows, m, wi, ver, mode);
				break;
		}

		if (rows.isEmpty())
		{
			rows.add(Row.plain("No data.", ColorScheme.LIGHT_GRAY_COLOR));
		}
		return rows;
	}

	private void combatTab(List<Row> rows, MonsterData m, HighlightMode mode)
	{
		Color white = Color.WHITE;
		// The combat level is already shown in the title row, so it's not repeated here.
		MonsterData.Skills s = m.getSkills();
		if (s != null)
		{
			rows.add(Row.stat(icons.hitpointsIcon, "Hitpoints", BetterMonsterExaminePanel.num(s.getHp()), white));
			rows.add(Row.stat(icons.attackIcon, "Attack", BetterMonsterExaminePanel.num(s.getAtk()), white));
			rows.add(Row.stat(icons.strengthIcon, "Strength", BetterMonsterExaminePanel.num(s.getStr()), white));
			rows.add(Row.stat(icons.defenceIcon, "Defence", BetterMonsterExaminePanel.num(s.getDef()), white));
			rows.add(Row.stat(icons.magicIcon, "Magic", BetterMonsterExaminePanel.num(s.getMagic()), white));
			rows.add(Row.stat(icons.rangedIcon, "Ranged", BetterMonsterExaminePanel.num(s.getRanged()), white));
		}
		rows.add(Row.kv("Speed", BetterMonsterExaminePanel.attackSpeed(m), white));

		List<String> st = m.getStyle();
		rows.add(Row.labelled("Style", st == null || st.isEmpty() ? "—" : String.join(", ", st), white));

		// Max hits: the wiki carries the full per-style list; fall back to the dataset's value.
		WikiInfo wi = wiki;
		String wikiMax = wi != null ? wi.get("max hit", m.getVersion()) : null;
		String maxText = wikiMax != null ? wikiMax : (m.getMaxHit() == null || m.getMaxHit().isEmpty() ? "—" : m.getMaxHit());
		int hp = playerHpLevel.getAsInt();
		boolean flagOverHp = mode != HighlightMode.OFF && hp > 0;
		for (String line : maxText.split(",\\s*"))
		{
			boolean over = flagOverHp && BetterMonsterExaminePanel.maxValue(line) > hp;
			rows.add(Row.kv("Max hit", line, over ? StatColors.danger(mode) : white));
		}
	}

	private void aggressiveTab(List<Row> rows, MonsterData m, WikiInfo wi, String ver, HighlightMode mode)
	{
		Color white = Color.WHITE;
		if (wi != null)
		{
			String aggr = wi.get("aggressive", ver);
			if (aggr != null)
			{
				boolean yes = aggr.trim().toLowerCase(Locale.ROOT).startsWith("yes");
				rows.add(Row.kv("Aggressive", aggr.trim(), yes ? StatColors.danger(mode) : white));
			}
			String pois = wi.get("poisonous", ver);
			if (pois != null)
			{
				boolean yes = pois.trim().toLowerCase(Locale.ROOT).startsWith("yes");
				rows.add(Row.kv("Poisonous", pois.trim(), yes ? StatColors.danger(mode) : white));
			}
		}
		else
		{
			rows.add(Row.plain("loading wiki data…", ColorScheme.LIGHT_GRAY_COLOR));
		}

		MonsterData.Offensive o = m.getOffensive();
		if (o != null)
		{
			rows.add(Row.stat(icons.attackIcon, "Attack", BetterMonsterExaminePanel.bonus(o.getAtk()), white));
			rows.add(Row.stat(icons.strengthIcon, "Strength", BetterMonsterExaminePanel.bonus(o.getStr()), white));
			rows.add(Row.stat(icons.magicIcon, "Magic", BetterMonsterExaminePanel.bonus(o.getMagic()), white));
			rows.add(Row.stat(icons.magicDamageIcon, "Magic dmg", BetterMonsterExaminePanel.bonus(o.getMagicStr()), white));
			rows.add(Row.stat(icons.rangedIcon, "Ranged", BetterMonsterExaminePanel.bonus(o.getRanged()), white));
			rows.add(Row.stat(icons.rangedStrengthIcon, "Ranged str", BetterMonsterExaminePanel.bonus(o.getRangedStr()), white));
		}
	}

	private void defensiveTab(List<Row> rows, MonsterData m, HighlightMode mode)
	{
		Color white = Color.WHITE;
		MonsterData.Defensive d = m.getDefensive();
		if (d != null)
		{
			rows.add(Row.stat(icons.stabIcon, "Stab", BetterMonsterExaminePanel.bonus(d.getStab()), white));
			rows.add(Row.stat(icons.slashIcon, "Slash", BetterMonsterExaminePanel.bonus(d.getSlash()), white));
			rows.add(Row.stat(icons.crushIcon, "Crush", BetterMonsterExaminePanel.bonus(d.getCrush()), white));
			rows.add(Row.stat(icons.magicDefenceIcon, "Magic", BetterMonsterExaminePanel.bonus(d.getMagic()), white));
			rows.add(Row.stat(icons.lightIcon, "Light", BetterMonsterExaminePanel.bonus(d.getLight()), white));
			rows.add(Row.stat(icons.standardIcon, "Standard", BetterMonsterExaminePanel.bonus(d.getStandard()), white));
			rows.add(Row.stat(icons.heavyIcon, "Heavy", BetterMonsterExaminePanel.bonus(d.getHeavy()), white));
			if (d.getFlatArmour() != 0)
			{
				int fa = d.getFlatArmour();
				rows.add(Row.kv("Flat armour", String.valueOf(fa),
					fa < 0 ? StatColors.good(mode) : StatColors.danger(mode)));
			}
		}

		MonsterData.Weakness w = m.getWeakness();
		if (w != null && w.getElement() != null)
		{
			rows.add(Row.stat(icons.getElementalWeaknessIcon(w.getElement()),
				BetterMonsterExaminePanel.cap(w.getElement()), w.getSeverity() + "%", white));
		}
		else
		{
			rows.add(Row.kv("Weakness", "—", white));
		}
	}

	private void infoTab(List<Row> rows, MonsterData m, WikiInfo wi, String ver, HighlightMode mode)
	{
		Color white = Color.WHITE;
		String size = m.getSize() > 0 ? m.getSize() + "x" + m.getSize() : null;
		String attrs = m.getAttributes() != null && !m.getAttributes().isEmpty()
			? BetterMonsterExaminePanel.attributeNames(m.getAttributes()) : null;
		String sizeAttr = size != null && attrs != null ? size + ", " + attrs : (size != null ? size : attrs);
		if (sizeAttr != null)
		{
			rows.add(Row.plain(sizeAttr, white));
		}
		if (m.isSlayerMonster())
		{
			rows.add(Row.kv("Slayer", "Yes", white));
		}

		if (wi != null)
		{
			String xp = wi.get("xpbonus", ver);
			if (xp != null && !xp.trim().isEmpty() && !isZero(xp))
			{
				boolean penalty = xp.trim().startsWith("-");
				rows.add(Row.kv("XP bonus", (penalty ? "" : "+") + xp.trim() + "%",
					penalty ? StatColors.danger(mode) : StatColors.good(mode)));
			}
		}

		String burn = BetterMonsterExaminePanel.burnImmunity(m);
		if (burn != null)
		{
			rows.add(Row.kv("Burn", burn, white));
		}
		if (wi != null)
		{
			String poison = BetterMonsterExaminePanel.resistanceLabel(wi.get("poisonresistance", ver));
			if (poison != null)
			{
				rows.add(Row.kv("Poison", poison, StatColors.danger(mode)));
			}
			String venom = BetterMonsterExaminePanel.resistanceLabel(wi.get("venomresistance", ver));
			if (venom != null)
			{
				rows.add(Row.kv("Venom", venom, StatColors.danger(mode)));
			}
			if (BetterMonsterExaminePanel.yes(wi.get("immunecannon", ver)))
			{
				rows.add(Row.kv("Cannon", "Immune", StatColors.danger(mode)));
			}
			if (BetterMonsterExaminePanel.yes(wi.get("immunethrall", ver)))
			{
				rows.add(Row.kv("Thrall", "Immune", StatColors.danger(mode)));
			}
		}
		else
		{
			rows.add(Row.plain("loading wiki data…", ColorScheme.LIGHT_GRAY_COLOR));
		}
	}

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
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" "))
		{
			String candidate = line.length() == 0 ? word : line + " " + word;
			if (fm.stringWidth(candidate) > maxW && line.length() > 0)
			{
				lines.add(line.toString());
				line = new StringBuilder(word);
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
	 * row swaps the text label for an {@code icon} on the left; a labelled or plain row puts a
	 * (wrapping) value beneath an optional label, for long text.
	 */
	private static final class Row
	{
		private final String label;
		private final BufferedImage icon;
		private final String value;
		private final Color color;
		private final boolean wrap;

		private Row(String label, BufferedImage icon, String value, Color color, boolean wrap)
		{
			this.label = label;
			this.icon = icon;
			this.value = value;
			this.color = color;
			this.wrap = wrap;
		}

		static Row kv(String label, String value, Color color)
		{
			return new Row(label, null, value, color, false);
		}

		static Row stat(BufferedImage icon, String label, String value, Color color)
		{
			return new Row(label, icon, value, color, false);
		}

		static Row labelled(String label, String value, Color color)
		{
			return new Row(label, null, value, color, true);
		}

		static Row plain(String value, Color color)
		{
			return new Row(null, null, value, color, true);
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
