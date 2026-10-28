package dev.ilyapple.ghc.mod.ui;

/**
 * A virtual canvas for the window, the way Athen's {@code Resolute} does it: lay out on a fixed-size canvas and
 * scale the pose to the screen, dividing mouse coordinates by the same factor. Here the canvas is a minimum rather
 * than a fixed size: at GUI scale 3–4 or in a small window the whole window is drawn smaller so the layout never
 * gets squeezed, and at the player's usual scale nothing changes (it never draws bigger than their GUI scale).
 */
public final class Canvas {
	/** The smallest canvas the window is laid out for, in GUI units; the pages are designed at 854x480. */
	public static final int MIN_WIDTH = 760;
	public static final int MIN_HEIGHT = 480;

	private Canvas() {}

	/** How much to scale the canvas by to fit a screen of {@code guiWidth} x {@code guiHeight} GUI units. */
	public static float scale(int guiWidth, int guiHeight) {
		return Math.min(1f, Math.min(guiHeight / (float) MIN_HEIGHT, guiWidth / (float) MIN_WIDTH));
	}
}
