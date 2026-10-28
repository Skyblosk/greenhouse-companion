package dev.ilyapple.ghc.mod.ui;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import dev.ilyapple.ghc.core.Greenhouse;
import dev.ilyapple.ghc.core.flow.Estimator;
import dev.ilyapple.ghc.core.flow.FlowRunner;
import dev.ilyapple.ghc.mod.GardenGrid;
import dev.ilyapple.ghc.mod.GreenhouseCompanion;
import dev.ilyapple.ghc.mod.JellybeanView;
import dev.ilyapple.ghc.mod.ModState;
import dev.ilyapple.ghc.mod.Settings;

/**
 * A small panel: the greenhouse you're in, its flow step and what to do next, the Jellybeans' state, and the next
 * growth tick. Outside a greenhouse it lists the greenhouses waiting for a swap.
 */
public final class CompanionHud implements HudElement {
	private record Line(String text, int color) {}

	private Greenhouse.Status cached;
	private String cachedKey;
	private long cachedMs;

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
		Settings settings = GreenhouseCompanion.settings();
		Minecraft client = Minecraft.getInstance();
		if (!settings.hud || client.options.hideGui || client.player == null || client.screen instanceof CompanionScreen) return;
		boolean garden = GreenhouseCompanion.location().inGarden();
		if (settings.hudOnlyInGarden && !garden) return;

		List<Line> lines = new ArrayList<>();
		String title;
		ModState.Site site = GreenhouseCompanion.currentSite();
		String server = GardenGrid.server(client);
		var clock = GreenhouseCompanion.state().clock(server);
		if (site != null) {
			Greenhouse.Status status = status(site);
			FlowRunner runner = status.runner();
			ModState.Assignment assignment = GreenhouseCompanion.state().assignment(site);
			title = GreenhouseCompanion.siteLabel(site) + (assignment == null ? "" : " · plot " + assignment.plotId());
			if (runner == null) {
				lines.add(new Line(GreenhouseCompanion.state().flows(site) == null ? "Import flows: /ghc import" : "Not following: /ghc identify", Theme.MUTED));
			} else {
				lines.add(new Line((runner.stepIndex() + 1) + "/" + runner.plot().steps().size() + "  " + runner.step().label(), Theme.TEXT));
				if (runner.finished()) lines.add(new Line("Flow finished", Theme.SUCCESS));
				else if (status.swapPending()) lines.add(new Line("⚠ Swap to this layout", Theme.WARNING));
				else {
					Estimator.StepEstimate first = status.plan().isEmpty() ? null : status.plan().getFirst();
					if (first != null && first.known()) lines.add(new Line("~" + CompanionScreen.duration(first.cycles(), clock.periodMs()) + " left in step", Theme.accent()));
				}
			}
			int ready = 0;
			int growing = 0;
			int lowest = Integer.MAX_VALUE;
			for (JellybeanView.Bean bean : CompanionScreen.beansHere()) {
				if (bean.fullyGrown()) ready++;
				else {
					growing++;
					lowest = Math.min(lowest, bean.stages().lo());
				}
			}
			if (ready + growing > 0) lines.add(new Line("Jellybeans " + ready + " ready" + (growing > 0 ? ", " + growing + " growing (lowest " + lowest + ")" : ""), Theme.DIM));
			// What the red boxes ask for, and what the highlight says is ready
			String todo = dev.ilyapple.ghc.core.overlay.PlotOverlay.summary(dev.ilyapple.ghc.mod.render.LayoutOverlay.marks());
			int harvest = dev.ilyapple.ghc.mod.render.Highlight.plants();
			if (!todo.isEmpty()) lines.add(new Line("To do: " + todo, Theme.TEXT));
			if (harvest > 0) lines.add(new Line("Ready to harvest: " + harvest, settings.grownColor));
			int soon = dev.ilyapple.ghc.mod.render.Highlight.needWater();
			int dried = dev.ilyapple.ghc.mod.render.Highlight.driedOut();
			if (dried > 0) lines.add(new Line("☔ Dried out: " + dried, settings.driedColor));
			if (soon > 0) lines.add(new Line("☔ Needs water: " + soon, settings.needsWaterColor));
		} else {
			title = "Greenhouses";
			int waiting = 0;
			for (ModState.Site other : GreenhouseCompanion.state().sites(server)) {
				if (!other.greenhouse().swapPending()) continue;
				waiting++;
				if (waiting <= 3) lines.add(new Line("⚠ " + GreenhouseCompanion.siteLabel(other) + ": swap", Theme.WARNING));
			}
			if (waiting == 0) lines.add(new Line("Nothing to swap", Theme.MUTED));
		}
		lines.add(new Line(clock.synced() ? "Next tick " + CompanionScreen.duration(clock.nextTickMs() - System.currentTimeMillis()) : "Clock waiting", Theme.MUTED));

		Font font = client.font;
		int w = font.width(title) + 12;
		for (Line line : lines) w = Math.max(w, font.width(line.text()) + 12);
		w = Math.min(w, 220);
		int h = 16 + lines.size() * 10 + 2;
		int x = switch (settings.hudCorner) {
			case TOP_LEFT, BOTTOM_LEFT -> settings.hudX;
			case TOP_RIGHT, BOTTOM_RIGHT -> g.guiWidth() - settings.hudX - w;
		};
		int y = switch (settings.hudCorner) {
			case TOP_LEFT, TOP_RIGHT -> settings.hudY;
			case BOTTOM_LEFT, BOTTOM_RIGHT -> g.guiHeight() - settings.hudY - h;
		};
		Theme.card(g, x, y, w, h, 0xC00A0F18, Theme.BORDER);
		g.fill(x + 1, y + 1, x + 3, y + h - 1, Theme.accent());
		g.text(font, Theme.fit(font, title, w - 12), x + 7, y + 5, Theme.TEXT_BRIGHT, false);
		int ly = y + 17;
		for (Line line : lines) {
			g.text(font, Theme.fit(font, line.text(), w - 12), x + 7, ly, line.color(), false);
			ly += 10;
		}
	}

	private Greenhouse.Status status(ModState.Site site) {
		long now = System.currentTimeMillis();
		if (cached == null || !site.key().equals(cachedKey) || now - cachedMs > 1000) {
			cached = site.greenhouse().status(2);
			cachedKey = site.key();
			cachedMs = now;
		}
		return cached;
	}
}
