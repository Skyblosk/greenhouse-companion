package dev.ilyapple.ghc.mod.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import dev.ilyapple.ghc.core.Greenhouse;
import dev.ilyapple.ghc.core.Plants;
import dev.ilyapple.ghc.core.StageSet;
import dev.ilyapple.ghc.core.StageTracker;
import dev.ilyapple.ghc.core.flow.Census;
import dev.ilyapple.ghc.core.flow.Estimator;
import dev.ilyapple.ghc.core.flow.ExitEvaluator;
import dev.ilyapple.ghc.core.flow.FlowRunner;
import dev.ilyapple.ghc.core.flow.Flows;
import dev.ilyapple.ghc.core.schematic.Schematic;
import dev.ilyapple.ghc.core.schematic.SkyblockerCrops;
import dev.ilyapple.ghc.mod.GreenhouseCompanion;
import dev.ilyapple.ghc.mod.ModState;

/** Flow preview, live grid, exit controls and their page-local state. */
final class FlowPage {
	private final CompanionScreen screen;
	/** A typed exit amount on the Flow page: the box and the condition field it edits. */
	private record AmountField(EditBox box, int plotId, String stepId, int exitIndex, int conditionIndex, String field, int value) {}

	private final Map<String, AmountField> amountBoxes = new LinkedHashMap<>();
	private @Nullable String amountsBuiltFor;
	private @Nullable Integer selectedStep;
	private boolean live = true;
	private int stepScroll;
	private @Nullable String copyError;

	FlowPage(CompanionScreen screen) {
		this.screen = screen;
	}

	// Flow page

	void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		ModState.Site site = screen.site();
		int top = screen.contentTop();
		int x = screen.contentX();
		int w = screen.contentW();
		int leftWidth = Math.min(150, Math.max(116, w / 5));
		int rightWidth = Math.min(190, Math.max(136, w / 4));
		int right = x + w - rightWidth;
		int center = x + leftWidth + Theme.GAP;
		int centerWidth = right - Theme.GAP - center;

