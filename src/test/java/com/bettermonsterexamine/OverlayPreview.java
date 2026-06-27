package com.bettermonsterexamine;

import com.google.gson.Gson;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import okhttp3.OkHttpClient;

/**
 * Dev-only: renders the in-game {@link MonsterCardOverlay} to PNGs without launching the
 * client, so we can screenshot states that aren't reachable at once in-game. It draws the
 * overlay exactly as the game would (same {@code render(Graphics2D)} path) onto a dark
 * backdrop, then tiles several captioned states side by side:
 *
 * <ul>
 *   <li>{@code overlay_vorkath_tabs.png} — every Vorkath tab (Combat / Aggro / Def / Info)
 *       at once.</li>
 *   <li>{@code overlay_blue_moon_modes.png} — Blue Moon's Info tab in Standard, Colour-blind,
 *       and highlighting-off modes.</li>
 * </ul>
 */
public class OverlayPreview
{
	/** Mirrors MonsterCardOverlay.WIDTH (package-private). */
	private static final int OVERLAY_WIDTH = 184;
	private static final int MARGIN = 18;
	private static final int GAP = 18;
	private static final int CAPTION_H = 26;
	private static final Color BACKDROP = new Color(0x2E2E2E);

	public static void main(String[] args) throws Exception
	{
		System.setProperty("java.awt.headless", "false");
		ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
		OkHttpClient httpClient = new OkHttpClient();
		MonsterDataService ds = new MonsterDataService(new Gson(), httpClient, exec);
		// dataset loads on a background thread (cache, else a network fetch on first run)
		for (int i = 0; i < 600 && ds.searchNames("a", 1).isEmpty(); i++)
		{
			Thread.sleep(50);
		}
		WikiInfoboxService wiki = new WikiInfoboxService(httpClient);

		File outDir = new File("previews");
		outDir.mkdirs();

		MonsterIcons icons = new MonsterIcons();

		// --- Vorkath: every tab at once -------------------------------------------------
		MonsterData vorkath = firstVariant(ds, "Vorkath");
		WikiInfo vorkathWiki = preloadWiki(wiki, "Vorkath");
		BetterMonsterExamineConfig standard = config(HighlightMode.STANDARD);
		String[] tabCaptions = {"Combat", "Aggressive", "Defensive", "Info"};
		BufferedImage[] vorkathTabs = new BufferedImage[4];
		for (int tab = 0; tab < 4; tab++)
		{
			vorkathTabs[tab] = renderOverlay(standard, icons, vorkath, vorkathWiki, tab);
		}
		writeStrip(new File(outDir, "overlay_vorkath_tabs.png"), vorkathTabs, tabCaptions);

		// --- Blue Moon: Info tab across the three highlight modes ------------------------
		MonsterData blueMoon = firstVariant(ds, "Blue Moon");
		WikiInfo blueMoonWiki = preloadWiki(wiki, "Blue Moon");
		HighlightMode[] modes = {HighlightMode.STANDARD, HighlightMode.COLOUR_BLIND, HighlightMode.OFF};
		String[] modeCaptions = {"Standard", "Colour-blind", "No highlighting"};
		BufferedImage[] modeImgs = new BufferedImage[modes.length];
		for (int i = 0; i < modes.length; i++)
		{
			modeImgs[i] = renderOverlay(config(modes[i]), icons, blueMoon, blueMoonWiki, 3);
		}
		writeStrip(new File(outDir, "overlay_blue_moon_modes.png"), modeImgs, modeCaptions);

		System.out.println("OVERLAY PREVIEWS WRITTEN TO " + outDir.getAbsolutePath());
		System.exit(0); // OkHttp's dispatcher keeps non-daemon threads alive
	}

	/** Render one overlay state (monster + tab + highlight mode) to a tightly-cropped image. */
	private static BufferedImage renderOverlay(BetterMonsterExamineConfig cfg, MonsterIcons icons,
		MonsterData monster, WikiInfo wiki, int tab) throws Exception
	{
		// Maxed player (126 combat, 99 hp) so level colouring and the over-HP max-hit cue render.
		MonsterCardOverlay overlay = new MonsterCardOverlay(cfg, icons, () -> 126, () -> 99);
		overlay.setMonster(monster, wiki);
		overlay.setActiveTab(tab);

		// First pass: measure the height the overlay wants for this tab.
		BufferedImage scratch = new BufferedImage(OVERLAY_WIDTH, 2000, BufferedImage.TYPE_INT_ARGB);
		Graphics2D mg = scratch.createGraphics();
		java.awt.Dimension dim = overlay.render(mg);
		mg.dispose();
		int h = dim != null ? dim.height : 200;

		BufferedImage img = new BufferedImage(OVERLAY_WIDTH, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		overlay.render(g);
		g.dispose();
		return img;
	}

	/** Lay images out in a captioned horizontal strip on a dark backdrop, top-aligned. */
	private static void writeStrip(File out, BufferedImage[] imgs, String[] captions) throws Exception
	{
		int tallest = 0;
		int totalW = MARGIN;
		for (BufferedImage img : imgs)
		{
			tallest = Math.max(tallest, img.getHeight());
			totalW += img.getWidth() + GAP;
		}
		totalW = totalW - GAP + MARGIN;
		int totalH = MARGIN + CAPTION_H + tallest + MARGIN;

		BufferedImage canvas = new BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = canvas.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setColor(BACKDROP);
		g.fillRect(0, 0, totalW, totalH);

		Font captionFont = new Font(Font.SANS_SERIF, Font.BOLD, 13);
		g.setFont(captionFont);

		int x = MARGIN;
		for (int i = 0; i < imgs.length; i++)
		{
			g.setColor(Color.WHITE);
			int cw = g.getFontMetrics().stringWidth(captions[i]);
			int cx = x + (imgs[i].getWidth() - cw) / 2;
			g.drawString(captions[i], cx, MARGIN + 16);
			g.drawImage(imgs[i], x, MARGIN + CAPTION_H, null);
			x += imgs[i].getWidth() + GAP;
		}
		g.dispose();
		ImageIO.write(canvas, "png", out);
		System.out.println("wrote " + out.getName() + " (" + totalW + "x" + totalH + ")");
	}

	private static WikiInfo preloadWiki(WikiInfoboxService wiki, String name) throws Exception
	{
		CountDownLatch latch = new CountDownLatch(1);
		wiki.fetch(name, latch::countDown);
		latch.await(15, TimeUnit.SECONDS);
		return wiki.getCached(name);
	}

	private static MonsterData firstVariant(MonsterDataService ds, String name)
	{
		List<MonsterData> variants = ds.variantsForName(name);
		if (variants.isEmpty())
		{
			throw new IllegalStateException("No dataset variant for " + name);
		}
		return variants.get(0);
	}

	private static BetterMonsterExamineConfig config(HighlightMode mode)
	{
		return new BetterMonsterExamineConfig()
		{
			@Override
			public HighlightMode statHighlighting()
			{
				return mode;
			}
		};
	}
}
