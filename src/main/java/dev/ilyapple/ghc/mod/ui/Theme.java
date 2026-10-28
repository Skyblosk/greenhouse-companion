package dev.ilyapple.ghc.mod.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import dev.ilyapple.ghc.mod.GreenhouseCompanion;

/**
 * The window's look. Layout and surfaces follow Waypointer's GUI tokens (spacing, a sidebar, translucent dark
 * surfaces with hairline borders and one accent colour); the crop and ground pictures are SkyShards' (MIT). The
 * accent is the player's pick in Settings.
 */
public final class Theme {
	public static final int GAP_TIGHT = 4;
	public static final int GAP = 8;
	public static final int PAD = 12;
	public static final int SIDEBAR_W = 132;
	public static final int ROW_H = 22;
	public static final int BUTTON_H = 18;

	public static final int BACKDROP = 0xB8050810;
	public static final int SIDEBAR = 0xF20A0F18;
	public static final int SURFACE = 0xF00F1624;
	public static final int CARD = 0xFF151E2E;
	public static final int CARD_HOVER = 0xFF1C283C;
	public static final int CARD_SUNK = 0xFF0C121D;
	public static final int BORDER = 0x26FFFFFF;
	public static final int BORDER_STRONG = 0x4DFFFFFF;
	public static final int HOVER = 0x14FFFFFF;

	public static final int TEXT_BRIGHT = 0xFFF8FAFC;
	public static final int TEXT = 0xFFE2E8F0;
	public static final int DIM = 0xFFAEB7C4;
	public static final int MUTED = 0xFF7D8796;
	public static final int FAINT = 0xFF566070;

	public static final int SUCCESS = 0xFF34D399;
	public static final int WARNING = 0xFFFBBF24;
	public static final int DANGER = 0xFFF87171;
	public static final int INFO = 0xFF60A5FA;
	public static final int VIOLET = 0xFFA78BFA;

	private Theme() {}

	public static int accent() {
		return GreenhouseCompanion.settings().accent.color;
	}

	/** {@code color} at {@code alpha} (0–255). */
	public static int alpha(int color, int alpha) {
		return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0xFFFFFF);
	}

	/** A colour's alpha scaled by the menu opacity setting. */
	public static int glass(int color) {
		int opacity = GreenhouseCompanion.settings().menuOpacity;
		return alpha(color, ((color >>> 24) & 0xFF) * opacity / 100);
	}

	public static Identifier crop(String id) {
		return Identifier.fromNamespaceAndPath(GreenhouseCompanion.MOD_ID, "textures/gui/crops/" + id + ".png");
	}

	public static Identifier ground(String ground) {
		return Identifier.fromNamespaceAndPath(GreenhouseCompanion.MOD_ID, "textures/gui/ground/" + ground + ".png");
	}

	/** A crop picture (64x64) drawn at {@code size}, with {@code alpha} 0–255. */
	public static void cropIcon(GuiGraphicsExtractor g, String id, int x, int y, int size, int alpha) {
		g.blit(RenderPipelines.GUI_TEXTURED, crop(id), x, y, 0, 0, size, size, 64, 64, 64, 64, alpha(0xFFFFFF, alpha));
	}

	public static void groundTile(GuiGraphicsExtractor g, String ground, int x, int y, int size, int alpha) {
		g.blit(RenderPipelines.GUI_TEXTURED, ground(ground), x, y, 0, 0, size, size, 16, 16, 16, 16, alpha(0xFFFFFF, alpha));
	}

	/** A rectangle with its corner pixels cut, which reads as slightly rounded. */
	public static void rounded(GuiGraphicsExtractor g, int x, int y, int w, int h, int fill) {
		if (w <= 2 || h <= 2) {
			g.fill(x, y, x + w, y + h, fill);
			return;
		}
		g.fill(x + 1, y, x + w - 1, y + h, fill);
		g.fill(x, y + 1, x + 1, y + h - 1, fill);
		g.fill(x + w - 1, y + 1, x + w, y + h - 1, fill);
	}

	/** A hairline border around a rounded rectangle. */
	public static void border(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
		g.fill(x + 1, y, x + w - 1, y + 1, color);
		g.fill(x + 1, y + h - 1, x + w - 1, y + h, color);
		g.fill(x, y + 1, x + 1, y + h - 1, color);
		g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
	}

	public static void card(GuiGraphicsExtractor g, int x, int y, int w, int h, int fill, int border) {
		rounded(g, x, y, w, h, fill);
		border(g, x, y, w, h, border);
	}

	/** A small tinted label, like "Active" or "auto"; returns its width. */
	public static int badge(GuiGraphicsExtractor g, Font font, String text, int x, int y, int color) {
		int w = font.width(text) / 2 + 8;
		rounded(g, x, y, w, 9, alpha(color, 0x33));
		small(g, font, text, x + 4, y + 2, color);
		return w;
	}

	/** An on/off switch, 20x10. */
	public static void toggle(GuiGraphicsExtractor g, int x, int y, boolean on, boolean hovered) {
		int track = on ? alpha(accent(), hovered ? 0xFF : 0xCC) : hovered ? 0xFF3A4558 : 0xFF2A3344;
		rounded(g, x, y, 20, 10, track);
		int knobX = on ? x + 11 : x + 1;
		rounded(g, knobX, y + 1, 8, 8, on ? TEXT_BRIGHT : DIM);
	}

	/** A progress bar. */
	public static void bar(GuiGraphicsExtractor g, int x, int y, int w, int h, double fraction, int color) {
		rounded(g, x, y, w, h, CARD_SUNK);
		int filled = (int) Math.round(Math.max(0, Math.min(1, fraction)) * w);
		if (filled > 1) rounded(g, x, y, filled, h, color);
	}

	/** Text cut to {@code width} with an ellipsis. */
	public static String fit(Font font, String text, int width) {
		if (font.width(text) <= width) return text;
		return font.plainSubstrByWidth(text, Math.max(0, width - font.width("…"))) + "…";
	}

	/** Small text at half size. */
	public static void small(GuiGraphicsExtractor g, Font font, String text, int x, int y, int color) {
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(0.5f, 0.5f);
		g.text(font, text, 0, 0, color, false);
		g.pose().popMatrix();
	}

	/** A section heading: small caps text with a hairline after it. */
	public static void heading(GuiGraphicsExtractor g, Font font, String text, int x, int y, int w) {
		small(g, font, text.toUpperCase(java.util.Locale.ROOT), x, y, MUTED);
		int after = x + font.width(text.toUpperCase(java.util.Locale.ROOT)) / 2 + 4;
		if (after < x + w) g.fill(after, y + 2, x + w, y + 3, BORDER);
	}
}