		Theme.card(g, x, top, leftWidth, screen.height - top - Theme.PAD, Theme.glass(Theme.SURFACE), Theme.BORDER);
		Theme.card(g, right, top, rightWidth, screen.height - top - Theme.PAD, Theme.glass(Theme.SURFACE), Theme.BORDER);
		if (site == null) {
			g.textWithWordWrap(screen.font(), Component.literal("Walk into one of your greenhouses in the Garden. Each greenhouse you visit shows up here."),
					center, top + 4, centerWidth, Theme.MUTED, false);
			return;
		}
		Greenhouse.Status status = screen.status(site);
		FlowRunner runner = status.runner();
		int shown = shownStep(runner);
		stepList(g, mouseX, mouseY, site, status, x, top, leftWidth, shown);
		gridPanel(g, mouseX, mouseY, site, status, center, top, centerWidth, shown);
		exitPanel(g, mouseX, mouseY, site, status, right, top, rightWidth, shown);
	}

	private void stepList(GuiGraphicsExtractor g, int mouseX, int mouseY, ModState.Site site, Greenhouse.Status status, int x, int y, int w, int shown) {
		List<ModState.Site> sites = screen.sites();
		int rowY = y + 7;
		g.text(screen.font(), "◀", x + 6, rowY, sites.size() > 1 ? Theme.TEXT : Theme.FAINT, false);
		g.text(screen.font(), "▶", x + w - 12, rowY, sites.size() > 1 ? Theme.TEXT : Theme.FAINT, false);
		screen.addHotspot(new CompanionScreen.Hotspot(x + 2, rowY - 2, 14, 12, event -> screen.moveSite(-1)));
		screen.addHotspot(new CompanionScreen.Hotspot(x + w - 16, rowY - 2, 14, 12, event -> screen.moveSite(1)));
		String name = Theme.fit(screen.font(), site.name(), w - 36);
		g.text(screen.font(), name, x + (w - screen.font().width(name)) / 2, rowY, site.equals(GreenhouseCompanion.currentSite()) ? Theme.accent() : Theme.TEXT, false);

		// Which flow plot it follows: click to cycle (right-click backwards)
		Flows flows = screen.state().flows(site);
		ModState.Assignment assignment = screen.state().assignment(site);
		String follows = flows == null ? "Import flows first" : assignment == null ? "Follow: none" : "Follow: plot " + assignment.plotId() + (assignment.auto() ? " (auto)" : "");
		int pillY = rowY + 14;
		boolean hovered = mouseX >= x + 6 && mouseX < x + w - 6 && mouseY >= pillY && mouseY < pillY + 14;
		Theme.card(g, x + 6, pillY, w - 12, 14, hovered ? Theme.CARD_HOVER : Theme.CARD, assignment == null ? Theme.BORDER : Theme.alpha(Theme.accent(), 0x99));
		g.text(screen.font(), Theme.fit(screen.font(), follows, w - 24), x + 12, pillY + 3, assignment == null ? Theme.MUTED : Theme.accent(), false);
		if (flows != null) {
			screen.addHotspot(new CompanionScreen.Hotspot(x + 6, pillY, w - 12, 14, event -> {
				List<Flows.Plot> plots = flows.plots();
				int index = -1;
				for (int i = 0; i < plots.size(); i++) if (assignment != null && plots.get(i).id() == assignment.plotId()) index = i;
				int next = event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT ? index - 1 : index + 1;
				screen.state().assign(site, next < 0 || next >= plots.size() ? null : plots.get(next).id(), false);
				screen.state().setNote(site, "Picked by you");
				dev.ilyapple.ghc.mod.render.LayoutOverlay.clear();
				selectedStep = null;
				screen.refresh();
			}));
		}

		int listTop = pillY + 22;
		Theme.small(g, screen.font(), "STEPS", x + 8, listTop - 6, Theme.FAINT);
		FlowRunner runner = status.runner();
		if (runner == null) {
			g.textWithWordWrap(screen.font(), Component.literal(flows == null
					? "Copy the live plants to a layout, or import a SkyShards export on the Flows page."
					: "Copy live plants to a new layout, or click Follow to pick a flow plot."), x + 8, listTop + 2, w - 16, Theme.MUTED, false);
			return;
		}

		Map<Integer, double[]> timing = new HashMap<>();
		double cumulative = 0;
		for (Estimator.StepEstimate estimate : status.plan()) {
			if (!timing.containsKey(estimate.stepIndex())) timing.put(estimate.stepIndex(), new double[] {cumulative, estimate.cycles()});
			cumulative += estimate.cycles();
		}
		long period = screen.state().clock(site.server()).periodMs();

		List<Flows.Step> steps = runner.plot().steps();
		int cardHeight = 30;
		int bottom = screen.height - Theme.PAD - 1;
		int visible = Math.max(1, (bottom - listTop) / (cardHeight + 4));
		stepScroll = Math.max(0, Math.min(stepScroll, Math.max(0, steps.size() - visible)));
		g.enableScissor(x + 1, listTop, x + w - 1, bottom);
		for (int i = stepScroll; i < steps.size(); i++) {
			int cy = listTop + (i - stepScroll) * (cardHeight + 4);
			if (cy > screen.height) break;
			Flows.Step step = steps.get(i);
			boolean current = i == runner.stepIndex();
			boolean selected = i == shown;
			boolean over = mouseX >= x + 6 && mouseX < x + w - 6 && mouseY >= cy && mouseY < cy + cardHeight;
			Theme.card(g, x + 6, cy, w - 12, cardHeight, over ? Theme.CARD_HOVER : Theme.CARD,
					selected ? Theme.WARNING : current ? Theme.alpha(Theme.accent(), 0xAA) : Theme.BORDER);
			Theme.rounded(g, x + 10, cy + 5, 12, 12, current ? Theme.alpha(Theme.accent(), 0x55) : Theme.BORDER);
			String number = Integer.toString(i + 1);
			g.text(screen.font(), number, x + 16 - screen.font().width(number) / 2, cy + 7, current ? Theme.accent() : Theme.TEXT, false);
			g.text(screen.font(), Theme.fit(screen.font(), step.label(), w - 40), x + 26, cy + 5, Theme.TEXT_BRIGHT, false);

			String line;
			int color;
			double[] time = timing.get(i);
			if (current && runner.finished()) {
				line = "Finished";
				color = Theme.SUCCESS;
			} else if (current) {
				line = status.swapPending() ? "Swap to this layout" : "Now" + (time != null && Double.isFinite(time[1]) ? " · ~" + CompanionScreen.duration(time[1], period) + " left" : "");
				color = status.swapPending() ? Theme.WARNING : Theme.accent();
			} else if (time != null) {
				line = Double.isFinite(time[0]) ? "In ~" + CompanionScreen.duration(time[0], period) + (Double.isFinite(time[1]) ? " · ~" + CompanionScreen.duration(time[1], period) : "") : "Later";
				color = Theme.WARNING;
			} else {
				line = step.exits().isEmpty() ? "No exit" : "—";
				color = Theme.FAINT;
			}
			g.text(screen.font(), Theme.fit(screen.font(), line, w - 40), x + 26, cy + 17, color, false);
			int index = i;
			screen.addHotspot(new CompanionScreen.Hotspot(x + 6, Math.max(cy, listTop), w - 12, cardHeight, event -> selectedStep = index));
		}
		g.disableScissor();
	}

	private void gridPanel(GuiGraphicsExtractor g, int mouseX, int mouseY, ModState.Site site, Greenhouse.Status status, int x, int y, int w, int shown) {
		FlowRunner runner = status.runner();
		Flows.Step step = runner == null || shown < 0 ? null : runner.plot().steps().get(shown);
		boolean isCurrent = runner != null && shown == runner.stepIndex();
		boolean showLive = step == null || live && isCurrent;

		String title = step == null ? "What's growing" : step.label() + (isCurrent ? "" : "  (preview)");
		if (w > 110) g.text(screen.font(), Theme.fit(screen.font(), title, w - 100), x, y + 3, Theme.TEXT_BRIGHT, false);
		int px = x + w;
		for (String label : new String[] {"Live", "Layout"}) {
			int pw = screen.font().width(label) + 12;
			px -= pw;
			boolean activePill = label.equals("Live") == showLive;
			boolean enabled = label.equals("Layout") ? step != null : isCurrent || step == null;
			boolean over = mouseX >= px && mouseX < px + pw && mouseY >= y && mouseY < y + 14;
			int accent = label.equals("Live") ? Theme.accent() : Theme.WARNING;
			Theme.card(g, px, y, pw, 14, activePill ? Theme.alpha(accent, 0x33) : over && enabled ? Theme.CARD_HOVER : Theme.CARD,
					activePill ? Theme.alpha(accent, 0x99) : Theme.BORDER);
			g.text(screen.font(), label, px + 6, y + 3, activePill ? accent : enabled ? Theme.TEXT : Theme.FAINT, false);
			if (enabled) {
				boolean wantLive = label.equals("Live");
				screen.addHotspot(new CompanionScreen.Hotspot(px, y, pw, 14, event -> live = wantLive));
			}
			px -= 4;
		}

		int gridTop = y + 20;
		if (status.swapPending() && step != null && isCurrent) {
			Theme.card(g, x, gridTop, w, 16, Theme.alpha(Theme.WARNING, 0x33), Theme.alpha(Theme.WARNING, 0x66));
			g.text(screen.font(), Theme.fit(screen.font(), "Swap this greenhouse to " + step.label() + ", then press Swapped", w - 8), x + 4, gridTop + 4, Theme.WARNING, false);
			gridTop += 20;
		}
		int cell = Math.max(6, Math.min(28, Math.min(w, screen.height - gridTop - 22) / 10));
		int size = cell * 10;
		int gx = x + (w - size) / 2;
		int gy = gridTop;

		// Turned the way this greenhouse is planted, so it lines up with what grows there
		Schematic drawn = step == null ? null : step.schematic();
		Schematic layout = drawn == null ? null : drawn.rotated(site == null ? 0 : site.greenhouse().rotation());
		Census census = status.census();
		Plants plants = screen.state().plants();

		g.fill(gx - 1, gy - 1, gx + size + 1, gy + size + 1, Theme.BORDER_STRONG);
		for (int cx = 0; cx < 10; cx++) {
			for (int cz = 0; cz < 10; cz++) {
				int sx = gx + cx * cell;
				int sy = gy + cz * cell;
				g.fill(sx, sy, sx + cell, sy + cell, Theme.CARD);
				String ground = groundAt(layout, census, plants, cx, cz, showLive);
				if (ground != null) Theme.groundTile(g, ground, sx, sy, cell, showLive ? 150 : 210);
				g.outline(sx, sy, cell, cell, 0x400F172A);
			}
		}

		if (layout != null) {
			int placementAlpha = showLive ? 70 : 255;
			for (Map.Entry<Integer, Integer> placement : layout.placements().entrySet()) drawCrop(g, placement.getValue(), placement.getKey(), gx, gy, cell, placementAlpha);
			for (Map.Entry<Integer, Integer> target : layout.targets().entrySet()) {
				int anchor = target.getKey();
				int span = SkyblockerCrops.sizeOf(target.getValue()) * cell;
				int sx = gx + anchor / 10 * cell;
				int sy = gy + anchor % 10 * cell;
				boolean filled = showLive && census.targetFilled(anchor, target.getValue());
				if (!filled) drawCrop(g, target.getValue(), anchor, gx, gy, cell, showLive ? 60 : 110);
				g.outline(sx + 1, sy + 1, span - 2, span - 2, filled ? Theme.SUCCESS : Theme.WARNING);
			}
		}

		if (showLive) {
			for (Census.Plant plant : census.plants()) {
				int anchor = plant.cells().stream().min(Comparator.comparingInt((Integer c) -> c / 10).thenComparingInt(c -> c % 10)).orElse(-1);
				if (anchor < 0) continue;
				int span = Math.min(plant.kind().size(), 10 - anchor / 10) * cell;
				int sx = gx + anchor / 10 * cell;
				int sy = gy + anchor % 10 * cell;
				Theme.cropIcon(g, plant.id(), sx + 1, sy + 1, span - 2, 255);
				if (plant.kind().grows()) {
					String stage = stageLabel(plant.stages(), (span - 2) * 2);
					int labelColor = plant.exact() ? Theme.TEXT_BRIGHT : plant.lookKnown() ? Theme.WARNING : Theme.DANGER;
					int tw = screen.font().width(stage) / 2;
					g.fill(sx + span - tw - 3, sy + span - 6, sx + span - 1, sy + span - 1, 0xC00F172A);
					Theme.small(g, screen.font(), stage, sx + span - tw - 2, sy + span - 5, labelColor);
				}
			}
			if (layout != null) {
				// The same verdicts as the red boxes in the greenhouse (soil isn't known here)
				for (dev.ilyapple.ghc.core.overlay.PlotOverlay.Mark mark : dev.ilyapple.ghc.core.overlay.PlotOverlay.plan(layout, census, plants, c -> null, java.util.Set.of(),
						new dev.ilyapple.ghc.core.overlay.PlotOverlay.Options(screen.settings().devourerRingOnlyWhenNeeded))) {
					if (!mark.action().problem()) continue;
					int span = mark.size() * cell;
					g.outline(gx + mark.anchor() / 10 * cell, gy + mark.anchor() % 10 * cell, span, span, mark.action().color());
				}
			}
		}

		if (mouseX >= gx && mouseX < gx + size && mouseY >= gy && mouseY < gy + size) {
			int hx = (mouseX - gx) / cell;
			int hz = (mouseY - gy) / cell;
			List<Component> lines = cellTooltip(layout, census, hx, hz, showLive);
			if (!lines.isEmpty()) screen.tooltip(g, lines, mouseX, mouseY);
		}

		int ly = gy + size + 6;
		if (showLive) {
			String[] legend = {"Stage: white exact, amber a range, red look not on record", "Box: + place blue, ✘ remove red, ⊘ clear orange (as in the greenhouse)", "Ring: spawn spot (green once filled)"};
			for (int i = 0; i < legend.length; i++) Theme.small(g, screen.font(), Theme.fit(screen.font(), legend[i], w * 2), x, ly + i * 6, Theme.MUTED);
		} else if (layout != null) {
			StringBuilder needs = new StringBuilder("Places ");
			layout.inputCounts().forEach((id, count) -> needs.append(count).append("x ").append(screen.name(id)).append("  "));
			Theme.small(g, screen.font(), Theme.fit(screen.font(), needs.toString(), w * 2), x, ly, Theme.MUTED);
			StringBuilder wants = new StringBuilder("Targets ");
			layout.targetCounts().forEach((id, count) -> wants.append(count).append("x ").append(screen.name(id)).append("  "));
			Theme.small(g, screen.font(), Theme.fit(screen.font(), wants.toString(), w * 2), x, ly + 6, Theme.WARNING);
		}
	}

	private @Nullable String groundAt(@Nullable Schematic layout, Census census, Plants plants, int x, int z, boolean showLive) {
		if (showLive) {
			StageTracker.Tracked there = census.cell(Schematic.cell(x, z));
			if (there != null) return there.plant().ground();
		}
		if (layout == null) return null;
		int id = layout.at(x, z);
		if (id > 0) {
			SkyblockerCrops.Crop crop = SkyblockerCrops.byId(id);
			Plants.Plant plant = crop == null || crop.simId() == null ? null : plants.byId(crop.simId());
			return plant == null ? null : plant.ground();
		}
		if (id == 0) {
			for (Map.Entry<Integer, Integer> target : layout.targets().entrySet()) {
				SkyblockerCrops.Crop crop = SkyblockerCrops.byId(target.getValue());
				int ax = target.getKey() / 10;
				int az = target.getKey() % 10;
				int span = crop == null ? 1 : crop.size();
				if (x >= ax && x < ax + span && z >= az && z < az + span && crop != null && crop.simId() != null) {
					Plants.Plant plant = plants.byId(crop.simId());
					return plant == null ? null : plant.ground();
				}
			}
		}
		return null;
	}

	private void drawCrop(GuiGraphicsExtractor g, int cropId, int anchor, int gx, int gy, int cell, int alpha) {
		SkyblockerCrops.Crop crop = SkyblockerCrops.byId(cropId);
		if (crop == null || crop.simId() == null) return;
		int span = Math.min(crop.size(), 10 - anchor / 10) * cell;
		Theme.cropIcon(g, crop.simId(), gx + anchor / 10 * cell + 1, gy + anchor % 10 * cell + 1, span - 2, alpha);
	}

	private List<Component> cellTooltip(@Nullable Schematic layout, Census census, int x, int z, boolean showLive) {
		List<Component> lines = new ArrayList<>();
		StageTracker.Tracked there = census.cell(Schematic.cell(x, z));
		if (showLive && there != null) {
			Plants.Plant plant = there.plant();
			lines.add(Component.literal(plant.name()).withColor(Theme.TEXT_BRIGHT));
			if (plant.grows()) {
				lines.add(Component.literal("Stage " + there.stages() + " of " + plant.maxStage() + (there.exact() ? "" : "  (not exact)"))
						.withColor(there.exact() ? Theme.SUCCESS : Theme.WARNING));
				lines.add(Component.literal("Fully grown at " + plant.readyStage()).withColor(Theme.MUTED));
			}
			lines.add(Component.literal(switch (there.origin()) {
				case SPAWNED -> "Spawned here";
				case PLACED -> "Placed by you";
				case PLANTED -> "Planted by you";
				case UNKNOWN -> "Already here when first seen";
			}).withColor(Theme.MUTED));
			if (!there.lookKnown()) lines.add(Component.literal("This look isn't on record yet (Stages page)").withColor(Theme.DANGER));
			if (there.mayStall()) lines.add(Component.literal("It missed a tick: dry or wilting?").withColor(Theme.WARNING));
		}
		if (layout != null) {
			int id = layout.at(x, z);
			if (id > 0) {
				SkyblockerCrops.Crop crop = SkyblockerCrops.byId(id);
				if (crop != null) lines.add(Component.literal("Layout: " + screen.name(crop.simId() == null ? crop.name() : crop.simId())).withColor(Theme.INFO));
			} else if (id == 0) {
				lines.add(Component.literal("Layout: spawn spot").withColor(Theme.WARNING));
			}
		}
		lines.add(Component.literal("Cell " + x + ", " + z).withColor(Theme.FAINT));
		return lines;
	}

	private void exitPanel(GuiGraphicsExtractor g, int mouseX, int mouseY, ModState.Site site, Greenhouse.Status status, int x, int y, int w, int shown) {
		int cy = y + 6;
		int textWidth = w - 16;
		FlowRunner runner = status.runner();
		int buttonsY = screen.height - Theme.PAD - 22;
		String note = screen.state().note(site);
		int noteTop = buttonsY - 4;
		if (note != null) {
			noteTop -= screen.font().split(Component.literal(note), textWidth).size() * (screen.font().lineHeight + 1) + 2;
			screen.wrap(g, note, x + 8, noteTop, textWidth, Theme.MUTED);
		}
		for (AmountField field : amountBoxes.values()) field.box().visible = false;
		if (runner == null) {
			cy = screen.wrap(g, "Create a layout from this greenhouse", x + 8, cy, textWidth, Theme.TEXT_BRIGHT) + 8;
			cy = screen.wrap(g, "Harvest all fully grown mutations before copying. Any left here will be saved as placed inputs.",
					x + 8, cy, textWidth, Theme.WARNING) + 8;
			cy = screen.wrap(g, "All base crops are copied, at any stage. Known growing mutations become targets. Uncertain mutations are left out; empty cells stay unspecified.",
					x + 8, cy, textWidth, Theme.MUTED) + 8;
			if (!status.census().plants().isEmpty()) {
				screen.button(g, mouseX, mouseY, "Copy live to layout", x + 8, cy, Theme.accent(), () -> {
					try {
						screen.state().copyLiveLayout(site);
						copyError = null;
						selectedStep = null;
						live = false;
						dev.ilyapple.ghc.mod.render.LayoutOverlay.clear();
						screen.refresh();
					} catch (RuntimeException e) {
						copyError = e.getMessage();
					}
				});
			} else cy = screen.wrap(g, "Visit this greenhouse to load its plants first.", x + 8, cy, textWidth, Theme.MUTED);
			if (copyError != null) screen.wrap(g, copyError, x + 8, cy + 26, textWidth, Theme.DANGER);
			return;
		}
		g.enableScissor(x + 1, y + 1, x + w - 1, noteTop - 3);
		Flows.Step step = runner.plot().steps().get(Math.max(0, shown));
		boolean isCurrent = shown == runner.stepIndex();
		Theme.small(g, screen.font(), isCurrent ? "LEAVE THIS STEP WHEN" : "THIS STEP ENDS WHEN", x + 8, cy, Theme.FAINT);
		cy += 8;
		if (step.exits().isEmpty()) cy = screen.wrap(g, "No exit: the plot stays on this step.", x + 8, cy, textWidth, Theme.MUTED);
		for (int i = 0; i < step.exits().size(); i++) {
			Flows.Exit exit = step.exits().get(i);
			String to = exit.to() == null ? (shown == runner.plot().steps().size() - 1 && !runner.plot().loop() ? "finish" : "next step")
					: label(runner.plot(), exit.to());
			boolean fires = isCurrent && status.decision() != null && status.decision().exitIndex() == i;
			cy = screen.wrap(g, "→ " + to + (exit.any() ? "  (any of)" : ""), x + 8, cy, textWidth, fires ? Theme.SUCCESS : Theme.INFO);
			if (isCurrent && i < status.verdicts().size()) {
				List<ExitEvaluator.Verdict> verdicts = status.verdicts().get(i);
				for (int j = 0; j < verdicts.size(); j++) {
					ExitEvaluator.Verdict verdict = verdicts.get(j);
					int color = !verdict.supported() ? Theme.FAINT : verdict.value() ? Theme.SUCCESS : Theme.TEXT;
					cy = screen.wrap(g, (verdict.value() ? "✔ " : "✘ ") + verdict.text(), x + 12, cy, textWidth - 4, color);
					cy = screen.wrap(g, verdict.detail(), x + 22, cy, textWidth - 14, Theme.MUTED);
					if (verdict.assumed()) cy = screen.wrap(g, "≈ " + (verdict.value() ? "taken as met" : "taken as not met") + " at the end of the range", x + 22, cy, textWidth - 14, Theme.WARNING);
					if (j < exit.when().size()) cy = amountRow(g, mouseX, mouseY, i, j, exit.when().get(j), x + 22, cy, noteTop - 3);
				}
			} else {
				for (int j = 0; j < exit.when().size(); j++) {
					Flows.Condition condition = exit.when().get(j);
					cy = screen.wrap(g, "• " + describe(condition), x + 12, cy, textWidth - 4, Theme.TEXT);
					cy = amountRow(g, mouseX, mouseY, i, j, condition, x + 22, cy, noteTop - 3);
				}
			}
			cy += 4;
		}

		cy += 2;
		g.fill(x + 8, cy, x + w - 8, cy + 1, Theme.BORDER);
		cy += 5;
		Theme.small(g, screen.font(), "ESTIMATE", x + 8, cy, Theme.FAINT);
		cy += 8;
		long period = screen.state().clock(site.server()).periodMs();
		double total = 0;
		int shownSteps = 0;
		for (Estimator.StepEstimate estimate : status.plan()) {
			if (shownSteps++ >= 5) break;
			String what = shownSteps == 1 ? "This step" : estimate.label();
			if (!estimate.known()) {
				cy = screen.wrap(g, what + ": can't tell (" + estimate.reason() + ")", x + 8, cy, textWidth, Theme.MUTED);
				break;
			}
			total += estimate.cycles();
			String line = what + ": ~" + CompanionScreen.duration(estimate.cycles(), period) + (shownSteps == 1 ? " left" : "") + "  · ends in ~" + CompanionScreen.duration(total, period);
			cy = screen.wrap(g, line, x + 8, cy, textWidth, shownSteps == 1 ? Theme.accent() : Theme.TEXT);
		}
		g.disableScissor();

		int bx = x + 6;
		if (status.swapPending() && isCurrent) bx += screen.button(g, mouseX, mouseY, "Swapped ✔", bx, buttonsY, Theme.SUCCESS, () -> {
			site.greenhouse().swapDone();
			screen.refresh();
		}) + 4;
		if (!isCurrent) bx += screen.button(g, mouseX, mouseY, "Start here", bx, buttonsY, Theme.WARNING, () -> {
			runner.enter(shown);
			site.greenhouse().swapDone();
			screen.state().markDirty();
			dev.ilyapple.ghc.mod.render.LayoutOverlay.clear();
			screen.refresh();
		}) + 4;
	}

	/**
	 * A typed amount under an exit condition that has one (stock, mutations spawned, harvested or grown, cycles): type
	 * a number and press Enter (or click away), or use − / + (shift ×10, ctrl ×100). It changes the flows file of the
	 * profile shown, keeping every greenhouse's step. Returns the y below the row.
	 */
	private int amountRow(GuiGraphicsExtractor g, int mouseX, int mouseY, int exitIndex, int conditionIndex, Flows.Condition condition, int x, int y, int bottom) {
		String field = Flows.amountField(condition);
		if (field == null) return y;
		AmountField amount = amountBoxes.get(exitIndex + "/" + conditionIndex);
		if (amount == null) return y;
		String label = switch (field) {
			case "qty" -> "Items";
			case "n" -> "Cycles";
			default -> "Mutations";
		};
		Theme.small(g, screen.font(), label, x, y + 4, Theme.FAINT);
		int bx = x + 32;
		boolean inView = y >= screen.contentTop() && y + 13 <= bottom;
		amount.box().setX(bx);
		amount.box().setY(y);
		amount.box().visible = inView;
		if (inView) {
			int value = amount.value();
			screen.stepper(g, mouseX, mouseY, "−", bx + 46, y, event -> setAmount(amount, value - screen.step(event)));
			screen.stepper(g, mouseX, mouseY, "+", bx + 64, y, event -> setAmount(amount, value + screen.step(event)));
		}
		return y + 17;
	}

	/** The step whose exits the Flow page shows for {@code site}: the one picked in the list, else the current one. */
	private int shownStep(@Nullable FlowRunner runner) {
		return selectedStep != null && runner != null && selectedStep < runner.plot().steps().size() ? selectedStep : runner == null ? -1 : runner.stepIndex();
	}

	/** What the amount boxes were made for: the greenhouse, the step shown and the flows text. */
	private @Nullable String amountsFor() {
		ModState.Site site = screen.site();
		if (site == null) return null;
		FlowRunner runner = site.greenhouse().runner();
		ModState.FlowFile file = screen.state().activeFile();
		return site.key() + "/" + shownStep(runner) + "/" + (file == null ? 0 : file.text().hashCode());
	}

	/** One box per exit condition with an amount, on the step the Flow page shows. */
	void buildAmountBoxes() {
		amountsBuiltFor = amountsFor();
		ModState.Site site = screen.site();
		FlowRunner runner = site == null ? null : site.greenhouse().runner();
		int shown = shownStep(runner);
		if (runner == null || shown < 0) return;
		Flows.Step step = runner.plot().steps().get(shown);
		for (int i = 0; i < step.exits().size(); i++) {
			List<Flows.Condition> when = step.exits().get(i).when();
			for (int j = 0; j < when.size(); j++) {
				String field = Flows.amountField(when.get(j));
				if (field == null) continue;
				int value = when.get(j).integer(field, field.equals("count") ? 1 : 0);
				EditBox box = new EditBox(screen.font(), 0, -100, 42, 13, Component.literal("Amount"));
				box.setMaxLength(7);
				box.setValue(Integer.toString(value));
				box.visible = false;
				screen.addAmountBox(box);
				amountBoxes.put(i + "/" + j, new AmountField(box, runner.plot().id(), step.id(), i, j, field, value));
			}
		}
	}

	private void setAmount(AmountField amount, int value) {
		ModState.FlowFile file = screen.state().activeFile();
		if (file == null) return;
		try {
			screen.state().editActiveFlow(Flows.withAmount(file.text(), amount.plotId(), amount.stepId(), amount.exitIndex(), amount.conditionIndex(), amount.field(), value));
			screen.refresh();
		} catch (RuntimeException e) {
			GreenhouseCompanion.LOGGER.warn("[GHC] Couldn't change the amount: {}", e.getMessage());
		}
	}

	private static String label(Flows.Plot plot, String stepId) {
		int index = plot.indexOf(stepId);
		return index < 0 ? stepId : plot.steps().get(index).label();
	}

	private String describe(Flows.Condition condition) {
		String name = screen.name(condition.mutationId());
		int stage = condition.integer("stage", 0);
		int count = condition.integer("count", 1);
		return switch (condition.kind()) {
			case "cycles" -> condition.integer("n", 0) + " cycles in step";
			case "inventoryAtLeast" -> "inventory " + screen.name(condition.string("item")) + " >= " + condition.integer("qty", 0);
			case "inventoryBelow" -> "inventory " + screen.name(condition.string("item")) + " < " + condition.integer("qty", 0);
			case "collectedAtLeast" -> "collected " + screen.name(condition.string("item")) + " >= " + condition.integer("qty", 0) + " in total";
			case "layoutShort" -> "inventory can't fill the layout";
			case "allFullyGrown" -> "everything fully grown";
			case "noneFullyGrown" -> "nothing fully grown";
			case "fullyGrown" -> count > 1 ? count + " x " + name + " fully grown" : name + " fully grown";
			case "mutationHarvested" -> count + " x " + name + " harvested";
			case "mutationSpawned" -> count + " x " + name + " spawned";
			case "lowestStageAtLeast" -> "every " + name + " at stage " + stage + "+";
			case "lowestStageBelow" -> "some " + name + " below stage " + stage + " (or none)";
			case "highestStageAtLeast" -> "some " + name + " at stage " + stage + "+";
			case "highestStageBelow" -> "every " + name + " below stage " + stage + " (or none)";
			case "targetsFilled" -> condition.integer("count", 0) <= 0 ? "every target filled" : condition.integer("count", 0) + " targets filled";
			case "plantDecayed" -> "a " + screen.name(condition.string("kindId")) + " decayed";
			case "stepVisits" -> "entered this step " + Math.max(1, count) + " times";
			case "decayImminent" -> "something decays within " + condition.integer("withinCycles", 0) + " cycles";
			case "group" -> (condition.any() ? "any of " : "all of ") + condition.of().size() + " conditions";
			case "script" -> "script: " + condition.string("expr");
			default -> condition.kind();
		};
	}

	/** {@code 7}, {@code 107–8}, or {@code 85+} when a range doesn't fit in {@code maxWidth} (full-size pixels). */
	private String stageLabel(StageSet stages, int maxWidth) {
		if (stages.isEmpty()) return "?";
		if (stages.size() == 1) return Integer.toString(stages.lo());
		String hi = Integer.toString(stages.hi());
		String lo = Integer.toString(stages.lo());
		if (hi.length() == lo.length() && hi.length() > 1 && hi.substring(0, hi.length() - 1).equals(lo.substring(0, lo.length() - 1))) {
			hi = hi.substring(hi.length() - 1);
		}
		String text = lo + "–" + hi;
		return screen.font().width(text) <= maxWidth ? text : lo + "+";
	}


	void tick() {
		boolean typing = false;
		for (AmountField amount : amountBoxes.values()) {
			if (amount.box().isFocused()) {
				typing = true;
				continue;
			}
			String digits = amount.box().getValue().replaceAll("[^0-9]", "");
			if (digits.isEmpty()) {
				amount.box().setValue(Integer.toString(amount.value()));
				continue;
			}
			int typed = (int) Math.min(9_999_999L, Long.parseLong(digits));
			if (typed == amount.value()) continue;
			setAmount(amount, typed);
			// Shown again from the flows: the new value once the boxes are rebuilt, the old one if it couldn't be changed
			amount.box().setValue(Integer.toString(amount.value()));
		}
		if (!typing && !java.util.Objects.equals(amountsFor(), amountsBuiltFor)) screen.rebuildPage();
	}

	void clearWidgets() {
		amountBoxes.clear();
		amountsBuiltFor = null;
	}

	void followCurrentStep() { selectedStep = null; }
	void previewLayout() { live = false; }
	void scroll(int delta) { stepScroll += delta; }

	boolean enter() {
		for (AmountField amount : amountBoxes.values()) {
			if (!amount.box().isFocused()) continue;
			screen.setFocused(null);
			amount.box().setFocused(false);
			return true;
		}
		return false;
	}
}
