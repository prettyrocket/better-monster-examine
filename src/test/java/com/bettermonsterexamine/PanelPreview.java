package com.bettermonsterexamine;

import com.google.gson.Gson;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import net.runelite.client.util.ImageUtil;
import okhttp3.OkHttpClient;

/**
 * Dev-only: renders the wiki-style monster card to PNGs without launching the full
 * client. Preloads the wiki infobox first so the async fields show up in the capture.
 */
public class PanelPreview
{
	public static void main(String[] args) throws Exception
	{
		System.setProperty("java.awt.headless", "false");
		ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
		OkHttpClient httpClient = new OkHttpClient();
		MonsterDataService ds = new MonsterDataService(new Gson(), httpClient, exec);
		// dataset loads on a background thread (cache, else a network fetch on first run);
		// wait up to ~30s before rendering
		for (int i = 0; i < 600 && ds.searchNames("a", 1).isEmpty(); i++)
		{
			Thread.sleep(50);
		}
		WikiInfoboxService wiki = new WikiInfoboxService(httpClient);
		File outDir = new File(System.getProperty("java.io.tmpdir"));

		render(ds, wiki, "Vorkath", new File(outDir, "preview_WIKI.png"));
		render(ds, wiki, "King Black Dragon", new File(outDir, "preview_WIKI_burn.png"));

		System.out.println("PREVIEWS WRITTEN TO " + outDir.getAbsolutePath());
		System.exit(0); // OkHttp's dispatcher keeps non-daemon threads alive
	}

	private static void render(MonsterDataService ds, WikiInfoboxService wiki, String monster, File out) throws Exception
	{
		// Preload the wiki infobox so the screenshot includes aggressive/poisonous/etc.
		CountDownLatch latch = new CountDownLatch(1);
		wiki.fetch(monster, latch::countDown);
		latch.await(15, TimeUnit.SECONDS);

		BetterMonsterExamineConfig cfg = new BetterMonsterExamineConfig()
		{
		};
		BufferedImage icon = ImageUtil.loadImageResource(BetterMonsterExaminePanel.class, "/icon.png");
		MonsterIcons icons = new MonsterIcons();
		// Pretend we're a maxed player (126 combat, 99 hp) so the level colouring and the
		// over-HP max-hit highlight both render in previews.
		BetterMonsterExaminePanel panel = new BetterMonsterExaminePanel(icons, ds, wiki, cfg, () -> 126, () -> 99, icon);
		panel.search(monster, true, null);

		JFrame frame = new JFrame();
		frame.setUndecorated(true);
		frame.getContentPane().setLayout(new BorderLayout());
		frame.getContentPane().add(panel, BorderLayout.CENTER);
		frame.pack();
		frame.setSize(245, 2000);
		frame.validate();

		JScrollPane scroll = findScroll(panel);
		int contentH = scroll != null ? scroll.getViewport().getView().getPreferredSize().height : 1500;
		frame.setSize(245, contentH + 70);
		frame.validate();

		int w = Math.max(1, panel.getWidth());
		int h = Math.max(1, panel.getHeight());
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		panel.printAll(g);
		g.dispose();
		ImageIO.write(img, "png", out);
		frame.dispose();
		System.out.println("wrote " + out.getName() + " (" + w + "x" + h + ")");
	}

	private static JScrollPane findScroll(Container c)
	{
		for (Component comp : c.getComponents())
		{
			if (comp instanceof JScrollPane)
			{
				return (JScrollPane) comp;
			}
			if (comp instanceof Container)
			{
				JScrollPane s = findScroll((Container) comp);
				if (s != null)
				{
					return s;
				}
			}
		}
		return null;
	}
}
