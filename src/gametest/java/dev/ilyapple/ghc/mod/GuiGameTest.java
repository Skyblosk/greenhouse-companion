package dev.ilyapple.ghc.mod;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;

import dev.ilyapple.ghc.mod.compat.Mc;
import dev.ilyapple.ghc.mod.ui.Canvas;
import dev.ilyapple.ghc.mod.ui.CompanionScreen;
import dev.ilyapple.ghc.mod.ui.DropsPanel;
import dev.ilyapple.ghc.mod.ui.Theme;
import dev.ilyapple.ghc.core.leaderboard.Leaderboard;

/**
 * Every element of the window, in a real client: each page is opened fresh and every clickable area is clicked once
 * with real mouse input, at the place the window drew it; what changed (the window's own state, settings, flows,
 * profiles, the inventory, the clipboard) is recorded, and every text field a click shows is typed into and confirmed
 * with Enter. Then lists are scrolled, the profile menu, Escape, the inventory screen's Profit icon and panel, and the
 * HUD. The report is {@code build/run/clientGameTest/gui-report-<version>.txt}; a crash fails the run.
 * {@code ./gradlew :26.3:runClientGameTest}
 */
public class GuiGameTest implements FabricClientGameTest {
	private static final Gson GSON = new Gson();
	private final List<String> lines = new ArrayList<>();
	/** The settings as the test started, put back before each part (the Settings sweep flips every toggle). */
	private String startSettings = "";
	private int clicked;
	private int changed;
	private int typed;
	private int typedOk;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			context.waitTicks(60);
			context.runOnClient(client -> {
				String recordings = System.getProperty("ghc.test.recordings", "");
				if (!recordings.isBlank() && Files.isDirectory(Path.of(recordings))) {
					DevDemo.seed(GreenhouseCompanion.state(), Path.of(recordings), System.getProperty("ghc.test.flow"));
				}
				DevDemo.seedDropsAndWater();
				garden(true);
				startSettings = GSON.toJson(GreenhouseCompanion.settings());
			});
			if (Boolean.getBoolean("ghc.test.probe")) probe(context);
			// -Dghc.test.pages=IMPORT,FLOW runs only those pages (and skips the rest)
			String only = System.getProperty("ghc.test.pages", "");
			for (CompanionScreen.Page page : CompanionScreen.Page.values()) {
				if (!only.isBlank() && !only.contains(page.name())) continue;
				context.runOnClient(client -> restoreSettings());
				sweep(context, page);
				if (page == CompanionScreen.Page.FLOW) copyLiveLayout(context);
				if (page == CompanionScreen.Page.STAGES) stageHighlightToggle(context);
				if (page == CompanionScreen.Page.LEADERBOARD) {
					leaderboardPriceToggle(context);
					leaderboardDefaultFlow(context);
				}
			}
			context.runOnClient(client -> restoreSettings());
			if (only.isBlank() || only.contains("INVENTORY")) {
				context.runOnClient(client -> restoreSettings());
				modifiers(context);
			}
			if (only.isBlank()) {
				profileMenu(context);
				escape(context);
				context.runOnClient(client -> restoreSettings());
				inventoryPanel(context);
			}
			context.runOnClient(client -> restoreSettings());
			context.setScreen(() -> null);
			context.waitTicks(20);
			context.takeScreenshot("gui-hud");
		}
		String version = SharedConstants.getCurrentVersion().name();
		lines.addFirst("Minecraft " + version + ": " + clicked + " clicks, " + changed + " changed something, " + (clicked - changed) + " changed nothing; "
				+ typed + " text fields typed into, " + typedOk + " took the text");
		try {
			Path out = Path.of("gui-report-" + version + ".txt").toAbsolutePath();
			Files.write(out, lines, StandardCharsets.UTF_8);
			GreenhouseCompanion.LOGGER.info("[GHC] GUI test report: {}", out);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		List<String> failures = lines.stream().filter(line -> line.contains("!!")).toList();
		if (!failures.isEmpty()) throw new AssertionError("GUI regressions:\n" + String.join("\n", failures));
	}

	/** Exercise the empty Flow page and its copy button with real mouse input. */
	private void copyLiveLayout(ClientGameTestContext context) {
		ModState.Profile previous = context.computeOnClient(client -> GreenhouseCompanion.state().viewedProfile());
		ModState.Site site = context.computeOnClient(client -> {
			ModState state = GreenhouseCompanion.state();
			state.view(state.createProfile("Copy live GUI test"));
			ModState.Site result = state.site("copy-test", new net.minecraft.core.BlockPos(-5, 73, 91));
			state.markGreenhouse(result.key());
			result.greenhouse().observe(27, com.google.gson.JsonParser.parseString(
					"{\"blocks\":[\"minecraft:wheat[age=1]\"],\"entities\":[]}").getAsJsonObject(), System.currentTimeMillis());
			return result;
		});
		open(context, CompanionScreen.Page.FLOW, 0);
		context.runOnClient(client -> set(Mc.screen(), "siteKey", site.key()));
		context.waitTicks(2);
		context.takeScreenshot("gui-flow-copy-live");
		int[] button = context.computeOnClient(client -> {
			int width = client.font.width("Copy live to layout") + 12;
			return visible(hotspots("hotspots")).stream().filter(h -> h[2] == width && h[3] == 18).findFirst().orElseThrow();
		});
		clickCanvas(context, button);
		context.runOnClient(client -> {
			if (site.greenhouse().runner() == null
					|| !site.greenhouse().runner().step().schematic().inputCounts().equals(Map.of("wheat", 1)))
				throw new AssertionError("Copy live must create a layout containing growing wheat");
		});
		context.takeScreenshot("gui-flow-copied-layout");
		lines.add("FLOW copy live → saved a layout with growing wheat as a placement");
		// Copying selects Layout. Keep the same window, then switch to an unassigned greenhouse.
		context.runOnClient(client -> {
			ModState state = GreenhouseCompanion.state();
			ModState.Site unassigned = state.site("copy-test", new net.minecraft.core.BlockPos(-5, 73, -197));
			state.markGreenhouse(unassigned.key());
			unassigned.greenhouse().observe(27, com.google.gson.JsonParser.parseString(
					"{\"blocks\":[\"minecraft:carrots[age=7]\"],\"entities\":[]}").getAsJsonObject(), System.currentTimeMillis());
			set(Mc.screen(), "siteKey", unassigned.key());
		});
		context.waitTicks(3);
		context.takeScreenshot("gui-flow-unassigned-after-layout");
		lines.add("FLOW preview → switched from a copied layout to an unassigned greenhouse with a live carrot");
		context.runOnClient(client -> GreenhouseCompanion.state().view(previous));
	}

	/** A Stages row must expose the setting and change it with a real click. */
	private void stageHighlightToggle(ClientGameTestContext context) {
		context.runOnClient(client -> restoreSettings());
		open(context, CompanionScreen.Page.STAGES, 0);
		String before = context.computeOnClient(client -> GSON.toJson(GreenhouseCompanion.settings().highlightOutlines));
		int[] toggle = context.computeOnClient(client -> {
			return visible(hotspots("hotspots")).stream().filter(h -> h[0] > canvasWidth() - 120 && h[3] == 14)
					.findFirst().orElseThrow(() -> new AssertionError("Stages needs a highlight toggle on each visible row"));
		});
		clickCanvas(context, toggle);
		String after = context.computeOnClient(client -> GSON.toJson(GreenhouseCompanion.settings().highlightOutlines));
		if (before.equals(after)) throw new AssertionError("Stages highlight button must change the plant's setting");
		context.takeScreenshot("gui-stages-highlight");
		lines.add("STAGES highlight → changed an individual plant's outline");
	}

	// The pages

	/** Both price choices must be clickable on the board and retain the selected flow and interval. */
	private void leaderboardPriceToggle(ClientGameTestContext context) {
		open(context, CompanionScreen.Page.LEADERBOARD, 0);
		context.runOnClient(client -> {
			Object page = get(Mc.screen(), "leaderboard");
			set(page, "boardSelected", "devourer");
			set(page, "boardInterval", 4);
		});
		context.waitTicks(2);
		List<int[]> choices = context.computeOnClient(client -> visible(hotspots("hotspots")).stream()
				.filter(h -> h[1] == Theme.PAD + 30 + 20 && h[3] == 14).toList());
		if (choices.size() != 2) throw new AssertionError("Leaderboard needs Main and Alpha price buttons");
		for (int choice : new int[] {1, 0}) {
			clickCanvas(context, choices.get(choice));
			context.runOnClient(client -> {
				Object page = get(Mc.screen(), "leaderboard");
				Leaderboard.NpcPrices expected = choice == 0 ? Leaderboard.NpcPrices.MAIN : Leaderboard.NpcPrices.ALPHA;
				if (get(page, "boardNpcPrices") != expected) throw new AssertionError("Price button must select " + expected);
				if (!Objects.equals(get(page, "boardSelected"), "devourer") || !Objects.equals(get(page, "boardInterval"), 4)) {
					throw new AssertionError("Price selection must retain the selected flow and harvest interval");
				}
			});
			context.takeScreenshot(choice == 0 ? "gui-leaderboard-main" : "gui-leaderboard-alpha");
		}
		lines.add("LEADERBOARD prices → Alpha and Main selected by real clicks, flow and interval retained");
	}

	private void leaderboardDefaultFlow(ClientGameTestContext context) {
		open(context, CompanionScreen.Page.LEADERBOARD, 0);
		context.runOnClient(client -> set(get(Mc.screen(), "leaderboard"), "boardSelected", "jellybean_shadevine"));
		context.waitTicks(2);
		context.takeScreenshot("gui-leaderboard-default");
		int[] add = context.computeOnClient(client -> visible(hotspots("hotspots")).stream()
				.filter(h -> h[0] > canvasWidth() * 0.6 && h[1] > canvasHeight() - 40 && h[3] == Theme.BUTTON_H)
				.findFirst().orElseThrow(() -> new AssertionError("Default flow needs an import button")));
		clickCanvas(context, add);
		context.runOnClient(client -> {
			ModState.FlowFile imported = GreenhouseCompanion.state().library().stream()
					.filter(f -> f.name().equals("Jellybean + Shadevine")).findFirst().orElseThrow();
			try (var in = GuiGameTest.class.getResourceAsStream(ModState.TEST_FLOW)) {
				JsonElement expected = com.google.gson.JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
				if (!expected.equals(com.google.gson.JsonParser.parseString(imported.text()))) throw new AssertionError("Default import must preserve all three plots and both phases");
			} catch (IOException e) {
				throw new AssertionError(e);
			}
		});
		lines.add("LEADERBOARD default → imported Jellybean + Shadevine with all three plots and phases intact");
	}

	private void sweep(ClientGameTestContext context, CompanionScreen.Page page) {
		open(context, page, 0);
		context.takeScreenshot("gui-" + page.name().toLowerCase(java.util.Locale.ROOT));
		List<String> baseline = context.computeOnClient(client -> boxNames());
		for (String name : baseline) typeInto(context, page, 0, name, page + " · field \"" + name + "\"");
		int rounds = page == CompanionScreen.Page.SETTINGS ? 5 : 1;
		for (int round = 0; round < rounds; round++) {
			int scroll = round * 240;
			open(context, page, scroll);
			int count = context.computeOnClient(client -> visible(hotspots("hotspots")).size());
			for (int i = 0; i < count; i++) {
				open(context, page, scroll);
				int index = i;
				int[] spot = context.computeOnClient(client -> {
					List<int[]> now = visible(hotspots("hotspots"));
					return index < now.size() ? now.get(index) : null;
				});
				if (spot == null) break;
				Map<String, String> before = context.computeOnClient(client -> state());
				clickCanvas(context, spot);
				Map<String, String> after = context.computeOnClient(client -> state());
				record(page + (scroll > 0 ? " (scrolled " + scroll + ")" : "") + " · #" + i + " at " + spot[0] + "," + spot[1] + " " + spot[2] + "x" + spot[3], before, after);
				// Text fields this click showed: type, then Enter
				for (String name : context.computeOnClient(client -> boxNames())) {
					if (!baseline.contains(name)) typeIntoCurrent(context, name, page + " #" + i + " → field \"" + name + "\"");
				}
			}
		}
		// Right clicks where they mean something (back a profile or flow plot, back to the sack count)
		if (page == CompanionScreen.Page.OVERVIEW || page == CompanionScreen.Page.FLOW || page == CompanionScreen.Page.INVENTORY) {
			open(context, page, 0);
			int right = context.computeOnClient(client -> visible(hotspots("hotspots")).size());
			for (int i = 0; i < right; i++) {
				open(context, page, 0);
				int index = i;
				int[] spot = context.computeOnClient(client -> {
					List<int[]> now = visible(hotspots("hotspots"));
					return index < now.size() ? now.get(index) : null;
				});
				if (spot == null) break;
				Map<String, String> before = context.computeOnClient(client -> state());
				clickCanvas(context, spot, InputConstants.MOUSE_BUTTON_RIGHT);
				record(page + " · right click #" + i + " at " + spot[0] + "," + spot[1] + " " + spot[2] + "x" + spot[3], before, context.computeOnClient(client -> state()));
			}
		}
		open(context, page, 0);
		Map<String, String> before = context.computeOnClient(client -> state());
		double[] middle = context.computeOnClient(client -> toWindow(canvasWidth() * 0.4, canvasHeight() * 0.5));
		context.getInput().setCursorPos(middle[0], middle[1]);
		context.getInput().scroll(-3);
		context.waitTicks(2);
		record(page + " · scroll the list", before, context.computeOnClient(client -> state()));
	}

	/** An inventory count's + with no modifier, Shift and Ctrl: 1, 10 and 100. */
	private void modifiers(ClientGameTestContext context) {
		String[] modes = {"plain", "shift", "control"};
		long[] expected = {1, 10, 100};
		for (int m = 0; m < modes.length; m++) {
			open(context, CompanionScreen.Page.INVENTORY, 0);
			// The first row's + (its − comes first)
			int[] plus = context.computeOnClient(client -> visible(hotspots("hotspots")).stream().filter(h -> h[2] == 16 && h[3] == 14).skip(1).findFirst().orElse(null));
			if (plus == null) {
				lines.add("INVENTORY + · !! no + button");
				return;
			}
			long before = context.computeOnClient(client -> adjustments());
			// Fabric's test input always clicks with no modifiers, so the click goes to the window directly, with this
			// version's own Shift and Ctrl bits (26.3 numbers them differently)
			int modifiers = m == 1 ? InputConstants.MOD_SHIFT : m == 2 ? InputConstants.MOD_CONTROL : 0;
			context.runOnClient(client -> {
				Window window = client.getWindow();
				float scale = Canvas.scale(window.getGuiScaledWidth(), window.getGuiScaledHeight());
				var event = new net.minecraft.client.input.MouseButtonEvent((plus[0] + plus[2] / 2.0) * scale, (plus[1] + plus[3] / 2.0) * scale,
						new net.minecraft.client.input.MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, modifiers));
				Mc.screen().mouseClicked(event, false);
				Mc.screen().mouseReleased(event);
			});
			context.waitTicks(2);
			long delta = context.computeOnClient(client -> adjustments()) - before;
			clicked++;
			if (delta != 0) changed++;
			lines.add("INVENTORY + with " + modes[m] + " → adjusts by " + delta + (delta == expected[m] ? "" : " !! expected " + expected[m]));
		}
	}

	/** The sum of every inventory adjustment. */
	private static long adjustments() {
		Object map = get(GreenhouseCompanion.state().inventory(), "adjustments");
		long sum = 0;
		if (map instanceof Map<?, ?> m) for (Object v : m.values()) sum += ((Number) v).longValue();
		return sum;
	}

	private void profileMenu(ClientGameTestContext context) {
		openMenu(context);
		context.takeScreenshot("gui-profile-menu");
		int count = context.computeOnClient(client -> visible(hotspots("menuHotspots")).size());
		for (int i = 0; i < count; i++) {
			openMenu(context);
			int index = i;
			int[] spot = context.computeOnClient(client -> {
				List<int[]> now = visible(hotspots("menuHotspots"));
				return index < now.size() ? now.get(index) : null;
			});
			if (spot == null) break;
			Map<String, String> before = context.computeOnClient(client -> state());
			clickCanvas(context, spot);
			record("PROFILE MENU · #" + i + " at " + spot[0] + "," + spot[1], before, context.computeOnClient(client -> state()));
			for (String name : context.computeOnClient(client -> boxNames())) {
				if (name.equals("Profile name")) typeIntoCurrent(context, name, "PROFILE MENU #" + i + " → field \"" + name + "\"");
			}
		}
	}

	private void openMenu(ClientGameTestContext context) {
		open(context, CompanionScreen.Page.OVERVIEW, 0);
		context.runOnClient(client -> set(Mc.screen(), "profileMenu", true));
		context.waitTicks(2);
	}

	private void escape(ClientGameTestContext context) {
		open(context, CompanionScreen.Page.SETTINGS, 0);
		Map<String, String> before = context.computeOnClient(client -> state());
		context.getInput().pressKey(InputConstants.KEY_ESCAPE);
		context.waitTicks(2);
		Map<String, String> after = context.computeOnClient(client -> state());
		record("ESCAPE closes the window", before, after);
		if (!"null".equals(after.get("screen"))) lines.add("  !! the window stayed open after Escape: " + after.get("screen"));
	}

	/** The Profit icon on the inventory screen (Garden only) and every area of its panel. */
	private void inventoryPanel(ClientGameTestContext context) {
		openInventory(context, false);
		context.takeScreenshot("gui-inventory-icon");
		int[] icon = context.computeOnClient(client -> first(areas()));
		if (icon == null) {
			lines.add("INVENTORY · !! no Profit icon on the inventory screen");
			return;
		}
		Map<String, String> before = context.computeOnClient(client -> state());
		clickGui(context, icon);
		record("INVENTORY · the Profit icon", before, context.computeOnClient(client -> state()));
		context.takeScreenshot("gui-inventory-panel");
		int count = context.computeOnClient(client -> areas().size());
		for (int i = 1; i < count; i++) {
			openInventory(context, true);
			int index = i;
			int[] area = context.computeOnClient(client -> {
				List<int[]> now = areas();
				return index < now.size() ? now.get(index) : null;
			});
			if (area == null) break;
			before = context.computeOnClient(client -> state());
			clickGui(context, area);
			record("INVENTORY PANEL · #" + i + " at " + area[0] + "," + area[1], before, context.computeOnClient(client -> state()));
		}
	}

	private void openInventory(ClientGameTestContext context, boolean panelOpen) {
		context.setScreen(() -> new InventoryScreen(Minecraft.getInstance().player));
		context.runOnClient(client -> setStatic(DropsPanel.class, "open", panelOpen));
		context.waitTicks(3);
	}

	/** Why a text field doesn't focus: what sits under it, and a click handed straight to the window. */
	private void probe(ClientGameTestContext context) {
		open(context, CompanionScreen.Page.IMPORT, 0);
		lines.add("PROBE " + context.computeOnClient(client -> {
			Screen screen = Mc.screen();
			StringBuilder out = new StringBuilder();
			int[] box = boxRect("Name");
			if (box == null) return "no Name box";
			double cx = box[0] + box[2] / 2.0;
			double cy = box[1] + box[3] / 2.0;
			Window window = client.getWindow();
			float scale = Canvas.scale(window.getGuiScaledWidth(), window.getGuiScaledHeight());
			out.append("canvas ").append(cx).append(",").append(cy).append(" scale ").append(scale).append("; children:");
			for (GuiEventListener child : screen.children()) {
				out.append(" ").append(child.getClass().getSimpleName());
				if (child instanceof EditBox e) out.append("(").append(e.getMessage().getString()).append(e.isMouseOver(cx, cy) ? ",over" : "").append(")");
			}
			out.append("; childAt=").append(screen.getChildAt(cx, cy).map(c -> c.getClass().getSimpleName() + (c instanceof EditBox e ? ":" + e.getMessage().getString() : "")).orElse("none"));
			boolean handled = screen.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(cx * scale, cy * scale, new net.minecraft.client.input.MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), false);
			GuiEventListener focused = screen.getFocused();
			out.append("; direct click handled=").append(handled).append(" focused=").append(focused instanceof EditBox f ? f.getMessage().getString() : String.valueOf(focused));
			screen.mouseReleased(new net.minecraft.client.input.MouseButtonEvent(cx * scale, cy * scale, new net.minecraft.client.input.MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)));
			focused = screen.getFocused();
			out.append(" after release=").append(focused instanceof EditBox f ? f.getMessage().getString() : String.valueOf(focused));
			return out.toString();
		}));
		context.waitTicks(2);
		lines.add("PROBE after 2 ticks: focused=" + context.computeOnClient(client -> {
			GuiEventListener focused = Mc.screen().getFocused();
			return focused instanceof EditBox f ? f.getMessage().getString() : String.valueOf(focused);
		}));
	}

	// Typing

	private void typeInto(ClientGameTestContext context, CompanionScreen.Page page, int scroll, String name, String label) {
		open(context, page, scroll);
		typeIntoCurrent(context, name, label);
	}

	private void typeIntoCurrent(ClientGameTestContext context, String name, String label) {
		int[] box = context.computeOnClient(client -> boxRect(name));
		if (box == null) return;
		typed++;
		clickCanvas(context, box);
		String focus = context.computeOnClient(client -> {
			Screen screen = Mc.screen();
			GuiEventListener focused = screen == null ? null : screen.getFocused();
			return "focused " + (focused instanceof EditBox f ? "\"" + f.getMessage().getString() + "\"" : String.valueOf(focused)) + ", box at " + box[0] + "," + box[1] + " " + box[2] + "x" + box[3];
		});
		context.getInput().typeChars("12");
		context.waitTicks(1);
		String value = context.computeOnClient(client -> boxValue(name));
		boolean took = value != null && value.contains("12");
		if (took) typedOk++;
		Map<String, String> before = context.computeOnClient(client -> state());
		context.getInput().pressKey(InputConstants.KEY_RETURN);
		context.waitTicks(2);
		lines.add(label + " [" + focus + "]: typed \"12\" → " + (took ? "took it (\"" + value + "\")" : "!! didn't take it (\"" + value + "\")") + "; Enter → "
				+ diff(before, context.computeOnClient(client -> state())));
	}

	// Input

	private void open(ClientGameTestContext context, CompanionScreen.Page page, int settingsScroll) {
		context.setScreen(() -> new CompanionScreen(null, page));
		context.waitTicks(2);
		if (settingsScroll > 0) {
			context.runOnClient(client -> set(get(Mc.screen(), "settingsPage"), "settingsScroll", settingsScroll));
			context.waitTicks(2);
		}
	}

	private void clickCanvas(ClientGameTestContext context, int[] rect) {
		clickCanvas(context, rect, InputConstants.MOUSE_BUTTON_LEFT);
	}

	private void clickCanvas(ClientGameTestContext context, int[] rect, int button) {
		double[] at = context.computeOnClient(client -> toWindow(rect[0] + rect[2] / 2.0, rect[1] + rect[3] / 2.0));
		context.getInput().setCursorPos(at[0], at[1]);
		context.getInput().pressMouse(button);
		context.waitTicks(2);
	}

	private void clickGui(ClientGameTestContext context, int[] rect) {
		double[] at = context.computeOnClient(client -> {
			Window window = client.getWindow();
			double k = (double) window.getScreenWidth() / window.getGuiScaledWidth();
			return new double[] {(rect[0] + rect[2] / 2.0) * k, (rect[1] + rect[3] / 2.0) * k};
		});
		context.getInput().setCursorPos(at[0], at[1]);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(2);
	}

	/** Window coordinates of a point on the window's canvas ({@link Canvas}). */
	private static double[] toWindow(double x, double y) {
		Window window = Minecraft.getInstance().getWindow();
		float scale = Canvas.scale(window.getGuiScaledWidth(), window.getGuiScaledHeight());
		double k = (double) window.getScreenWidth() / window.getGuiScaledWidth();
		return new double[] {x * scale * k, y * scale * k};
	}

	private static double canvasWidth() {
		Window window = Minecraft.getInstance().getWindow();
		return window.getGuiScaledWidth() / Canvas.scale(window.getGuiScaledWidth(), window.getGuiScaledHeight());
	}

	private static double canvasHeight() {
		Window window = Minecraft.getInstance().getWindow();
		return window.getGuiScaledHeight() / Canvas.scale(window.getGuiScaledWidth(), window.getGuiScaledHeight());
	}

	// Reading the window

	/** The window's click areas ({@code hotspots} or {@code menuHotspots}) as x, y, w, h on its canvas. */
	private static List<int[]> hotspots(String field) {
		List<int[]> out = new ArrayList<>();
		Object list = get(Mc.screen(), field);
		if (!(list instanceof List<?> spots)) return out;
		for (Object spot : spots) out.add(rect(spot));
		return out;
	}

	/** The ones whose middle is on screen. */
	private static List<int[]> visible(List<int[]> spots) {
		double w = canvasWidth();
		double h = canvasHeight();
		List<int[]> out = new ArrayList<>();
		for (int[] spot : spots) {
			double cx = spot[0] + spot[2] / 2.0;
			double cy = spot[1] + spot[3] / 2.0;
			if (cx >= 0 && cx < w && cy >= 0 && cy < h && spot[2] > 0 && spot[3] > 0) out.add(spot);
		}
		return out;
	}

	private static List<int[]> areas() {
		List<int[]> out = new ArrayList<>();
		Object list = getStatic(DropsPanel.class, "areas");
		if (list instanceof List<?> areas) for (Object area : areas) out.add(rect(area));
		return out;
	}

	private static int @org.jspecify.annotations.Nullable [] first(List<int[]> list) {
		return list.isEmpty() ? null : list.getFirst();
	}

	private static int[] rect(Object record) {
		try {
			int[] r = new int[4];
			String[] names = {"x", "y", "w", "h"};
			for (int i = 0; i < 4; i++) {
				Method m = record.getClass().getDeclaredMethod(names[i]);
				m.setAccessible(true);
				r[i] = (int) m.invoke(record);
			}
			return r;
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	private static List<EditBox> boxes() {
		List<EditBox> out = new ArrayList<>();
		Screen screen = Mc.screen();
		if (screen == null) return out;
		for (GuiEventListener child : screen.children()) {
			if (child instanceof EditBox box && box.visible && box.getY() >= 0) out.add(box);
		}
		return out;
	}

	private static List<String> boxNames() {
		List<String> names = new ArrayList<>();
		for (EditBox box : boxes()) names.add(box.getMessage().getString());
		return names;
	}

	private static int @org.jspecify.annotations.Nullable [] boxRect(String name) {
		for (EditBox box : boxes()) {
			if (box.getMessage().getString().equals(name)) return new int[] {box.getX(), box.getY(), box.getWidth(), box.getHeight()};
		}
		return null;
	}

	private static @org.jspecify.annotations.Nullable String boxValue(String name) {
		for (EditBox box : boxes()) if (box.getMessage().getString().equals(name)) return box.getValue();
		return null;
	}

	// What changed

	/** Everything a click could change, as key → value. */
	private static Map<String, String> state() {
		Map<String, String> state = new LinkedHashMap<>();
		Minecraft client = Minecraft.getInstance();
		Screen screen = Mc.screen();
		state.put("screen", screen == null ? "null" : screen.getClass().getSimpleName());
		if (screen instanceof CompanionScreen) {
			fields(state, "window.", screen, CompanionScreen.class);
			for (String page : List.of("flow", "leaderboard", "settingsPage")) {
				Object view = get(screen, page);
				if (view != null) fields(state, "window." + page + ".", view, view.getClass());
			}
		}
		JsonObject settings = GSON.toJsonTree(GreenhouseCompanion.settings()).getAsJsonObject();
		for (Map.Entry<String, JsonElement> entry : settings.entrySet()) state.put("settings." + entry.getKey(), entry.getValue().toString());
		ModState mod = GreenhouseCompanion.state();
		List<String> library = new ArrayList<>();
		for (ModState.FlowFile file : mod.library()) library.add(file.name() + "#" + file.text().hashCode());
		state.put("flows", library.toString());
		List<String> profiles = new ArrayList<>();
		for (ModState.Profile profile : mod.profiles()) profiles.add(profile.name() + "→" + profile.flowName());
		state.put("profiles", profiles.toString());
		state.put("viewedProfile", mod.viewedProfile().name());
		// Which profile each greenhouse is in, its assignments, notes and the rest of the mod's own state
		fields(state, "mod.", mod, ModState.class);
		fields(state, "inventory.", mod.inventory(), mod.inventory().getClass());
		fields(state, "profitPanel.", null, DropsPanel.class);
		state.put("clipboard", Objects.toString(client.keyboardHandler.getClipboard()));
		return state;
	}

	private static void fields(Map<String, String> into, String prefix, @org.jspecify.annotations.Nullable Object target, Class<?> type) {
		for (Field field : type.getDeclaredFields()) {
			boolean isStatic = Modifier.isStatic(field.getModifiers());
			if ((target == null) != isStatic || field.getName().contains("otspot") || field.getName().equals("areas") || field.getName().endsWith("Ms")) continue;
			if (isStatic && Modifier.isFinal(field.getModifiers())) continue;
			try {
				field.setAccessible(true);
				Object value = field.get(target);
				String text;
				if (value instanceof EditBox box) text = "box:" + box.getValue();
				else if (value instanceof Map<?, ?> map) text = "map:" + map.size() + "#" + map.toString().hashCode();
				else if (value instanceof Collection<?> list) text = "list:" + list.size();
				else if (value == null || value instanceof Number || value instanceof Boolean || value instanceof CharSequence || value instanceof Enum<?>) text = String.valueOf(value);
				else continue;
				into.put(prefix + field.getName(), text);
			} catch (ReflectiveOperationException | RuntimeException e) {
				// Not readable: left out
			}
		}
	}

	private void record(String label, Map<String, String> before, Map<String, String> after) {
		clicked++;
		String diff = diff(before, after);
		if (!diff.equals("nothing")) changed++;
		lines.add(label + " → " + diff);
	}

	private static String diff(Map<String, String> before, Map<String, String> after) {
		List<String> keys = new ArrayList<>();
		for (String key : after.keySet()) {
			if (!Objects.equals(before.get(key), after.get(key))) keys.add(key + ": " + shorten(before.get(key)) + " → " + shorten(after.get(key)));
		}
		return keys.isEmpty() ? "nothing" : String.join("; ", keys);
	}

	private static String shorten(@org.jspecify.annotations.Nullable String value) {
		if (value == null) return "∅";
		return value.length() > 60 ? value.substring(0, 57) + "…" : value;
	}

	// Reflection

	private static @org.jspecify.annotations.Nullable Object get(@org.jspecify.annotations.Nullable Object target, String name) {
		if (target == null) return null;
		try {
			Field field = target.getClass().getDeclaredField(name);
			field.setAccessible(true);
			return field.get(target);
		} catch (ReflectiveOperationException e) {
			return null;
		}
	}

	private static void set(@org.jspecify.annotations.Nullable Object target, String name, Object value) {
		if (target == null) return;
		try {
			Field field = target.getClass().getDeclaredField(name);
			field.setAccessible(true);
			field.set(target, value);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	private static @org.jspecify.annotations.Nullable Object getStatic(Class<?> type, String name) {
		try {
			Field field = type.getDeclaredField(name);
			field.setAccessible(true);
			return field.get(null);
		} catch (ReflectiveOperationException e) {
			return null;
		}
	}

	private static void setStatic(Class<?> type, String name, Object value) {
		try {
			Field field = type.getDeclaredField(name);
			field.setAccessible(true);
			field.set(null, value);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	private void restoreSettings() {
		Settings start = GSON.fromJson(startSettings, Settings.class);
		Settings live = GreenhouseCompanion.settings();
		for (Field field : Settings.class.getDeclaredFields()) {
			if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) continue;
			try {
				field.setAccessible(true);
				field.set(live, field.get(start));
			} catch (ReflectiveOperationException e) {
				throw new RuntimeException(e);
			}
		}
	}

	/** The Garden, as Hypixel's location packet would say (the Profit icon and the HUD only show there). */
	private static void garden(boolean in) {
		setStatic(Location.class, "apiReady", in);
		setStatic(Location.class, "apiMap", in ? "Garden" : null);
	}
}
