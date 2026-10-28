package dev.ilyapple.ghc.mod.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;

import dev.ilyapple.ghc.core.Greenhouse;
import dev.ilyapple.ghc.core.Growth;
import dev.ilyapple.ghc.core.LookTable;
import dev.ilyapple.ghc.core.ModelBook;
import dev.ilyapple.ghc.core.Plants;
import dev.ilyapple.ghc.core.StageSet;
import dev.ilyapple.ghc.core.StageTracker;
import dev.ilyapple.ghc.core.flow.Census;
import dev.ilyapple.ghc.core.flow.Estimator;
import dev.ilyapple.ghc.core.flow.ExitEvaluator;
import dev.ilyapple.ghc.core.flow.FlowCheck;
import dev.ilyapple.ghc.core.flow.FlowRunner;
import dev.ilyapple.ghc.core.flow.Flows;
import dev.ilyapple.ghc.core.schematic.Schematic;
import dev.ilyapple.ghc.core.leaderboard.Leaderboard;
import dev.ilyapple.ghc.core.schematic.SkyblockerCrops;
import dev.ilyapple.ghc.mod.GreenhouseCompanion;
import dev.ilyapple.ghc.mod.JellybeanView;
import dev.ilyapple.ghc.mod.ModState;
import dev.ilyapple.ghc.mod.Settings;

/**
 * The mod's window: a sidebar of pages (Waypointer's layout) on a translucent backdrop.
 * <ul>
 * <li><b>Overview</b>: every greenhouse, what it follows, where it is in its flow.</li>
 * <li><b>Flow</b>: one greenhouse's steps, the selected step's layout (or what grows, with stages) and its exits,
 *     each ticked off live, in the simulator's words.</li>
 * <li><b>Flows</b>: paste and import SkyShards exports, the library of imported files, and the checks.</li>
 * <li><b>Inventory</b>, <b>Stages</b> (where stage looks come from and what is still unknown) and <b>Settings</b>.</li>
 * </ul>
 */
public final class CompanionScreen extends Screen {
	public enum Page {
		OVERVIEW("Overview", "◉", "Your greenhouses at a glance"),
		FLOW("Flow", "❖", "Steps, layout and exits of one greenhouse"),
		IMPORT("Flows", "⇩", "Import SkyShards simulator exports"),
		LEADERBOARD("Leaderboard", "★", "Every mutation's plot, ranked by the simulator (run locally)"),
		DROPS("Profit", "✿", "Every greenhouse harvest, what it dropped and what it's worth"),
		INVENTORY("Inventory", "▤", "Sacks plus your own adjustments"),
		STAGES("Stages", "✦", "Where plant stages come from"),
		SETTINGS("Settings", "⚙", "Everything the mod does, on or off");

		final String label;
		final String icon;
		final String subtitle;

		Page(String label, String icon, String subtitle) {
			this.label = label;
			this.icon = icon;
			this.subtitle = subtitle;
		}
	}

	private record Hotspot(int x, int y, int w, int h, Consumer<MouseButtonEvent> action) {
		boolean contains(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	private static Page lastPage = Page.OVERVIEW;

	private final @Nullable Screen parent;
	private final List<Hotspot> hotspots = new ArrayList<>();
	/** The profile dropdown's own click areas, drawn over everything and checked before anything else. */
	private final List<Hotspot> menuHotspots = new ArrayList<>();
	private boolean drawingMenu;
	private boolean profileMenu;
	private boolean renamingProfile;
	private @Nullable EditBox profileBox;
	/** A two-click confirmation in progress ("forget:<key>", "inactive", "profile", "delete:<file>") and when it began. */
	private @Nullable String confirm;
	private long confirmMs;

	/** A typed exit amount on the Flow page: the box and the condition field it edits. */
	private record AmountField(EditBox box, int plotId, String stepId, int exitIndex, int conditionIndex, String field, int value) {}

	private final Map<String, AmountField> amountBoxes = new LinkedHashMap<>();
	private @Nullable String amountsBuiltFor;
	private Page page;
	private int siteIndex;
	private @Nullable String siteKey;
	private @Nullable Integer selectedStep;
	private boolean live = true;
	private int stepScroll;
	private int inventoryScroll;
	private int libraryScroll;
	private int settingsScroll;
	private int stagesScroll;
	private boolean debugOpen;
	private int boardScroll;
	private int boardDetailScroll;
	private Leaderboard.Mode boardMode = Leaderboard.Mode.FLOW;
	private Leaderboard.Sort boardSort = Leaderboard.Sort.COINS;
	private int boardInterval = 1;
	/** The board's numbers for this many plots (the simulations ran on one; 3 by default, like the user's greenhouse). */
	private int boardPlots = 3;
	private @Nullable String boardSelected;
	private String boardStatus = "";
	private int renaming = -1;
	private String importStatus = "";
	private int importColor = Theme.MUTED;
	private @Nullable EditBox search;
	/** The inventory item whose count is being typed, and its box. */
	private @Nullable String countItem;
	private @Nullable EditBox countBox;
	private @Nullable EditBox nameBox;
	private @Nullable EditBox renameBox;
	private @Nullable MultiLineEditBox importBox;
	private String importDraft = "";
	/** The Flows page imports one layout link per plot instead of a flows JSON. */
	private static boolean importLinks;
	private final List<EditBox> linkBoxes = new ArrayList<>();
	/** What's typed in each plot's link box, kept across rebuilds (3 plots to start, like the user's greenhouse). */
	private static final List<String> linkDrafts = new ArrayList<>(List.of("", "", ""));
	/** Each typed link decoded once, not every frame. */
	private final Map<String, java.util.Optional<Schematic>> linkCache = new HashMap<>();
	private Greenhouse.@Nullable Status status;
	/** Canvas scale (see {@link Canvas}): {@code width} and {@code height} are canvas units, the pose is scaled by it. */
	private float ui = 1f;
	private long statusMs;
	private @Nullable String statusSite;

	public CompanionScreen(@Nullable Screen parent, @Nullable Page page) {
		super(Component.literal("Greenhouse Companion"));
		this.parent = parent;
		this.page = page == null ? lastPage : page;
		ModState.Site here = GreenhouseCompanion.currentSite();
		if (here != null) {
			siteKey = here.key();
			// The window opens on the profile of the greenhouse you stand in
			state().view(state().profileOf(here));
		}
	}

	private void addHotspot(Hotspot hotspot) {
		(drawingMenu ? menuHotspots : hotspots).add(hotspot);
	}

	private boolean confirming(String what) {
		return what.equals(confirm) && System.currentTimeMillis() - confirmMs < 4_000;
	}

	/** Runs {@code action} on the second click within 4 s; the first click only arms it. */
	private void confirmed(String what, Runnable action) {
		if (confirming(what)) {
			confirm = null;
			action.run();
		} else {
			confirm = what;
			confirmMs = System.currentTimeMillis();
		}
	}

	private ModState state() {
		return GreenhouseCompanion.state();
	}

	private Settings settings() {
		return GreenhouseCompanion.settings();
	}

	private int contentX() {
		return Theme.SIDEBAR_W + Theme.PAD;
	}

	private int contentW() {
		return width - contentX() - Theme.PAD;
	}

	private int contentTop() {
		return Theme.PAD + 30;
	}

	/** Lays the window out on the canvas: at least {@link Canvas#MIN_WIDTH} x {@link Canvas#MIN_HEIGHT} units. */
	private void fitCanvas() {
		int guiWidth = minecraft.getWindow().getGuiScaledWidth();
		int guiHeight = minecraft.getWindow().getGuiScaledHeight();
		ui = Canvas.scale(guiWidth, guiHeight);
		width = (int) Math.ceil(guiWidth / ui);
		height = (int) Math.ceil(guiHeight / ui);
	}

	private double canvas(double screen) {
		return screen / ui;
	}

	/** Tooltips are drawn after the window, unscaled, so they take screen coordinates. */
	private void tooltip(GuiGraphicsExtractor g, List<Component> lines, int mouseX, int mouseY) {
		g.setComponentTooltipForNextFrame(font, lines, Math.round(mouseX * ui), Math.round(mouseY * ui));
	}

	@Override
	protected void init() {
		fitCanvas();
		if (importBox != null) importDraft = importBox.getValue();
		for (int i = 0; i < linkBoxes.size() && i < linkDrafts.size(); i++) linkDrafts.set(i, linkBoxes.get(i).getValue());
		linkBoxes.clear();
		// Typing a count rebuilds the page: keep the search
		String searched = search == null ? "" : search.getValue();
		search = null;
		nameBox = null;
		renameBox = null;
		importBox = null;
		profileBox = null;
		amountBoxes.clear();
		amountsBuiltFor = null;
		int x = contentX();
		int w = contentW();
		if (renamingProfile) {
			profileBox = new EditBox(font, 0, -100, 150, 14, Component.literal("Profile name"));
			profileBox.setValue(state().viewedProfile().name());
			profileBox.setMaxLength(40);
			addRenderableWidget(profileBox);
			setFocused(profileBox);
		}
		if (page == Page.FLOW) buildAmountBoxes();
		countBox = null;
		if (page == Page.INVENTORY) {
			search = new EditBox(font, x + w - 140, Theme.PAD + 4, 140, 16, Component.literal("Search"));
			search.setHint(Component.literal("Search plants…"));
			search.setValue(searched);
			addRenderableWidget(search);
			if (countItem != null) {
				countBox = new EditBox(font, 0, -100, 60, 14, Component.literal("Count"));
				countBox.setMaxLength(12);
				countBox.setValue(Integer.toString(state().inventory().count(countItem)));
				addRenderableWidget(countBox);
				setFocused(countBox);
				countBox.setFocused(true);
			}
		} else if (page == Page.IMPORT) {
			int right = importColumnX();
			int rw = x + w - right;
			int modeTop = contentTop() + 10 + smallLines(importHelp(), rw) * 5 + 4;
			int nameTop = modeTop + 18;
			nameBox = new EditBox(font, right, nameTop, rw, 16, Component.literal("Name"));
			nameBox.setHint(Component.literal("Name (optional)"));
			nameBox.setMaxLength(60);
			addRenderableWidget(nameBox);
			int boxTop = nameTop + 20;
			if (importLinks) {
				for (int i = 0; i < linkDrafts.size(); i++) {
					EditBox box = new EditBox(font, right + LINK_LABEL_W, boxTop + i * LINK_ROW_H, rw - LINK_LABEL_W - 32, 14, Component.literal("Plot " + (i + 1)));
					box.setMaxLength(20_000);
					box.setHint(Component.literal("Designer or share link"));
					box.setValue(linkDrafts.get(i));
					addRenderableWidget(box);
					linkBoxes.add(box);
				}
			} else {
				importBox = MultiLineEditBox.builder().setX(right).setY(boxTop).setPlaceholder(Component.literal(
						"Paste the simulator's Export (flows JSON), or SkyShards / SkyMutations layout links, one per line"))
						.build(font, rw, Math.max(30, height - boxTop - Theme.PAD - Theme.BUTTON_H - 16), Component.literal("Flows"));
				importBox.setCharacterLimit(400_000);
				importBox.setValue(importDraft);
				addRenderableWidget(importBox);
			}
			if (renaming >= 0 && renaming < state().library().size()) {
				renameBox = new EditBox(font, x + 8, 0, importColumnX() - x - 90, 14, Component.literal("Rename"));
				renameBox.setValue(state().library().get(renaming).name());
				renameBox.setMaxLength(60);
				addRenderableWidget(renameBox);
				setFocused(renameBox);
			}
		}
	}

	private int importColumnX() {
		return contentX() + Math.max(170, contentW() * 46 / 100);
	}

	@Override
	public void onClose() {
		lastPage = page;
		state().save();
		settings().save();
		minecraft.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		g.fill(0, 0, g.guiWidth(), g.guiHeight(), Theme.glass(Theme.BACKDROP));
	}

	private void go(Page target) {
		if (page == target) return;
		page = target;
		lastPage = target;
		renaming = -1;
		countItem = null;
		rebuildWidgets();
	}

	// Greenhouses

	/** The greenhouses of the profile the window shows, the one you stand in first. */
	private List<ModState.Site> sites() {
		List<ModState.Site> sites = new ArrayList<>(state().sites(state().viewedProfile()));
		ModState.Site current = GreenhouseCompanion.currentSite();
		sites.sort(Comparator.comparing((ModState.Site site) -> !site.equals(current)).thenComparingInt(ModState.Site::plotNumber));
		return sites;
	}

	private ModState.@Nullable Site site() {
		List<ModState.Site> sites = sites();
		if (sites.isEmpty()) return null;
		if (siteKey != null) {
			for (int i = 0; i < sites.size(); i++) {
				if (sites.get(i).key().equals(siteKey)) {
					siteIndex = i;
					return sites.get(i);
				}
			}
		}
		siteIndex = Math.floorMod(siteIndex, sites.size());
		siteKey = sites.get(siteIndex).key();
		return sites.get(siteIndex);
	}

	private void moveSite(int delta) {
		List<ModState.Site> sites = sites();
		if (sites.isEmpty()) return;
		site();
		siteIndex = Math.floorMod(siteIndex + delta, sites.size());
		siteKey = sites.get(siteIndex).key();
		selectedStep = null;
		refresh();
	}

	private Greenhouse.Status status(ModState.Site site) {
		long now = System.currentTimeMillis();
		if (status == null || now - statusMs > 500 || !site.key().equals(statusSite)) {
			status = site.greenhouse().status(8);
			statusMs = now;
			statusSite = site.key();
		}
		return status;
	}

	private void refresh() {
		status = null;
	}

	// Frame

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int screenX, int screenY, float delta) {
		int mouseX = (int) canvas(screenX);
		int mouseY = (int) canvas(screenY);
		g.pose().pushMatrix();
		g.pose().scale(ui, ui);
		try {
			extractCanvas(g, mouseX, mouseY, delta);
		} finally {
			g.pose().popMatrix();
		}
	}

	private void extractCanvas(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		hotspots.clear();
		sidebar(g, mouseX, mouseY);
		int x = contentX();
		g.text(font, page.label, x, Theme.PAD + 2, Theme.TEXT_BRIGHT, false);
		Theme.small(g, font, page.subtitle, x, Theme.PAD + 14, Theme.MUTED);
		g.fill(x, Theme.PAD + 24, x + contentW(), Theme.PAD + 25, Theme.BORDER);
		switch (page) {
			case OVERVIEW -> overview(g, mouseX, mouseY);
			case FLOW -> flowPage(g, mouseX, mouseY);
			case IMPORT -> importPage(g, mouseX, mouseY);
			case LEADERBOARD -> leaderboardPage(g, mouseX, mouseY);
			case DROPS -> DropsPanel.render(g, font, contentX(), contentTop(), Math.min(contentW(), 560), height - contentTop() - Theme.PAD, mouseX, mouseY,
					(hx, hy, hw, hh, action) -> addHotspot(new Hotspot(hx, hy, hw, hh, event -> action.run())), true);
			case INVENTORY -> inventoryPage(g, mouseX, mouseY);
			case STAGES -> stagesPage(g, mouseX, mouseY);
			case SETTINGS -> settingsPage(g, mouseX, mouseY);
		}
		super.extractRenderState(g, mouseX, mouseY, delta);
		menuHotspots.clear();
		if (profileMenu) {
			drawingMenu = true;
			try {
				profileMenu(g, mouseX, mouseY);
			} finally {
				drawingMenu = false;
			}
		}
	}

	// Profiles

	private static final int MENU_X = 6;
	private static final int MENU_Y = 56;
	private static final int MENU_W = 226;

	/** The sidebar's profile dropdown: which set of greenhouses (and their flows) the window shows. */
	private void profilePill(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		ModState.Profile profile = state().viewedProfile();
		int x = 6;
		int y = 36;
		int w = Theme.SIDEBAR_W - 12;
		int h = 16;
		boolean over = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		Theme.card(g, x, y, w, h, profileMenu || over ? Theme.CARD_HOVER : Theme.CARD, profileMenu ? Theme.alpha(Theme.accent(), 0xAA) : Theme.BORDER_STRONG);
		g.text(font, "◆", x + 5, y + 4, Theme.accent(), false);
		g.text(font, Theme.fit(font, profile.name(), w - 30), x + 15, y + 4, Theme.TEXT_BRIGHT, false);
		g.text(font, profileMenu ? "▴" : "▾", x + w - 10, y + 4, Theme.DIM, false);
		if (over && !profileMenu) {
			tooltip(g, List.of(Component.literal("Profile: " + profile.name()).withColor(Theme.TEXT_BRIGHT),
					Component.literal("A set of greenhouses that follow one flows file. Every profile is tracked at once;").withColor(Theme.MUTED),
					Component.literal("this picks the one the window shows.").withColor(Theme.MUTED)), mouseX, mouseY);
		}
		addHotspot(new Hotspot(x, y, w, h, event -> {
			profileMenu = !profileMenu;
			if (!profileMenu && renamingProfile) {
				renamingProfile = false;
				rebuildWidgets();
			}
		}));
	}

	private int profileMenuHeight() {
		return 8 + state().profiles().size() * 22 + 24;
	}

	private void profileMenu(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		List<ModState.Profile> profiles = state().profiles();
		ModState.Profile shown = state().viewedProfile();
		int x = MENU_X;
		int y = MENU_Y;
		int w = MENU_W;
		int h = profileMenuHeight();
		// Solid, so the sidebar and page don't show through
		Theme.card(g, x, y, w, h, Theme.SURFACE | 0xFF000000, Theme.BORDER_STRONG);
		int cy = y + 4;
		ModState.Site here = GreenhouseCompanion.currentSite();
		for (ModState.Profile profile : profiles) {
			boolean selected = profile == shown;
			boolean over = mouseX >= x + 3 && mouseX < x + w - 3 && mouseY >= cy && mouseY < cy + 20;
			if (selected || over) Theme.rounded(g, x + 3, cy, w - 6, 20, selected ? Theme.alpha(Theme.accent(), 0x24) : Theme.HOVER);
			g.text(font, Theme.fit(font, profile.name(), w - 70), x + 9, cy + 2, selected ? Theme.accent() : Theme.TEXT_BRIGHT, false);
			int bx = x + w - 8;
			if (here != null && state().profileOf(here) == profile) bx -= Theme.badge(g, font, "HERE", bx - font.width("HERE") - 6, cy + 2, Theme.accent()) + 3;
			if (!profile.scopes().isEmpty()) Theme.badge(g, font, "AUTO", bx - font.width("AUTO") - 6, cy + 2, Theme.INFO);
			int count = state().sites(profile).size();
			String detail = count + " greenhouse" + (count == 1 ? "" : "s") + " · " + (profile.flowName() == null ? "no flows yet" : profile.flowName());
			Theme.small(g, font, Theme.fit(font, detail, (w - 18) * 2), x + 9, cy + 12, Theme.MUTED);
			addHotspot(new Hotspot(x + 3, cy, w - 6, 20, event -> {
				state().view(profile);
				profileMenu = false;
				renamingProfile = false;
				siteKey = null;
				selectedStep = null;
				refresh();
				rebuildWidgets();
			}));
			cy += 22;
		}
		g.fill(x + 6, cy + 1, x + w - 6, cy + 2, Theme.BORDER);
		cy += 6;
		if (renamingProfile && profileBox != null) {
			profileBox.setX(x + 6);
			profileBox.setY(cy);
			profileBox.setWidth(w - 56);
			button(g, mouseX, mouseY, "Save", x + w - 46, cy - 2, Theme.accent(), this::saveProfileName);
			return;
		}
		int bx = x + 6;
		bx += smallButton(g, mouseX, mouseY, "+ New profile", bx, cy + 2, Theme.accent(), () -> {
			state().view(state().createProfile(""));
			siteKey = null;
			selectedStep = null;
			renamingProfile = true;
			refresh();
			rebuildWidgets();
		}) + 4;
		bx += smallButton(g, mouseX, mouseY, "Rename", bx, cy + 2, Theme.DIM, () -> {
			renamingProfile = true;
			rebuildWidgets();
		}) + 4;
		if (profiles.size() > 1) {
			int count = state().sites(shown).size();
			String label = confirming("profile") ? "Click again: delete" + (count == 0 ? "" : " with " + count + " greenhouse" + (count == 1 ? "" : "s")) : "Delete";
			smallButton(g, mouseX, mouseY, label, bx, cy + 2, Theme.DANGER, () -> confirmed("profile", () -> {
				state().deleteProfile(shown);
				siteKey = null;
				selectedStep = null;
				refresh();
				rebuildWidgets();
			}));
		}
	}

	private void saveProfileName() {
		if (profileBox != null) state().renameProfile(state().viewedProfile(), profileBox.getValue());
		renamingProfile = false;
		rebuildWidgets();
	}

	private void sidebar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int w = Theme.SIDEBAR_W;
		g.fill(0, 0, w, height, Theme.glass(Theme.SIDEBAR));
		g.fill(w - 1, 0, w, height, Theme.BORDER);
		g.text(font, "Greenhouse", Theme.PAD, 12, Theme.TEXT_BRIGHT, false);
		g.text(font, "Companion", Theme.PAD, 23, Theme.accent(), false);
		profilePill(g, mouseX, mouseY);

		int y = 60;
		for (Page target : Page.values()) {
			boolean selected = target == page;
			boolean over = mouseX >= 4 && mouseX < w - 4 && mouseY >= y && mouseY < y + Theme.ROW_H - 2;
			if (selected || over) Theme.rounded(g, 4, y, w - 8, Theme.ROW_H - 2, selected ? Theme.alpha(Theme.accent(), 0x24) : Theme.HOVER);
			if (selected) g.fill(4, y + 4, 6, y + Theme.ROW_H - 6, Theme.accent());
			int color = selected ? Theme.accent() : over ? Theme.TEXT_BRIGHT : Theme.DIM;
			g.text(font, target.icon, 12, y + 6, color, false);
			g.text(font, target.label, 26, y + 6, color, false);
			if (target == Page.IMPORT && hasProblems()) Theme.badge(g, font, "!", w - 20, y + 6, Theme.WARNING);
			addHotspot(new Hotspot(4, y, w - 8, Theme.ROW_H - 2, event -> go(target)));
			y += Theme.ROW_H;
		}

		// Status, bottom up
		int sy = height - Theme.PAD - 8;
		String server = Minecraft.getInstance().getCurrentServer() == null ? "singleplayer" : dev.ilyapple.ghc.mod.GardenGrid.server(Minecraft.getInstance());
		var clock = state().clock(server);
		String tick = clock.synced() ? "Next tick " + duration(clock.nextTickMs() - System.currentTimeMillis()) : "Clock: waiting for growth";
		Theme.small(g, font, tick, Theme.PAD, sy, clock.synced() ? Theme.TEXT : Theme.MUTED);
		sy -= 10;
		String todo = dev.ilyapple.ghc.core.overlay.PlotOverlay.summary(dev.ilyapple.ghc.mod.render.LayoutOverlay.marks());
		statusDot(g, Theme.PAD, sy, todo.isEmpty() ? Theme.SUCCESS : Theme.DANGER, todo.isEmpty() ? "Layout in place" : Theme.fit(font, todo, Theme.SIDEBAR_W - 2 * Theme.PAD - 8));
		sy -= 10;
		ModState.Site here = GreenhouseCompanion.currentSite();
		boolean garden = GreenhouseCompanion.location().inGarden();
		statusDot(g, Theme.PAD, sy, here != null ? Theme.SUCCESS : garden ? Theme.INFO : Theme.FAINT,
				here != null ? "In " + here.name() : garden ? "In the Garden" : "Not in the Garden");
	}

	private void statusDot(GuiGraphicsExtractor g, int x, int y, int color, String text) {
		Theme.rounded(g, x, y, 4, 4, color);
		Theme.small(g, font, text, x + 8, y, Theme.DIM);
	}

	private boolean hasProblems() {
		return state().problems().stream().anyMatch(problem -> problem.level() != FlowCheck.Level.INFO);
	}

	// Overview

	private void overview(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int x = contentX();
		int w = contentW();
		int y = contentTop();

		// Summary tiles
		ModState.FlowFile active = state().activeFile();
		Flows flows = state().flows();
		List<ModState.Site> sites = sites();
		long followed = sites.stream().filter(site -> state().assignment(site) != null).count();
		ModState.Site here = GreenhouseCompanion.currentSite();
		String server = here != null ? here.server() : Minecraft.getInstance().getCurrentServer() == null ? "singleplayer" : dev.ilyapple.ghc.mod.GardenGrid.server(Minecraft.getInstance());
		var clock = state().clock(server);
		int tiles = 3;
		int tileW = (w - (tiles - 1) * Theme.GAP) / tiles;
		ModState.Profile profile = state().viewedProfile();
		tile(g, x, y, tileW, "FLOWS IN USE", active == null ? "None yet" : active.name(),
				"profile " + profile.name() + (flows == null ? " · import one in Flows" : " · " + flows.plots().size() + " plots"), active == null ? Theme.WARNING : Theme.TEXT_BRIGHT);
		int elsewhere = state().sites().size() - sites.size();
		tile(g, x + tileW + Theme.GAP, y, tileW, "GREENHOUSES", followed + " / " + sites.size() + " followed",
				(sites.isEmpty() ? "Walk into one to add it" : "identified as you visit") + (elsewhere > 0 ? " · " + elsewhere + " in other profiles" : ""), Theme.TEXT_BRIGHT);
		tile(g, x + 2 * (tileW + Theme.GAP), y, tileW, "GROWTH CLOCK", clock.synced() ? "Tick in " + duration(clock.nextTickMs() - System.currentTimeMillis()) : "Waiting",
				clock.synced() ? "every " + duration(clock.periodMs()) : "learns from the next look change", clock.synced() ? Theme.SUCCESS : Theme.MUTED);
		if (active == null) addHotspot(new Hotspot(x, y, tileW, 40, event -> go(Page.IMPORT)));
		y += 48;

		if (sites.isEmpty()) {
			Theme.card(g, x, y, w, 86, Theme.glass(Theme.SURFACE), Theme.BORDER);
			g.text(font, "Getting started", x + Theme.PAD, y + 10, Theme.TEXT_BRIGHT, false);
			String[] steps = {
					"1  In the SkyShards simulator press Export, then /ghc import (or the Flows page).",
					"2  Walk into each of your greenhouses: it is identified from what grows there and followed.",
					"3  Red boxes show what to place, remove or re-soil (labels up close), and you're told when to swap."};
			for (int i = 0; i < steps.length; i++) g.text(font, Theme.fit(font, steps[i], w - 2 * Theme.PAD), x + Theme.PAD, y + 28 + i * 16, Theme.DIM, false);
			return;
		}

		// Greenhouses that follow nothing (plots that piled up, other people's gardens) can be forgotten
		List<ModState.Site> inactive = sites.stream().filter(site -> state().inactive(site) && !site.equals(here)).toList();
		Theme.small(g, font, "GREENHOUSES IN " + profile.name().toUpperCase(Locale.ROOT), x, y + 4, Theme.FAINT);
		if (!inactive.isEmpty()) {
			String label = confirming("inactive") ? "Click again to forget " + inactive.size() : "Forget inactive (" + inactive.size() + ")";
			int bw = font.width(label) / 2 + 8;
			smallButton(g, mouseX, mouseY, label, x + w - bw, y + 2, Theme.DANGER, () -> confirmed("inactive", () -> {
				for (ModState.Site site : inactive) state().forget(site);
				state().save();
				refresh();
			}));
		}
		y += 14;

		int columns = w >= 420 ? 2 : 1;
		int cardW = (w - (columns - 1) * Theme.GAP) / columns;
		int cardH = 74;
		for (int i = 0; i < sites.size(); i++) {
			ModState.Site site = sites.get(i);
			int cx = x + (i % columns) * (cardW + Theme.GAP);
			int cy = y + (i / columns) * (cardH + Theme.GAP);
			if (cy > height) break;
			greenhouseCard(g, mouseX, mouseY, site, cx, cy, cardW, cardH, site.equals(here));
		}
	}

	private void tile(GuiGraphicsExtractor g, int x, int y, int w, String label, String value, String detail, int valueColor) {
		Theme.card(g, x, y, w, 40, Theme.glass(Theme.SURFACE), Theme.BORDER);
		Theme.small(g, font, label, x + 8, y + 6, Theme.MUTED);
		g.text(font, Theme.fit(font, value, w - 16), x + 8, y + 15, valueColor, false);
		Theme.small(g, font, Theme.fit(font, detail, (w - 16) * 2), x + 8, y + 29, Theme.FAINT);
	}

	private void greenhouseCard(GuiGraphicsExtractor g, int mouseX, int mouseY, ModState.Site site, int x, int y, int w, int h, boolean here) {
		boolean over = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		Theme.card(g, x, y, w, h, Theme.glass(over ? Theme.CARD_HOVER : Theme.CARD), here ? Theme.alpha(Theme.accent(), 0xAA) : Theme.BORDER);
		g.text(font, site.name(), x + 8, y + 7, Theme.TEXT_BRIGHT, false);
		int bx = x + 12 + font.width(site.name());
		if (here) bx += Theme.badge(g, font, "HERE", bx, y + 7, Theme.accent()) + 3;
		ModState.Assignment assignment = state().assignment(site);
		if (assignment != null) Theme.badge(g, font, assignment.auto() ? "AUTO" : "MANUAL", bx, y + 7, assignment.auto() ? Theme.INFO : Theme.VIOLET);

		Greenhouse.Status status = site.greenhouse().status(4);
		FlowRunner runner = status.runner();
		String line;
		String detail;
		int detailColor;
		double progress = -1;
		if (runner == null) {
			line = state().flows() == null ? "No flows imported" : "Not following a flow plot";
			detail = state().flows() == null ? "Import an export in Flows" : "Open it to pick a plot, or /ghc identify here";
			detailColor = Theme.MUTED;
		} else {
			line = "Flow plot " + runner.plot().id() + " · step " + (runner.stepIndex() + 1) + "/" + runner.plot().steps().size() + ": " + runner.step().label();
			long period = state().clock(site.server()).periodMs();
			if (runner.finished()) {
				detail = "Finished";
				detailColor = Theme.SUCCESS;
			} else if (status.swapPending()) {
				detail = "Swap to this layout now";
				detailColor = Theme.WARNING;
			} else {
				Estimator.StepEstimate first = status.plan().isEmpty() ? null : status.plan().getFirst();
				if (first != null && first.known()) {
					detail = "~" + duration(first.cycles(), period) + " left in this step";
					progress = runner.cyclesInStep() / Math.max(1.0, runner.cyclesInStep() + first.cycles());
				} else {
					detail = first == null ? "No exit" : "Can't estimate: " + first.reason();
				}
				detailColor = Theme.DIM;
			}
		}
		g.text(font, Theme.fit(font, line, w - 16), x + 8, y + 21, Theme.TEXT, false);
		g.text(font, Theme.fit(font, detail, w - 16), x + 8, y + 33, detailColor, false);
		if (progress >= 0) Theme.bar(g, x + 8, y + 45, w - 16, 3, progress, Theme.accent());

		// Jellybeans in this greenhouse
		int ready = 0;
		int growing = 0;
		for (Census.Plant plant : status.census().plants()) {
			if (!plant.id().equals(Growth.JELLYBEAN)) continue;
			if (plant.stages().lo() >= plant.kind().maxStage() || plant.origin() == StageTracker.Origin.PLACED) ready++;
			else growing++;
		}
		String note = state().note(site);
		long seen = state().lastSeen(site);
		String foot = (here ? "" : seen == 0 ? "not seen since the update  ·  " : "seen " + duration(System.currentTimeMillis() - seen) + " ago  ·  ")
				+ (ready + growing > 0 ? "Jellybeans: " + ready + " ready, " + growing + " growing" + (note == null ? "" : "  ·  ") : "") + (note == null ? "" : note);

		int buttonX = x + w - 8;
		buttonX -= button(g, mouseX, mouseY, "Open", buttonX - (font.width("Open") + 12), y + h - 22, Theme.accent(), () -> {
			siteKey = site.key();
			selectedStep = null;
			refresh();
			go(Page.FLOW);
		}) + 4;
		if (here) {
			buttonX -= button(g, mouseX, mouseY, "Identify", buttonX - (font.width("Identify") + 12), y + h - 22, Theme.INFO, () -> {
				GreenhouseCompanion.identifyHere();
				refresh();
			}) + 4;
		} else {
			// Forget it (a visit brings it back): stages, flow position and plot are dropped
			String key = "forget:" + site.key();
			String label = confirming(key) ? "Sure?" : "Forget";
			buttonX -= button(g, mouseX, mouseY, label, buttonX - (font.width(label) + 12), y + h - 22, Theme.DANGER, () -> confirmed(key, () -> {
				state().forget(site);
				state().save();
				refresh();
			})) + 4;
		}
		List<ModState.Profile> profiles = state().profiles();
		if (profiles.size() > 1) {
			// Move it to another profile (click: next, right-click: previous); it is identified again there
			buttonX -= button(g, mouseX, mouseY, "Move ▸", buttonX - (font.width("Move ▸") + 12), y + h - 22, Theme.VIOLET, () -> {}) + 4;
			int moveX = buttonX + 4;
			hotspotsReplaceLast(new Hotspot(moveX, y + h - 22, font.width("Move ▸") + 12, Theme.BUTTON_H, event -> {
				int index = profiles.indexOf(state().profileOf(site));
				ModState.Profile target = profiles.get(Math.floorMod(index + (event.button() == 1 ? -1 : 1), profiles.size()));
				state().moveTo(site, target);
				state().setNote(site, "Moved to profile " + target.name());
				state().save();
				refresh();
			}));
			if (mouseX >= moveX && mouseX < moveX + font.width("Move ▸") + 12 && mouseY >= y + h - 22 && mouseY < y + h - 22 + Theme.BUTTON_H) {
				int index = profiles.indexOf(state().profileOf(site));
				tooltip(g, List.of(Component.literal("Move to profile " + profiles.get(Math.floorMod(index + 1, profiles.size())).name()),
						Component.literal("Right-click: " + profiles.get(Math.floorMod(index - 1, profiles.size())).name()).withColor(Theme.MUTED)), mouseX, mouseY);
			}
		}
		Theme.small(g, font, Theme.fit(font, foot, (buttonX - x - 12) * 2), x + 8, y + h - 12, Theme.MUTED);
	}

	/** Swaps the click area a {@link #button} just added for one that also gets the mouse button. */
	private void hotspotsReplaceLast(Hotspot hotspot) {
		List<Hotspot> list = drawingMenu ? menuHotspots : hotspots;
		if (!list.isEmpty()) list.removeLast();
		list.add(hotspot);
	}

	// Flow page

	private void flowPage(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		ModState.Site site = site();
		int top = contentTop();
		int x = contentX();
		int w = contentW();
		int leftWidth = Math.min(150, Math.max(116, w / 5));
		int rightWidth = Math.min(190, Math.max(136, w / 4));
		int right = x + w - rightWidth;
		int center = x + leftWidth + Theme.GAP;
		int centerWidth = right - Theme.GAP - center;

		Theme.card(g, x, top, leftWidth, height - top - Theme.PAD, Theme.glass(Theme.SURFACE), Theme.BORDER);
		Theme.card(g, right, top, rightWidth, height - top - Theme.PAD, Theme.glass(Theme.SURFACE), Theme.BORDER);
		if (site == null) {
			g.textWithWordWrap(font, Component.literal("Walk into one of your greenhouses in the Garden. Each greenhouse you visit shows up here."),
					center, top + 4, centerWidth, Theme.MUTED, false);
			return;
		}
		Greenhouse.Status status = status(site);
		FlowRunner runner = status.runner();
		int shown = selectedStep != null && runner != null && selectedStep < runner.plot().steps().size() ? selectedStep : runner == null ? -1 : runner.stepIndex();
		stepList(g, mouseX, mouseY, site, status, x, top, leftWidth, shown);
		gridPanel(g, mouseX, mouseY, site, status, center, top, centerWidth, shown);
		exitPanel(g, mouseX, mouseY, site, status, right, top, rightWidth, shown);
	}

	private void stepList(GuiGraphicsExtractor g, int mouseX, int mouseY, ModState.Site site, Greenhouse.Status status, int x, int y, int w, int shown) {
		List<ModState.Site> sites = sites();
		int rowY = y + 7;
		g.text(font, "◀", x + 6, rowY, sites.size() > 1 ? Theme.TEXT : Theme.FAINT, false);
		g.text(font, "▶", x + w - 12, rowY, sites.size() > 1 ? Theme.TEXT : Theme.FAINT, false);
		addHotspot(new Hotspot(x + 2, rowY - 2, 14, 12, event -> moveSite(-1)));
		addHotspot(new Hotspot(x + w - 16, rowY - 2, 14, 12, event -> moveSite(1)));
		String name = Theme.fit(font, site.name(), w - 36);
		g.text(font, name, x + (w - font.width(name)) / 2, rowY, site.equals(GreenhouseCompanion.currentSite()) ? Theme.accent() : Theme.TEXT, false);

		// Which flow plot it follows: click to cycle (right-click backwards)
		Flows flows = state().flows(site);
		ModState.Assignment assignment = state().assignment(site);
		String follows = flows == null ? "Import flows first" : assignment == null ? "Follow: none" : "Follow: plot " + assignment.plotId() + (assignment.auto() ? " (auto)" : "");
		int pillY = rowY + 14;
		boolean hovered = mouseX >= x + 6 && mouseX < x + w - 6 && mouseY >= pillY && mouseY < pillY + 14;
		Theme.card(g, x + 6, pillY, w - 12, 14, hovered ? Theme.CARD_HOVER : Theme.CARD, assignment == null ? Theme.BORDER : Theme.alpha(Theme.accent(), 0x99));
		g.text(font, Theme.fit(font, follows, w - 24), x + 12, pillY + 3, assignment == null ? Theme.MUTED : Theme.accent(), false);
		if (flows != null) {
			addHotspot(new Hotspot(x + 6, pillY, w - 12, 14, event -> {
				List<Flows.Plot> plots = flows.plots();
				int index = -1;
				for (int i = 0; i < plots.size(); i++) if (assignment != null && plots.get(i).id() == assignment.plotId()) index = i;
				int next = event.button() == 1 ? index - 1 : index + 1;
				state().assign(site, next < 0 || next >= plots.size() ? null : plots.get(next).id(), false);
				state().setNote(site, "Picked by you");
				dev.ilyapple.ghc.mod.render.LayoutOverlay.clear();
				selectedStep = null;
				refresh();
			}));
		}

		int listTop = pillY + 22;
		Theme.small(g, font, "STEPS", x + 8, listTop - 6, Theme.FAINT);
		FlowRunner runner = status.runner();
		if (runner == null) {
			g.textWithWordWrap(font, Component.literal(flows == null
					? "Import a SkyShards simulator export on the Flows page."
					: "Click Follow to pick which plot of the flow this greenhouse runs."), x + 8, listTop + 2, w - 16, Theme.MUTED, false);
			return;
		}

		Map<Integer, double[]> timing = new HashMap<>();
		double cumulative = 0;
		for (Estimator.StepEstimate estimate : status.plan()) {
			if (!timing.containsKey(estimate.stepIndex())) timing.put(estimate.stepIndex(), new double[] {cumulative, estimate.cycles()});
			cumulative += estimate.cycles();
		}
		long period = state().clock(site.server()).periodMs();

		List<Flows.Step> steps = runner.plot().steps();
		int cardHeight = 30;
		int bottom = height - Theme.PAD - 1;
		int visible = Math.max(1, (bottom - listTop) / (cardHeight + 4));
		stepScroll = Math.max(0, Math.min(stepScroll, Math.max(0, steps.size() - visible)));
		g.enableScissor(x + 1, listTop, x + w - 1, bottom);
		for (int i = stepScroll; i < steps.size(); i++) {
			int cy = listTop + (i - stepScroll) * (cardHeight + 4);
			if (cy > height) break;
			Flows.Step step = steps.get(i);
			boolean current = i == runner.stepIndex();
			boolean selected = i == shown;
			boolean over = mouseX >= x + 6 && mouseX < x + w - 6 && mouseY >= cy && mouseY < cy + cardHeight;
			Theme.card(g, x + 6, cy, w - 12, cardHeight, over ? Theme.CARD_HOVER : Theme.CARD,
					selected ? Theme.WARNING : current ? Theme.alpha(Theme.accent(), 0xAA) : Theme.BORDER);
			Theme.rounded(g, x + 10, cy + 5, 12, 12, current ? Theme.alpha(Theme.accent(), 0x55) : Theme.BORDER);
			String number = Integer.toString(i + 1);
			g.text(font, number, x + 16 - font.width(number) / 2, cy + 7, current ? Theme.accent() : Theme.TEXT, false);
			g.text(font, Theme.fit(font, step.label(), w - 40), x + 26, cy + 5, Theme.TEXT_BRIGHT, false);

			String line;
			int color;
			double[] time = timing.get(i);
			if (current && runner.finished()) {
				line = "Finished";
				color = Theme.SUCCESS;
			} else if (current) {
				line = status.swapPending() ? "Swap to this layout" : "Now" + (time != null && Double.isFinite(time[1]) ? " · ~" + duration(time[1], period) + " left" : "");
				color = status.swapPending() ? Theme.WARNING : Theme.accent();
			} else if (time != null) {
				line = Double.isFinite(time[0]) ? "In ~" + duration(time[0], period) + (Double.isFinite(time[1]) ? " · ~" + duration(time[1], period) : "") : "Later";
				color = Theme.WARNING;
			} else {
				line = step.exits().isEmpty() ? "No exit" : "—";
				color = Theme.FAINT;
			}
			g.text(font, Theme.fit(font, line, w - 40), x + 26, cy + 17, color, false);
			int index = i;
			addHotspot(new Hotspot(x + 6, Math.max(cy, listTop), w - 12, cardHeight, event -> selectedStep = index));
		}
		g.disableScissor();
	}

	private void gridPanel(GuiGraphicsExtractor g, int mouseX, int mouseY, ModState.Site site, Greenhouse.Status status, int x, int y, int w, int shown) {
		FlowRunner runner = status.runner();
		Flows.Step step = runner == null || shown < 0 ? null : runner.plot().steps().get(shown);
		boolean isCurrent = runner != null && shown == runner.stepIndex();
		boolean showLive = live && (isCurrent || step == null);

		String title = step == null ? "What's growing" : step.label() + (isCurrent ? "" : "  (preview)");
		if (w > 110) g.text(font, Theme.fit(font, title, w - 100), x, y + 3, Theme.TEXT_BRIGHT, false);
		int px = x + w;
		for (String label : new String[] {"Live", "Layout"}) {
			int pw = font.width(label) + 12;
			px -= pw;
			boolean activePill = label.equals("Live") == showLive;
			boolean enabled = label.equals("Layout") ? step != null : isCurrent || step == null;
			boolean over = mouseX >= px && mouseX < px + pw && mouseY >= y && mouseY < y + 14;
			int accent = label.equals("Live") ? Theme.accent() : Theme.WARNING;
			Theme.card(g, px, y, pw, 14, activePill ? Theme.alpha(accent, 0x33) : over && enabled ? Theme.CARD_HOVER : Theme.CARD,
					activePill ? Theme.alpha(accent, 0x99) : Theme.BORDER);
			g.text(font, label, px + 6, y + 3, activePill ? accent : enabled ? Theme.TEXT : Theme.FAINT, false);
			if (enabled) {
				boolean wantLive = label.equals("Live");
				addHotspot(new Hotspot(px, y, pw, 14, event -> live = wantLive));
			}
			px -= 4;
		}

		int gridTop = y + 20;
		if (status.swapPending() && step != null && isCurrent) {
			Theme.card(g, x, gridTop, w, 16, Theme.alpha(Theme.WARNING, 0x33), Theme.alpha(Theme.WARNING, 0x66));
			g.text(font, Theme.fit(font, "Swap this greenhouse to " + step.label() + ", then press Swapped", w - 8), x + 4, gridTop + 4, Theme.WARNING, false);
			gridTop += 20;
		}
		int cell = Math.max(6, Math.min(28, Math.min(w, height - gridTop - 22) / 10));
		int size = cell * 10;
		int gx = x + (w - size) / 2;
		int gy = gridTop;

		// Turned the way this greenhouse is planted, so it lines up with what grows there
		Schematic drawn = step == null ? null : step.schematic();
		Schematic layout = drawn == null ? null : drawn.rotated(site == null ? 0 : site.greenhouse().rotation());
		Census census = status.census();
		Plants plants = state().plants();

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
					int tw = font.width(stage) / 2;
					g.fill(sx + span - tw - 3, sy + span - 6, sx + span - 1, sy + span - 1, 0xC00F172A);
					Theme.small(g, font, stage, sx + span - tw - 2, sy + span - 5, labelColor);
				}
			}
			if (layout != null) {
				// The same verdicts as the red boxes in the greenhouse (soil isn't known here)
				for (dev.ilyapple.ghc.core.overlay.PlotOverlay.Mark mark : dev.ilyapple.ghc.core.overlay.PlotOverlay.plan(layout, census, plants, c -> null, java.util.Set.of(),
						new dev.ilyapple.ghc.core.overlay.PlotOverlay.Options(settings().devourerRingOnlyWhenNeeded))) {
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
			if (!lines.isEmpty()) tooltip(g, lines, mouseX, mouseY);
		}

		int ly = gy + size + 6;
		if (showLive) {
			String[] legend = {"Stage: white exact, amber a range, red look not on record", "Box: + place blue, ✘ remove red, ⊘ clear orange (as in the greenhouse)", "Ring: spawn spot (green once filled)"};
			for (int i = 0; i < legend.length; i++) Theme.small(g, font, Theme.fit(font, legend[i], w * 2), x, ly + i * 6, Theme.MUTED);
		} else if (layout != null) {
			StringBuilder needs = new StringBuilder("Places ");
			layout.inputCounts().forEach((id, count) -> needs.append(count).append("x ").append(name(id)).append("  "));
			Theme.small(g, font, Theme.fit(font, needs.toString(), w * 2), x, ly, Theme.MUTED);
			StringBuilder wants = new StringBuilder("Targets ");
			layout.targetCounts().forEach((id, count) -> wants.append(count).append("x ").append(name(id)).append("  "));
			Theme.small(g, font, Theme.fit(font, wants.toString(), w * 2), x, ly + 6, Theme.WARNING);
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
				if (crop != null) lines.add(Component.literal("Layout: " + name(crop.simId() == null ? crop.name() : crop.simId())).withColor(Theme.INFO));
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
		int buttonsY = height - Theme.PAD - 22;
		String note = state().note(site);
		int noteTop = buttonsY - 4;
		if (note != null) {
			noteTop -= font.split(Component.literal(note), textWidth).size() * (font.lineHeight + 1) + 2;
			wrap(g, note, x + 8, noteTop, textWidth, Theme.MUTED);
		}
		for (AmountField field : amountBoxes.values()) field.box().visible = false;
		if (runner == null) return;
		g.enableScissor(x + 1, y + 1, x + w - 1, noteTop - 3);
		Flows.Step step = runner.plot().steps().get(Math.max(0, shown));
		boolean isCurrent = shown == runner.stepIndex();
		Theme.small(g, font, isCurrent ? "LEAVE THIS STEP WHEN" : "THIS STEP ENDS WHEN", x + 8, cy, Theme.FAINT);
		cy += 8;
		if (step.exits().isEmpty()) cy = wrap(g, "No exit: the plot stays on this step.", x + 8, cy, textWidth, Theme.MUTED);
		for (int i = 0; i < step.exits().size(); i++) {
			Flows.Exit exit = step.exits().get(i);
			String to = exit.to() == null ? (shown == runner.plot().steps().size() - 1 && !runner.plot().loop() ? "finish" : "next step")
					: label(runner.plot(), exit.to());
			boolean fires = isCurrent && status.decision() != null && status.decision().exitIndex() == i;
			cy = wrap(g, "→ " + to + (exit.any() ? "  (any of)" : ""), x + 8, cy, textWidth, fires ? Theme.SUCCESS : Theme.INFO);
			if (isCurrent && i < status.verdicts().size()) {
				List<ExitEvaluator.Verdict> verdicts = status.verdicts().get(i);
				for (int j = 0; j < verdicts.size(); j++) {
					ExitEvaluator.Verdict verdict = verdicts.get(j);
					int color = !verdict.supported() ? Theme.FAINT : verdict.value() ? Theme.SUCCESS : Theme.TEXT;
					cy = wrap(g, (verdict.value() ? "✔ " : "✘ ") + verdict.text(), x + 12, cy, textWidth - 4, color);
					cy = wrap(g, verdict.detail(), x + 22, cy, textWidth - 14, Theme.MUTED);
					if (verdict.assumed()) cy = wrap(g, "≈ " + (verdict.value() ? "taken as met" : "taken as not met") + " at the end of the range", x + 22, cy, textWidth - 14, Theme.WARNING);
					if (j < exit.when().size()) cy = amountRow(g, mouseX, mouseY, i, j, exit.when().get(j), x + 22, cy, noteTop - 3);
				}
			} else {
				for (int j = 0; j < exit.when().size(); j++) {
					Flows.Condition condition = exit.when().get(j);
					cy = wrap(g, "• " + describe(condition), x + 12, cy, textWidth - 4, Theme.TEXT);
					cy = amountRow(g, mouseX, mouseY, i, j, condition, x + 22, cy, noteTop - 3);
				}
			}
			cy += 4;
		}

		cy += 2;
		g.fill(x + 8, cy, x + w - 8, cy + 1, Theme.BORDER);
		cy += 5;
		Theme.small(g, font, "ESTIMATE", x + 8, cy, Theme.FAINT);
		cy += 8;
		long period = state().clock(site.server()).periodMs();
		double total = 0;
		int shownSteps = 0;
		for (Estimator.StepEstimate estimate : status.plan()) {
			if (shownSteps++ >= 5) break;
			String what = shownSteps == 1 ? "This step" : estimate.label();
			if (!estimate.known()) {
				cy = wrap(g, what + ": can't tell (" + estimate.reason() + ")", x + 8, cy, textWidth, Theme.MUTED);
				break;
			}
			total += estimate.cycles();
			String line = what + ": ~" + duration(estimate.cycles(), period) + (shownSteps == 1 ? " left" : "") + "  · ends in ~" + duration(total, period);
			cy = wrap(g, line, x + 8, cy, textWidth, shownSteps == 1 ? Theme.accent() : Theme.TEXT);
		}
		g.disableScissor();

		int bx = x + 6;
		if (status.swapPending() && isCurrent) bx += button(g, mouseX, mouseY, "Swapped ✔", bx, buttonsY, Theme.SUCCESS, () -> {
			site.greenhouse().swapDone();
			refresh();
		}) + 4;
		if (!isCurrent) bx += button(g, mouseX, mouseY, "Start here", bx, buttonsY, Theme.WARNING, () -> {
			runner.enter(shown);
			site.greenhouse().swapDone();
			state().markDirty();
			dev.ilyapple.ghc.mod.render.LayoutOverlay.clear();
			refresh();
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
		Theme.small(g, font, label, x, y + 4, Theme.FAINT);
		int bx = x + 32;
		boolean inView = y >= contentTop() && y + 13 <= bottom;
		amount.box().setX(bx);
		amount.box().setY(y);
		amount.box().visible = inView;
		if (inView) {
			int value = amount.value();
			stepper(g, mouseX, mouseY, "−", bx + 46, y, event -> setAmount(amount, value - step(event)));
			stepper(g, mouseX, mouseY, "+", bx + 64, y, event -> setAmount(amount, value + step(event)));
		}
		return y + 17;
	}

	/** The step whose exits the Flow page shows for {@code site}: the one picked in the list, else the current one. */
	private int shownStep(@Nullable FlowRunner runner) {
		return selectedStep != null && runner != null && selectedStep < runner.plot().steps().size() ? selectedStep : runner == null ? -1 : runner.stepIndex();
	}

	/** What the amount boxes were made for: the greenhouse, the step shown and the flows text. */
	private @Nullable String amountsFor() {
		ModState.Site site = site();
		if (site == null) return null;
		FlowRunner runner = site.greenhouse().runner();
		ModState.FlowFile file = state().activeFile();
		return site.key() + "/" + shownStep(runner) + "/" + (file == null ? 0 : file.text().hashCode());
	}

	/** One box per exit condition with an amount, on the step the Flow page shows. */
	private void buildAmountBoxes() {
		amountsBuiltFor = amountsFor();
		ModState.Site site = site();
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
				EditBox box = new EditBox(font, 0, -100, 42, 13, Component.literal("Amount"));
				box.setMaxLength(7);
				box.setValue(Integer.toString(value));
				box.visible = false;
				addRenderableWidget(box);
				amountBoxes.put(i + "/" + j, new AmountField(box, runner.plot().id(), step.id(), i, j, field, value));
			}
		}
	}

	private void setAmount(AmountField amount, int value) {
		ModState.FlowFile file = state().activeFile();
		if (file == null) return;
		try {
			state().editActiveFlow(Flows.withAmount(file.text(), amount.plotId(), amount.stepId(), amount.exitIndex(), amount.conditionIndex(), amount.field(), value));
			refresh();
		} catch (RuntimeException e) {
			GreenhouseCompanion.LOGGER.warn("[GHC] Couldn't change the amount: {}", e.getMessage());
		}
	}

	/** Applies typed amounts once their box loses focus, and makes new boxes when the step shown or the flows change. */
	@Override
	public void tick() {
		super.tick();
		// A typed inventory count is set when its box loses focus (Enter, or a click elsewhere)
		if (page == Page.INVENTORY && countBox != null && !countBox.isFocused()) applyCount();
		if (page != Page.FLOW) return;
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
		if (!typing && !java.util.Objects.equals(amountsFor(), amountsBuiltFor)) rebuildWidgets();
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		int key = event.key();
		boolean enter = key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER;
		if (profileMenu && key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
			profileMenu = false;
			if (renamingProfile) {
				renamingProfile = false;
				rebuildWidgets();
			}
			return true;
		}
		if (enter && profileBox != null && profileBox.isFocused()) {
			saveProfileName();
			return true;
		}
		if (countBox != null && countBox.isFocused()) {
			if (enter) {
				applyCount();
				return true;
			}
			if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
				countItem = null;
				rebuildWidgets();
				return true;
			}
		}
		if (enter) {
			for (AmountField amount : amountBoxes.values()) {
				if (!amount.box().isFocused()) continue;
				// Unfocused, the next tick applies it
				setFocused(null);
				amount.box().setFocused(false);
				return true;
			}
		}
		return super.keyPressed(event);
	}

	// Leaderboard
	// Leaderboard

	private static @Nullable Leaderboard board;
	private static boolean boardLoaded;

	/** The bundled leaderboard, read once. */
	static @Nullable Leaderboard board() {
		if (!boardLoaded) {
			boardLoaded = true;
			try (var in = CompanionScreen.class.getResourceAsStream("/assets/greenhouse-companion/leaderboard/leaderboard.json")) {
				if (in != null) board = Leaderboard.read(in);
			} catch (Exception e) {
				GreenhouseCompanion.LOGGER.error("[GHC] Could not read the leaderboard", e);
			}
		}
		return board;
	}

	private int pill(GuiGraphicsExtractor g, int mouseX, int mouseY, String label, int x, int y, boolean active, int accent, Runnable action) {
		int pw = font.width(label) + 12;
		boolean over = mouseX >= x && mouseX < x + pw && mouseY >= y && mouseY < y + 14;
		Theme.card(g, x, y, pw, 14, active ? Theme.alpha(accent, 0x33) : over ? Theme.CARD_HOVER : Theme.CARD, active ? Theme.alpha(accent, 0x99) : Theme.BORDER);
		g.text(font, label, x + 6, y + 3, active ? accent : Theme.TEXT, false);
		addHotspot(new Hotspot(x, y, pw, 14, event -> action.run()));
		return pw + 4;
	}

	private static String coins(double value) {
		if (Math.abs(value) >= 1e6) return String.format(Locale.ROOT, "%.1fM", value / 1e6);
		if (Math.abs(value) >= 1e3) return String.format(Locale.ROOT, "%.0fk", value / 1e3);
		return String.format(Locale.ROOT, "%.0f", value);
	}

	private static String items(double value) {
		return value >= 100 ? String.format(Locale.ROOT, "%.0f", value) : value >= 10 ? String.format(Locale.ROOT, "%.1f", value) : String.format(Locale.ROOT, "%.2f", value);
	}

	private static int rarityColor(String rarity) {
		return switch (rarity) {
			case "uncommon" -> 0xFF4ADE80;
			case "rare" -> 0xFF60A5FA;
			case "epic" -> 0xFFC084FC;
			case "legendary" -> 0xFFFBBF24;
			default -> Theme.TEXT;
		};
	}

	private void leaderboardPage(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int x = contentX();
		int w = contentW();
		int top = contentTop();
		Leaderboard lb = board();
		if (lb == null) {
			wrap(g, "No leaderboard data in this build: its leaderboard.json is missing or unreadable.", x, top, w, Theme.MUTED);
			return;
		}
		// Ironman: each mutation's tuned Ironman flow as simulated, measured against its profit share; Normal: inputs in stock
		boolean ironman = boardMode != Leaderboard.Mode.NORMAL;
		int px = x;
		px += pill(g, mouseX, mouseY, "Ironman", px, top, ironman, Theme.accent(), () -> {
			boardMode = Leaderboard.Mode.FLOW;
			boardScroll = 0;
		});
		px += pill(g, mouseX, mouseY, "Normal", px, top, !ironman, Theme.accent(), () -> {
			boardMode = Leaderboard.Mode.NORMAL;
			if (boardSort == Leaderboard.Sort.UPTIME) boardSort = Leaderboard.Sort.COINS;
			boardScroll = 0;
		});
		px += 10;
		Theme.small(g, font, "SORT", px, top + 5, Theme.FAINT);
		px += 20;
		px += pill(g, mouseX, mouseY, "Coins", px, top, boardSort == Leaderboard.Sort.COINS, Theme.WARNING, () -> boardSort = Leaderboard.Sort.COINS);
		px += pill(g, mouseX, mouseY, "Items", px, top, boardSort == Leaderboard.Sort.ITEMS, Theme.WARNING, () -> boardSort = Leaderboard.Sort.ITEMS);
		if (ironman) px += pill(g, mouseX, mouseY, "Uptime", px, top, boardSort == Leaderboard.Sort.UPTIME, Theme.WARNING, () -> boardSort = Leaderboard.Sort.UPTIME);
		px += 10;
		px += pill(g, mouseX, mouseY, "3 plots", px, top, boardPlots == 3, Theme.INFO, () -> boardPlots = 3);
		px += pill(g, mouseX, mouseY, "1 plot", px, top, boardPlots == 1, Theme.INFO, () -> boardPlots = 1);
		// Harvest interval: online every N growth stages
		String every = "Every " + boardInterval;
		int ex = x + w - font.width(every) - 44;
		stepper(g, mouseX, mouseY, "−", ex, top, event -> boardInterval = Math.max(1, boardInterval - 1));
		g.text(font, every, ex + 20, top + 3, Theme.TEXT_BRIGHT, false);
		stepper(g, mouseX, mouseY, "+", ex + 24 + font.width(every), top, event -> boardInterval = Math.min(14, boardInterval + 1));
		if (mouseX >= ex && mouseX < x + w && mouseY >= top && mouseY < top + 14) {
			tooltip(g, List.of(Component.literal("Harvest interval: you're online every " + boardInterval + " growth stage" + (boardInterval == 1 ? "" : "s"))), mouseX, mouseY);
		}

		int listTop = top + 22;
		int listW = (int) (w * 0.56);
		String plots = boardPlots == 3 ? "3 plots" : "1 plot";
		String head = ironman ? "Ironman: each mutation's Ironman flow run from an empty inventory, " + plots + ", once it's running; uptime = main items a day"
				+ " vs plots that always have their inputs, beside its profit share and how much of it the flow loses. NPC coins a day"
				: "Normal: " + plots + " running full time with inputs in stock: NPC coins + mutations made − inputs used, mutations at Bazaar instant-sell (" + dev.ilyapple.ghc.mod.Prices.status() + ")";
		Theme.small(g, font, Theme.fit(font, head, w * 2), x, listTop, Theme.MUTED);
		Theme.small(g, font, Theme.fit(font, disclaimer(lb), w * 2), x, listTop + 7, Theme.FAINT);
		listTop += 18;
		dev.ilyapple.ghc.mod.Prices.refresh();
		List<Leaderboard.Ranked> rows = lb.rank(boardMode, boardInterval, boardSort, "", CompanionScreen::inputPrice);
		int rowH = 20;
		int visible = Math.max(1, (height - listTop - Theme.PAD) / rowH);
		boardScroll = Math.max(0, Math.min(boardScroll, Math.max(0, rows.size() - visible)));
		if (boardSelected == null && !rows.isEmpty()) boardSelected = rows.getFirst().entry().mutation();
		g.enableScissor(x, listTop, x + listW, height - Theme.PAD);
		Leaderboard.Ranked chosen = null;
		for (int i = 0; i < rows.size(); i++) {
			Leaderboard.Ranked row = rows.get(i);
			if (row.entry().mutation().equals(boardSelected)) chosen = row;
			int vi = i - boardScroll;
			if (vi < 0 || vi > visible) continue;
			int ry = listTop + vi * rowH;
			boolean selected = row.entry().mutation().equals(boardSelected);
			boolean over = mouseX >= x && mouseX < x + listW && mouseY >= ry && mouseY < ry + rowH - 2;
			Theme.card(g, x, ry, listW, rowH - 2, selected ? Theme.alpha(Theme.accent(), 0x22) : over ? Theme.CARD_HOVER : Theme.CARD,
					selected ? Theme.alpha(Theme.accent(), 0x99) : Theme.BORDER);
			int rankColor = row.rank() == 1 ? 0xFFFBBF24 : row.rank() == 2 ? 0xFFCBD5E1 : row.rank() == 3 ? 0xFFD97706 : Theme.MUTED;
			g.text(font, "#" + row.rank(), x + 4, ry + 5, rankColor, false);
			Theme.cropIcon(g, row.entry().mutation(), x + 26, ry + 2, 14, 255);
			int valueW = ironman ? 190 : 92;
			g.text(font, Theme.fit(font, row.entry().name(), listW - 44 - valueW), x + 44, ry + 5, rarityColor(row.entry().rarity()), false);
			Leaderboard.Numbers numbers = row.numbers();
			if (!numbers.possible()) {
				Theme.small(g, font, ironman ? "never reaches its main step" : "an input nobody makes", x + listW - 80, ry + 7, Theme.DANGER);
			} else {
				String c = coins(numbers.coins() * boardPlots);
				String it = items(numbers.made() * boardPlots) + "/d";
				int cx = x + listW - 6 - font.width(c);
				g.text(font, c, cx, ry + 5, boardSort == Leaderboard.Sort.COINS ? Theme.WARNING : Theme.TEXT, false);
				int ix = cx - 8 - font.width(it);
				g.text(font, it, ix, ry + 5, boardSort == Leaderboard.Sort.ITEMS ? Theme.WARNING : Theme.DIM, false);
				if (ironman) {
					// The flow's uptime, the profit share beside it, and the share of the profit share it loses
					int ux = ix - 8;
					Leaderboard.Sim sim = row.entry().sim(boardInterval);
					if (sim != null && sim.lostVsProfitShare() != null) {
						String lost = lostLabel(sim.lostVsProfitShare());
						int lw = font.width(lost) / 2;
						Theme.small(g, font, lost, ux - lw, ry + 7, lostColor(sim.lostVsProfitShare()));
						ux -= lw + 5;
					}
					Leaderboard.Numbers formula = row.entry().at(Leaderboard.Mode.PROFIT_SHARE, boardInterval);
					if (formula != null && formula.possible()) {
						String of = "of " + Math.round(formula.uptime() * 100) + "%";
						int ow = font.width(of) / 2;
						Theme.small(g, font, of, ux - ow, ry + 7, Theme.FAINT);
						ux -= ow + 4;
					}
					String up = Math.round(numbers.uptime() * 100) + "%";
					g.text(font, up, ux - font.width(up), ry + 5, boardSort == Leaderboard.Sort.UPTIME ? Theme.WARNING : uptimeColor(numbers.uptime()), false);
				}
			}
			String key = row.entry().mutation();
			addHotspot(new Hotspot(x, Math.max(ry, listTop), listW, rowH - 2, event -> {
				boardSelected = key;
				boardDetailScroll = 0;
				boardStatus = "";
			}));
		}
		g.disableScissor();
		if (chosen != null) boardDetail(g, mouseX, mouseY, chosen, x + listW + Theme.GAP, top + 22, w - listW - Theme.GAP);
	}

	/** "−3% lost", or "+1% over" when the simulation beat the formula (within its noise). */
	private static String lostLabel(double lost) {
		long pct = Math.round(lost * 100);
		return pct > 0 ? "−" + pct + "% lost" : pct < 0 ? "+" + (-pct) + "% over" : "±0%";
	}

	private static int lostColor(double lost) {
		return lost <= 0.02 ? Theme.SUCCESS : lost <= 0.10 ? Theme.WARNING : Theme.DANGER;
	}

	/** What the numbers assume: the player stats they were simulated with, and how many plots. */
	private String disclaimer(Leaderboard lb) {
		Map<String, String> stats = lb.stats();
		String fortune = stats.getOrDefault("farmingFortune", "?");
		if (fortune.endsWith(".0")) fortune = fortune.substring(0, fortune.length() - 2);
		boolean ironman = boardMode != Leaderboard.Mode.NORMAL;
		return "Simulated at Farming Fortune " + fortune + ", Overbloom " + stats.getOrDefault("overbloom", "?") + ", Crop Growth "
				+ stats.getOrDefault("cropGrowth", "?") + " (wiki max), harvest every " + boardInterval + (ironman ? ", on 3 plots for 656 days" : ", on 1 plot")
				+ " and shown for " + boardPlots + (boardPlots == 1 ? " plot" : " plots") + "; your numbers scale with your own stats";
	}

	private static int uptimeColor(double uptime) {
		return uptime >= 0.8 ? Theme.SUCCESS : uptime >= 0.4 ? Theme.WARNING : Theme.DANGER;
	}

	private void boardDetail(GuiGraphicsExtractor g, int mouseX, int mouseY, Leaderboard.Ranked row, int x, int y, int w) {
		Leaderboard.Entry e = row.entry();
		boolean ironman = boardMode != Leaderboard.Mode.NORMAL;
		int bottom = height - Theme.PAD;
		Theme.card(g, x, y, w, bottom - y, Theme.glass(Theme.SURFACE), Theme.BORDER);
		int buttonsY = bottom - Theme.BUTTON_H - 6;
		g.enableScissor(x + 1, y + 1, x + w - 1, buttonsY - 4);
		int cy = y + 8 - boardDetailScroll;
		int tx = x + 8;
		int tw = w - 16;
		Theme.cropIcon(g, e.mutation(), tx, cy, 16, 255);
		g.text(font, Theme.fit(font, "#" + row.rank() + "  " + e.name(), tw - 20), tx + 20, cy + 4, rarityColor(e.rarity()), false);
		cy += 22;
		String plots = boardPlots == 3 ? "3 plots" : "1 plot";
		if (ironman) {
			cy = ironmanDetail(g, e, tx, cy, tw, plots);
		} else {
			Leaderboard.Numbers normal = e.at(Leaderboard.Mode.NORMAL, boardInterval);
			Theme.small(g, font, "EVERY " + boardInterval + " · NORMAL · " + plots.toUpperCase(Locale.ROOT) + ", INPUTS IN STOCK", tx, cy, Theme.FAINT);
			cy += 8;
			if (normal != null) {
				cy = wrap(g, items(normal.made() * boardPlots) + " " + e.name() + " a day, " + coins(normal.coins() * boardPlots) + " NPC coins a day", tx, cy, tw, Theme.TEXT);
				List<String> unpriced = new ArrayList<>();
				if (inputPrice(e.mutation()) <= 0) unpriced.add(e.name());
				if (!normal.makes().isEmpty()) cy = wrap(g, "Also makes a day: " + itemList(normal.makes(), unpriced), tx, cy, tw, Theme.MUTED);
				if (!normal.uses().isEmpty()) cy = wrap(g, "Uses a day: " + itemList(normal.uses(), unpriced), tx, cy, tw, Theme.MUTED);
				double made = normal.madeWorth(e.mutation(), CompanionScreen::inputPrice) * boardPlots;
				double cost = normal.inputCost(CompanionScreen::inputPrice) * boardPlots;
				cy = wrap(g, "At Bazaar: +" + coins(made) + " mutations made" + (cost > 0 ? ", −" + coins(cost) + " inputs" : "") + " → profit "
						+ coins(normal.profit(e.mutation(), CompanionScreen::inputPrice) * boardPlots) + " a day", tx, cy, tw, Theme.WARNING);
				if (!unpriced.isEmpty()) cy = wrap(g, "No Bazaar price yet for " + String.join(", ", unpriced) + " (counted as 0)", tx, cy, tw, Theme.FAINT);
			}
			cy = wrap(g, "The normal flow puts this layout on all 3 plots, with nothing else: your inputs come from stock.", tx, cy + 2, tw, Theme.MUTED);
			if (e.fillCells() > 0) cy = filledNote(g, e, tx, cy + 2, tw);
		}
		g.disableScissor();

		// Each board adds its own flow: the Ironman board the Ironman flow, the Normal board the normal one
		int bx = x + 8;
		if (e.layout() != null) bx += button(g, mouseX, mouseY, "Copy link", bx, buttonsY, Theme.INFO, () -> {
			Minecraft.getInstance().keyboardHandler.setClipboard("https://greenhouse.skyshards.com/designer?layout=" + e.layout());
			boardStatus = "Designer link copied";
		}) + 4;
		if (ironman && e.ironmanFlow() != null) bx += button(g, mouseX, mouseY, "Add Ironman flow", bx, buttonsY, Theme.SUCCESS, () -> {
			try {
				state().addToLibrary(e.ironmanFlow(), "Ironman: " + e.name());
				boardStatus = "Added to Flows as \"Ironman: " + e.name() + "\"";
			} catch (RuntimeException ex) {
				boardStatus = "Couldn't add it: " + ex.getMessage();
			}
		}) + 4;
		if (!ironman && e.normalFlow() != null) bx += button(g, mouseX, mouseY, "Add normal flow", bx, buttonsY, Theme.SUCCESS, () -> {
			try {
				state().addToLibrary(e.normalFlow(), "Normal: " + e.name());
				boardStatus = "Added to Flows as \"Normal: " + e.name() + "\"";
			} catch (RuntimeException ex) {
				boardStatus = "Couldn't add it: " + ex.getMessage();
			}
		}) + 4;
		if (!boardStatus.isEmpty()) Theme.small(g, font, Theme.fit(font, boardStatus, (x + w - bx - 6) * 2), bx + 2, buttonsY + 6, Theme.SUCCESS);
	}

	/** The Ironman board's detail: the simulated flow at this interval, what it loses, then the profit share it's measured against. */
	private int ironmanDetail(GuiGraphicsExtractor g, Leaderboard.Entry e, int tx, int cy, int tw, String plots) {
		Leaderboard.Sim sim = e.sim(boardInterval);
		Leaderboard.Numbers flow = e.at(Leaderboard.Mode.FLOW, boardInterval);
		Leaderboard.Numbers share = e.at(Leaderboard.Mode.PROFIT_SHARE, boardInterval);
		Theme.small(g, font, "EVERY " + boardInterval + " · IRONMAN FLOW · FROM AN EMPTY INVENTORY", tx, cy, Theme.FAINT);
		cy += 8;
		if (sim == null || flow == null) {
			cy = wrap(g, "Not simulated at this interval.", tx, cy, tw, Theme.MUTED);
		} else if (sim.daysToMain() == null) {
			cy = wrap(g, "Never reached its main step in " + Math.round(sim.days()) + " days.", tx, cy, tw, Theme.DANGER);
		} else {
			cy = wrap(g, items(flow.made() * boardPlots) + " " + e.name() + " and " + coins(flow.coins() * boardPlots) + " NPC coins a day from " + plots
					+ " once it's running: " + Math.round(flow.uptime() * 100) + "% uptime.", tx, cy, tw, Theme.TEXT);
			if (sim.lostVsProfitShare() != null && share != null && share.possible()) {
				double lost = sim.lostVsProfitShare();
				String text = lost > 0.005 ? "Loses " + String.format(Locale.ROOT, "%.1f%%", lost * 100) + " of the profit share (" + items(share.made() * boardPlots)
						+ " a day by the formula, " + Math.round(share.uptime() * 100) + "% uptime): plots regrow after swaps and stock runs out before the plots notice."
						: lost < -0.005 ? "Makes " + String.format(Locale.ROOT, "%.1f%%", -lost * 100) + " more than the profit share (" + items(share.made() * boardPlots)
						+ " a day by the formula): within the simulation's noise, it loses nothing."
						: "Matches the profit share (" + items(share.made() * boardPlots) + " a day by the formula).";
				cy = wrap(g, text, tx, cy + 2, tw, lostColor(lost));
			}
			String first = "Main step after " + String.format(Locale.ROOT, "%.1f", sim.daysToMain()) + " days from nothing";
			String swaps = sim.swapsPerPlotDay() == null ? "" : sim.swapsPerPlotDay() < 0.005 ? ", then no swaps" : ", then a swap every " + swapEvery(sim.swapsPerPlotDay()) + " per plot";
			cy = wrap(g, first + swaps + " (simulated " + Math.round(sim.days()) + " days, online every " + boardInterval + ").", tx, cy + 2, tw, Theme.DIM);
		}
		if (e.ironmanFlow() != null && !e.targets().isEmpty()) {
			cy += 4;
			String round = e.roundDays() <= 0 ? "about two days" : (e.roundDays() == Math.rint(e.roundDays()) ? Integer.toString((int) e.roundDays()) : Double.toString(e.roundDays()))
					+ (e.roundDays() == 1 ? " day" : " days");
			String roles = switch (e.dedicated()) {
				case 1 -> " Plot 1 stays on the main step once it runs (its layout never regrows); plots 2 and 3 make the inputs and run the main step in between.";
				case 2 -> " Plots 1 and 2 stay on the main step once it runs (their layouts never regrow); plot 3 makes the inputs and runs the main step in between.";
				default -> " All 3 plots run it.";
			};
			cy = wrap(g, "Each plot first makes the inputs, deepest first; each step ends once the stock is enough for " + round + " of the main"
					+ " plot (inventory rule), and the main step goes back when an input runs low." + roles + " Plot roles and round length are tuned"
					+ " so layouts grow as long as they can without a swap.", tx, cy, tw, Theme.MUTED);
			for (Leaderboard.Target target : e.targets()) {
				cy = wrap(g, "Make " + name(target.item()) + " to " + target.high() + (target.low() == null ? "" : ", back when under " + target.low()), tx + 4, cy, tw - 4, Theme.DIM);
			}
		} else {
			cy = wrap(g, "Only base crops needed: the layout alone is the flow.", tx, cy + 2, tw, Theme.MUTED);
		}
		if (e.fillCells() > 0) cy = filledNote(g, e, tx, cy + 2, tw);

		// The profit share it's measured against, as before
		cy += 6;
		Theme.small(g, font, "PROFIT SHARE · EVERY " + boardInterval + " · PLOT TIME TO KEEP IT RUNNING", tx, cy, Theme.FAINT);
		cy += 8;
		if (share == null || !share.possible()) {
			cy = wrap(g, "Can't run: one of its inputs has no plot that makes it.", tx, cy, tw, Theme.DANGER);
		} else {
			cy = wrap(g, "Profit share " + Math.round(share.uptime() * 100) + "%: " + items(share.made() * boardPlots) + " " + e.name() + " and "
					+ coins(share.coins() * boardPlots) + " coins a day from " + plots + " shared across the chain", tx, cy, tw, Theme.TEXT);
			cy += 2;
			List<Map.Entry<String, Double>> shares = new ArrayList<>(share.shares().entrySet());
			shares.sort(Map.Entry.<String, Double>comparingByValue().reversed());
			for (Map.Entry<String, Double> part : shares) {
				boolean main = part.getKey().equals(e.mutation());
				String label = (main ? "★ " : "") + plotName(part.getKey());
				int barX = tx + 96;
				int barW = Math.max(10, tw - 96 - 34);
				g.text(font, Theme.fit(font, label, 92), tx, cy, main ? Theme.TEXT_BRIGHT : Theme.DIM, false);
				g.fill(barX, cy + 2, barX + barW, cy + 6, Theme.CARD_SUNK);
				g.fill(barX, cy + 2, barX + (int) Math.round(barW * part.getValue()), cy + 6, main ? Theme.accent() : Theme.INFO);
				String pct = String.format(Locale.ROOT, "%.1f%%", part.getValue() * 100);
				g.text(font, pct, tx + tw - font.width(pct), cy, main ? Theme.accent() : Theme.DIM, false);
				cy += 11;
			}
		}
		return cy;
	}

	private int filledNote(GuiGraphicsExtractor g, Leaderboard.Entry e, int tx, int cy, int tw) {
		return wrap(g, "Filled: " + e.fillCells() + " free cells hold base crops and spawn spots for commons (its inputs' first), out of reach of every"
				+ " spot's ring and buffs.", tx, cy, tw, Theme.MUTED);
	}

	/** "~2.5 days", "~9 hours" between swaps at {@code perDay} swaps a day. */
	private static String swapEvery(double perDay) {
		if (perDay <= 0) return "never";
		double days = 1 / perDay;
		return days >= 1.5 ? String.format(Locale.ROOT, "~%.1f days", days) : String.format(Locale.ROOT, "~%.0f hours", days * 24);
	}

	private String plotName(String key) {
		return switch (key) {
			case "shellfruit_loop_plot" -> "Shellfruit loop";
			case "replant_converter" -> "Replant converter";
			default -> name(key);
		};
	}

	// Flows (import and library)

	private static final String IMPORT_HELP = "In the SkyShards simulator press Export (nothing ticked copies the flows only) and paste it here. "
			+ "Layout links work too, one per line, each as a one-step plot. It becomes the flows of the profile picked in the sidebar.";
	private static final String LINKS_HELP = "Paste a SkyShards Designer or share link (or a SkyMutations link) for each plot. Each plot grows "
			+ "its layout as one step; a greenhouse you walk into follows the plot whose layout grows there. It becomes the flows of the profile picked in the sidebar.";
	private static final int LINK_LABEL_W = 34;
	private static final int LINK_ROW_H = 26;
	private static final int MAX_LINK_PLOTS = 9;

	private static String importHelp() {
		return importLinks ? LINKS_HELP : IMPORT_HELP;
	}

	private void importPage(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int x = contentX();
		int top = contentTop();
		int right = importColumnX();
		int leftW = right - x - Theme.GAP * 2;
		int rightW = x + contentW() - right;

		// Right: paste and import
		Theme.heading(g, font, "Import", right, top, rightW);
		int modeTop = wrapSmall(g, importHelp(), right, top + 10, rightW, Theme.DIM) + 4;
		int px = right;
		px += pill(g, mouseX, mouseY, "Flows JSON", px, modeTop, !importLinks, Theme.accent(), () -> switchImport(false));
		pill(g, mouseX, mouseY, "Plot links", px, modeTop, importLinks, Theme.accent(), () -> switchImport(true));
		if (importLinks) linkRows(g, mouseX, mouseY, right, modeTop + 38, rightW);
		int by = height - Theme.PAD - Theme.BUTTON_H;
		int bx = right;
		boolean roomy = font.width("Paste clipboard") + font.width("Import") + font.width("Clear") + 44 <= rightW;
		bx += button(g, mouseX, mouseY, roomy ? "Paste clipboard" : "Paste", bx, by, Theme.INFO, this::pasteImport) + 4;
		bx += button(g, mouseX, mouseY, "Import", bx, by, Theme.accent(), this::doImport) + 4;
		button(g, mouseX, mouseY, "Clear", bx, by, Theme.MUTED, () -> {
			if (importBox != null) importBox.setValue("");
			linkBoxes.forEach(box -> box.setValue(""));
		});
		Theme.small(g, font, Theme.fit(font, importStatus, rightW * 2), right, by - 8, importColor);

		// Left: library, then checks
		Theme.heading(g, font, "Library", x, top, leftW);
		List<ModState.FlowFile> library = state().library();
		int y = top + 10;
		// Every import is its own file, written at once
		String where = "Each file is saved in config/greenhouse-companion/flows as you import it.";
		int folderW = font.width("Open folder") / 2 + 8;
		Theme.small(g, font, Theme.fit(font, where, (leftW - folderW - 6) * 2), x, y, Theme.FAINT);
		smallButton(g, mouseX, mouseY, "Open folder", x + leftW - folderW, y - 3, Theme.DIM, () -> {
			try {
				java.nio.file.Files.createDirectories(state().flowFolder());
				net.minecraft.util.Util.getPlatform().openPath(state().flowFolder());
			} catch (java.io.IOException | RuntimeException e) {
				importStatus = "Couldn't open " + state().flowFolder() + ": " + e.getMessage();
				importColor = Theme.DANGER;
			}
		});
		y += 10;
		if (library.isEmpty()) {
			y = wrap(g, "Nothing imported yet. Paste an export on the right, or run /ghc import with it on the clipboard.", x, y, leftW, Theme.MUTED);
		}
		int cardH = 40;
		int maxCards = Math.max(1, Math.min(library.size(), (height / 2 - y) / (cardH + 4)));
		libraryScroll = Math.max(0, Math.min(libraryScroll, Math.max(0, library.size() - maxCards)));
		for (int i = libraryScroll; i < Math.min(library.size(), libraryScroll + maxCards); i++) {
			ModState.FlowFile file = library.get(i);
			boolean activeFile = i == state().activeIndex();
			boolean over = mouseX >= x && mouseX < x + leftW && mouseY >= y && mouseY < y + cardH;
			Theme.card(g, x, y, leftW, cardH, over ? Theme.CARD_HOVER : Theme.CARD, activeFile ? Theme.alpha(Theme.accent(), 0xAA) : Theme.BORDER);
			if (i == renaming && renameBox != null) {
				renameBox.setX(x + 6);
				renameBox.setY(y + 4);
				renameBox.setWidth(leftW - 70);
				int index = i;
				button(g, mouseX, mouseY, "Save", x + leftW - 50, y + 3, Theme.accent(), () -> {
					state().rename(index, renameBox.getValue());
					renaming = -1;
					rebuildWidgets();
				});
			} else {
				g.text(font, Theme.fit(font, file.name(), leftW - 60), x + 6, y + 6, Theme.TEXT_BRIGHT, false);
				if (activeFile) Theme.badge(g, font, "IN USE", x + leftW - 40, y + 6, Theme.accent());
			}
			String detail;
			try {
				Flows parsed = Flows.parse(file.text());
				int steps = parsed.plots().stream().mapToInt(plot -> plot.steps().size()).sum();
				List<String> users = state().usersOf(i).stream().map(ModState.Profile::name).toList();
				detail = parsed.plots().size() + " plots · " + steps + " steps · imported " + duration(System.currentTimeMillis() - file.importedMs()) + " ago"
						+ (users.isEmpty() ? "" : " · used by " + String.join(", ", users)) + " · " + file.file();
			} catch (RuntimeException e) {
				detail = "Can't be read any more: " + e.getMessage();
			}
			Theme.small(g, font, Theme.fit(font, detail, (leftW - 12) * 2), x + 6, y + 18, Theme.MUTED);
			int index = i;
			int ax = x + 6;
			if (!activeFile) ax += smallButton(g, mouseX, mouseY, "Use", ax, y + cardH - 14, Theme.accent(), () -> {
				state().activate(index, true);
				dev.ilyapple.ghc.mod.render.LayoutOverlay.clear();
				refresh();
			}) + 3;
			ax += smallButton(g, mouseX, mouseY, "Rename", ax, y + cardH - 14, Theme.DIM, () -> {
				renaming = index;
				rebuildWidgets();
			}) + 3;
			ax += smallButton(g, mouseX, mouseY, "Copy", ax, y + cardH - 14, Theme.DIM, () -> Minecraft.getInstance().keyboardHandler.setClipboard(file.text())) + 3;
			// Deleting removes the file too: ask twice
			String key = "delete:" + file.file();
			smallButton(g, mouseX, mouseY, confirming(key) ? "Click again to delete the file" : "Delete", ax, y + cardH - 14, Theme.DANGER, () -> confirmed(key, () -> {
				state().remove(index);
				renaming = -1;
				rebuildWidgets();
			}));
			y += cardH + 4;
		}

		y += 6;
		Theme.heading(g, font, "Checks", x, y, leftW);
		y += 10;
		List<FlowCheck.Problem> problems = state().problems();
		if (state().flows() == null) {
			wrap(g, "Checks appear here for the flows in use.", x, y, leftW, Theme.MUTED);
		} else if (problems.isEmpty()) {
			wrap(g, "✔ Every step can be followed in game.", x, y, leftW, Theme.SUCCESS);
		} else {
			g.enableScissor(x, y, x + leftW, height - Theme.PAD);
			for (FlowCheck.Problem problem : problems) {
				int color = switch (problem.level()) {
					case ERROR -> Theme.DANGER;
					case WARNING -> Theme.WARNING;
					case INFO -> Theme.MUTED;
				};
				String icon = switch (problem.level()) {
					case ERROR -> "✘ ";
					case WARNING -> "⚠ ";
					case INFO -> "ℹ ";
				};
				Theme.small(g, font, Theme.fit(font, problem.where(), leftW * 2), x, y, Theme.FAINT);
				y = wrap(g, icon + problem.text(), x, y + 6, leftW, color) + 3;
				if (y > height) break;
			}
			g.disableScissor();
		}
	}

	/** Plot links: a label and a box per plot, what each link holds under it, and + / − for the number of plots. */
	private void linkRows(GuiGraphicsExtractor g, int mouseX, int mouseY, int x, int y, int w) {
		for (int i = 0; i < linkBoxes.size(); i++) {
			EditBox box = linkBoxes.get(i);
			int rowY = y + i * LINK_ROW_H;
			g.text(font, "Plot " + (i + 1), x, rowY + 3, Theme.TEXT, false);
			smallButton(g, mouseX, mouseY, "Paste", x + w - 28, rowY + 2, Theme.INFO, () -> box.setValue(Minecraft.getInstance().keyboardHandler.getClipboard().strip()));
			String link = box.getValue().strip();
			if (link.isEmpty()) {
				Theme.small(g, font, "Left out", x + LINK_LABEL_W, rowY + 17, Theme.FAINT);
				continue;
			}
			Schematic schematic = linkCache.computeIfAbsent(link, text -> java.util.Optional.ofNullable(Schematic.fromLink(text))).orElse(null);
			String detail;
			int color;
			if (schematic == null) {
				detail = "✘ Not a SkyShards or SkyMutations layout";
				color = Theme.DANGER;
			} else {
				int inputs = schematic.inputCounts().values().stream().mapToInt(Integer::intValue).sum();
				int spots = schematic.targetCounts().values().stream().mapToInt(Integer::intValue).sum();
				List<String> targets = schematic.targetCounts().keySet().stream().map(this::name).toList();
				detail = "✔ " + inputs + " input" + (inputs == 1 ? "" : "s") + " · " + spots + " spot" + (spots == 1 ? "" : "s")
						+ (targets.isEmpty() ? "" : " (" + String.join(", ", targets) + ")");
				color = Theme.SUCCESS;
			}
			Theme.small(g, font, Theme.fit(font, detail, (w - LINK_LABEL_W) * 2), x + LINK_LABEL_W, rowY + 17, color);
		}
		int ay = y + linkBoxes.size() * LINK_ROW_H;
		int ax = x + LINK_LABEL_W;
		if (linkDrafts.size() < MAX_LINK_PLOTS) ax += smallButton(g, mouseX, mouseY, "+ Plot", ax, ay, Theme.accent(), () -> {
			saveLinkDrafts();
			linkDrafts.add("");
			rebuildWidgets();
		}) + 4;
		if (linkDrafts.size() > 1) smallButton(g, mouseX, mouseY, "− Plot " + linkDrafts.size(), ax, ay, Theme.DIM, () -> {
			saveLinkDrafts();
			linkDrafts.removeLast();
			rebuildWidgets();
		});
	}

	private void saveLinkDrafts() {
		for (int i = 0; i < linkBoxes.size() && i < linkDrafts.size(); i++) linkDrafts.set(i, linkBoxes.get(i).getValue());
	}

	private void switchImport(boolean links) {
		if (importLinks == links) return;
		importLinks = links;
		importStatus = "";
		rebuildWidgets();
	}

	/** Paste clipboard: into the JSON box, or one link per line into the plot boxes from Plot 1 on. */
	private void pasteImport() {
		String clipboard = Minecraft.getInstance().keyboardHandler.getClipboard();
		if (!importLinks) {
			if (importBox != null) importBox.setValue(clipboard);
			return;
		}
		List<String> lines = clipboard.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
		if (lines.isEmpty()) return;
		saveLinkDrafts();
		while (linkDrafts.size() < Math.min(MAX_LINK_PLOTS, lines.size())) linkDrafts.add("");
		for (int i = 0; i < linkDrafts.size(); i++) linkDrafts.set(i, i < lines.size() ? lines.get(i) : "");
		rebuildWidgets();
	}

	/** The text to import: the JSON box's, or a flows export built from the plot links. */
	private String importText() {
		if (!importLinks) return importBox == null ? "" : importBox.getValue();
		Map<Integer, String> links = new LinkedHashMap<>();
		for (int i = 0; i < linkBoxes.size(); i++) links.put(i + 1, linkBoxes.get(i).getValue());
		return Flows.fromPlotLinks(links);
	}

	private void doImport() {
		if (importBox == null && linkBoxes.isEmpty()) return;
		try {
			String text = importText();
			String name = nameBox == null || nameBox.getValue().isBlank() ? GreenhouseCompanion.defaultName(text) : nameBox.getValue().strip();
			Flows flows = state().importFlows(text, name);
			dev.ilyapple.ghc.mod.render.LayoutOverlay.clear();
			long errors = state().problems().stream().filter(problem -> problem.level() == FlowCheck.Level.ERROR).count();
			importStatus = "Imported " + flows.plots().size() + " plot" + (flows.plots().size() == 1 ? "" : "s") + (errors > 0 ? ", " + errors + " problem" + (errors == 1 ? "" : "s") : "");
			importColor = errors > 0 ? Theme.WARNING : Theme.SUCCESS;
			if (importBox != null) importBox.setValue("");
			linkBoxes.forEach(box -> box.setValue(""));
			if (nameBox != null) nameBox.setValue("");
			refresh();
		} catch (RuntimeException e) {
			importStatus = "Couldn't read that: " + e.getMessage();
			importColor = Theme.DANGER;
		}
	}

	// Inventory

	private void inventoryPage(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		ModState state = state();
		var inventory = state.inventory();
		int x = contentX();
		int w = contentW();
		int top = contentTop();
		long read = inventory.sacksReadMs();
		String when = read == 0 ? "No sack read yet: open your sacks (SkyBlock menu → Sacks) and the counts fill in."
				: "Sacks last read " + duration(System.currentTimeMillis() - read) + " ago. Open them again to refresh.";
		g.text(font, Theme.fit(font, when, w), x, top, Theme.DIM, false);
		Theme.small(g, font, "Click a count to type it (Enter sets it; 1.5k and 2m work). + / − adjust (shift ×10, ctrl ×100). Right-click a count to clear its adjustment.",
				x, top + 12, Theme.FAINT);

		Map<String, Integer> needed = neededByFlows();
		String filter = search == null ? "" : search.getValue().toLowerCase(Locale.ROOT).strip();
		List<Plants.Plant> plants = new ArrayList<>();
		for (Plants.Plant plant : state.plants().all()) {
			if (!filter.isEmpty() && !plant.name().toLowerCase(Locale.ROOT).contains(filter)) continue;
			if (!plant.mutation() && inventory.count(plant.id()) == 0 && !needed.containsKey(plant.id()) && filter.isEmpty()) continue;
			plants.add(plant);
		}
		plants.sort(Comparator.comparing((Plants.Plant plant) -> !needed.containsKey(plant.id()))
				.thenComparing(plant -> inventory.count(plant.id()) == 0)
				.thenComparing(Plants.Plant::name));

		int columns = Math.max(1, w / 190);
		int columnWidth = (w - (columns - 1) * 6) / columns;
		int rowHeight = 24;
		int listTop = top + 24;
		int rows = (plants.size() + columns - 1) / columns;
		int visibleRows = Math.max(1, (height - listTop - Theme.PAD) / rowHeight);
		inventoryScroll = Math.max(0, Math.min(inventoryScroll, Math.max(0, rows - visibleRows)));
		g.enableScissor(x, listTop, x + w, height - Theme.PAD);
		for (int i = 0; i < plants.size(); i++) {
			int row = i / columns - inventoryScroll;
			if (row < 0 || row >= visibleRows + 1) continue;
			Plants.Plant plant = plants.get(i);
			int cx = x + i % columns * (columnWidth + 6);
			int cy = listTop + row * rowHeight;
			boolean over = mouseX >= cx && mouseX < cx + columnWidth && mouseY >= cy && mouseY < cy + rowHeight - 2;
			Theme.card(g, cx, cy, columnWidth, rowHeight - 2, over ? Theme.CARD_HOVER : Theme.CARD,
					needed.containsKey(plant.id()) ? Theme.alpha(Theme.WARNING, 0x66) : Theme.BORDER);
			Theme.cropIcon(g, plant.id(), cx + 3, cy + 3, 16, 255);
			int count = inventory.count(plant.id());
			int adjustment = inventory.adjustment(plant.id());
			g.text(font, Theme.fit(font, plant.name(), columnWidth - 110), cx + 23, cy + 3, Theme.TEXT_BRIGHT, false);
			String detail = "sacks " + String.format("%,d", inventory.sackCount(plant.id())) + (adjustment != 0 ? (adjustment > 0 ? "  +" : "  ") + String.format("%,d", adjustment) : "")
					+ (needed.containsKey(plant.id()) ? "  · flow wants " + String.format("%,d", needed.get(plant.id())) : "");
			Theme.small(g, font, Theme.fit(font, detail, (columnWidth - 110) * 2), cx + 23, cy + 13, adjustment != 0 ? Theme.WARNING : Theme.MUTED);
			String total = String.format("%,d", count);
			int tx = cx + columnWidth - 44 - font.width(total);
			if (plant.id().equals(countItem) && countBox != null) {
				countBox.setX(cx + columnWidth - 44 - 64);
				countBox.setY(cy + 4);
				countBox.setWidth(60);
			} else {
				g.text(font, total, tx, cy + 7, count > 0 ? Theme.SUCCESS : Theme.FAINT, false);
				boolean overCount = mouseX >= tx - 2 && mouseX < tx + font.width(total) + 2 && mouseY >= cy && mouseY < cy + rowHeight - 2;
				if (overCount) g.fill(tx, cy + 16, tx + font.width(total), cy + 17, Theme.alpha(Theme.SUCCESS, 0x99));
				addHotspot(new Hotspot(tx - 2, cy, font.width(total) + 4, rowHeight - 2, event -> {
					if (event.button() == 1) {
						state.inventory().set(plant.id(), state.inventory().sackCount(plant.id()));
						state.markDirty();
						return;
					}
					// Type the count instead
					countItem = plant.id();
					rebuildWidgets();
				}));
			}
			int bx = cx + columnWidth - 40;
			stepper(g, mouseX, mouseY, "−", bx, cy + 4, event -> adjust(plant.id(), -step(event)));
			stepper(g, mouseX, mouseY, "+", bx + 20, cy + 4, event -> adjust(plant.id(), step(event)));
		}
		g.disableScissor();
	}

	private void adjust(String id, int delta) {
		state().inventory().adjust(id, delta);
		state().markDirty();
		refresh();
	}

	/** Sets the typed count ({@code 1,234}, {@code 1.5k}, {@code 2m}); an empty or unreadable box changes nothing. */
	private void applyCount() {
		String id = countItem;
		EditBox box = countBox;
		countItem = null;
		if (id != null && box != null) {
			Long typed = typedCount(box.getValue());
			if (typed != null) {
				state().inventory().set(id, (int) Math.min(Integer.MAX_VALUE, typed));
				state().markDirty();
				refresh();
			}
		}
		rebuildWidgets();
	}

	static @Nullable Long typedCount(String text) {
		String clean = text.strip().toLowerCase(Locale.ROOT).replace(",", "").replace("_", "");
		if (clean.isEmpty()) return null;
		double scale = 1;
		if (clean.endsWith("k")) scale = 1_000;
		else if (clean.endsWith("m")) scale = 1_000_000;
		if (scale > 1) clean = clean.substring(0, clean.length() - 1);
		try {
			double value = Double.parseDouble(clean) * scale;
			return value < 0 ? null : Math.round(value);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static int step(MouseButtonEvent event) {
		int modifiers = event.modifiers();
		if ((modifiers & 2) != 0) return 100;
		if ((modifiers & 1) != 0) return 10;
		return 1;
	}

	private Map<String, Integer> neededByFlows() {
		Map<String, Integer> needed = new LinkedHashMap<>();
		Flows flows = state().flows();
		if (flows == null) return needed;
		for (Flows.Plot plot : flows.plots()) {
			for (Flows.Step step : plot.steps()) {
				for (Flows.Exit exit : step.exits()) collectNeeds(exit.when(), needed);
				if (step.schematic() != null) step.schematic().inputCounts().forEach((id, count) -> needed.merge(id, count, Math::max));
			}
		}
		return needed;
	}

	private static void collectNeeds(List<Flows.Condition> conditions, Map<String, Integer> needed) {
		for (Flows.Condition condition : conditions) {
			if (condition.kind().equals("group")) collectNeeds(condition.of(), needed);
			if (condition.kind().startsWith("inventory") || condition.kind().equals("collectedAtLeast")) {
				needed.merge(condition.string("item"), condition.integer("qty", 0), Math::max);
			}
		}
	}

	// Stages

	private void stagesPage(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int x = contentX();
		int w = contentW();
		int top = contentTop();
		LookTable table = state().table();
		ModelBook models = table.models();
		int readings = table.observations().size();
		String intro = "Stage looks come from your stage recorder (" + String.format("%,d", readings) + " readings, " + state().tableSource()
				+ "), each Jellybean's own shape, and " + (models == null ? "no model book" : "stage models converted from " + models.source())
				+ ". All-in Aloe reads its \"Stage N\" hologram. Stages in amber have no look on record yet: read them with the stage recorder.";
		int y = wrapSmall(g, intro, x, top, w, Theme.DIM) + 6;

		List<Plants.Plant> plants = new ArrayList<>();
		for (Plants.Plant plant : state().plants().all()) if (plant.grows()) plants.add(plant);
		plants.sort(Comparator.comparing((Plants.Plant plant) -> table.uncovered(plant).isEmpty()).thenComparing(Plants.Plant::rarity).thenComparing(Plants.Plant::name));

		int nameW = Math.min(150, w / 3);
		Theme.small(g, font, "PLANT", x + 22, y, Theme.MUTED);
		Theme.small(g, font, "RECORDER", x + nameW, y, Theme.MUTED);
		Theme.small(g, font, "MODELS", x + nameW + 60, y, Theme.MUTED);
		Theme.small(g, font, "STILL UNKNOWN", x + nameW + 120, y, Theme.MUTED);
		y += 8;
		int rowH = 16;
		int visible = Math.max(1, (height - Theme.PAD - y) / rowH);
		stagesScroll = Math.max(0, Math.min(stagesScroll, Math.max(0, plants.size() - visible)));
		g.enableScissor(x, y, x + w, height - Theme.PAD);
		for (int i = stagesScroll; i < plants.size(); i++) {
			Plants.Plant plant = plants.get(i);
			int ry = y + (i - stagesScroll) * rowH;
			if (ry > height) break;
			if ((i & 1) == 0) g.fill(x, ry, x + w, ry + rowH, 0x0AFFFFFF);
			Theme.cropIcon(g, plant.id(), x + 2, ry + 2, 12, 255);
			g.text(font, Theme.fit(font, plant.name(), nameW - 26), x + 18, ry + 4, Theme.TEXT, false);
			StageSet read = plant.allStages().minus(table.unread(plant));
			boolean jellybean = plant.id().equals(Growth.JELLYBEAN);
			g.text(font, jellybean ? "shape" : read.size() + "/" + plant.maxStage(), x + nameW, ry + 4, read.size() == plant.maxStage() || jellybean ? Theme.SUCCESS : Theme.DIM, false);
			StageSet modelled = models == null ? StageSet.EMPTY : models.covered(plant);
			g.text(font, modelled.isEmpty() ? "—" : modelled.size() + "/" + plant.maxStage(), x + nameW + 60, ry + 4, modelled.isEmpty() ? Theme.FAINT : Theme.INFO, false);
			StageSet left = table.uncovered(plant);
			String unknown = plant.id().equals(Growth.ALOE) ? "✔ hologram" : jellybean ? "✔ shape" : left.isEmpty() ? "✔ all covered" : left.toString().replace("{", "").replace("}", "");
			g.text(font, Theme.fit(font, unknown, w - nameW - 124), x + nameW + 120, ry + 4, left.isEmpty() ? Theme.SUCCESS : Theme.WARNING, false);
		}
		g.disableScissor();
	}

	// Settings

	private interface Flag {
		boolean get();

		void set(boolean value);
	}

	private void settingsPage(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		Settings s = settings();
		int x = contentX();
		int w = Math.min(contentW(), 460);
		int top = contentTop();
		int bottom = height - Theme.PAD;
		int y = top - settingsScroll;
		g.enableScissor(x, top, x + contentW(), bottom);
		String bypass = GreenhouseCompanion.bypassKey().getTranslatedKeyMessage().getString();

		y = group(g, "Layouts", x, y, w);
		y = toggleRow(g, mouseX, mouseY, x, y, w, "Layout Overlay", "A box in its action's colour on every cell that needs something done, ghosts of missing plants, turned the way you planted",
				flag(() -> s.layoutHologram, value -> s.layoutHologram = value));
		y = subToggleRow(g, mouseX, mouseY, x, y, w, true, "Identify greenhouses automatically", "The flow plot, step and turn from what grows in a greenhouse you walk into",
				flag(() -> s.autoIdentify, value -> s.autoIdentify = value));
		y = toggleRow(g, mouseX, mouseY, x, y, w, "Labels up Close", "+ place, ✘ remove, ⊘ clear a spot, ▼ soil, ◇ spawn spot, ✂ root, each in its own colour",
				flag(() -> s.hologramLabels, value -> s.hologramLabels = value));
		y = subStepperRow(g, mouseX, mouseY, x, y, w, s.hologramLabels, "Range", s.overlayLabelRange + " blocks",
				() -> s.overlayLabelRange = Math.max(2, s.overlayLabelRange - 1), () -> s.overlayLabelRange = Math.min(32, s.overlayLabelRange + 1));

		y = group(g, "Crops", x, y + 6, w);
		y = toggleRow(g, mouseX, mouseY, x, y, w, "Short Jellybeans", "Draws each Jellybean as its bottom block; hitboxes stay as they are",
				flag(() -> s.shrinkJellybeans, value -> s.shrinkJellybeans = value));
		y = toggleRow(g, mouseX, mouseY, x, y, w, "Protect Growing Crops", "Left clicks on a crop that isn't fully grown do nothing; hold " + bypass + " to break anyway",
				flag(() -> s.protectGrowing, value -> s.protectGrowing = value));
		y = subToggleRow(g, mouseX, mouseY, x, y, w, s.protectGrowing, "Magic Jellybeans only", "",
				flag(() -> s.protectJellybeansOnly, value -> s.protectJellybeansOnly = value));
		y = toggleRow(g, mouseX, mouseY, x, y, w, "Protect Right Clicks", "Right clicks too; the Plant Diagnostics Tool always works",
				flag(() -> s.protectRightClicks, value -> s.protectRightClicks = value));
		y = toggleRow(g, mouseX, mouseY, x, y, w, "Stage Above Crops", "12 / 15: white exact, amber a range, red a look not on record, ✔ fully grown",
				flag(() -> s.stageLabels, value -> s.stageLabels = value));
		y = subToggleRow(g, mouseX, mouseY, x, y, w, s.stageLabels, "Magic Jellybeans only", "",
				flag(() -> s.stageLabelsJellybeansOnly, value -> s.stageLabelsJellybeansOnly = value));
		y = subStepperRow(g, mouseX, mouseY, x, y, w, s.stageLabels, "Range", s.labelRange + " blocks",
				() -> s.labelRange = Math.max(4, s.labelRange - 4), () -> s.labelRange = Math.min(64, s.labelRange + 4));
		y = toggleRow(g, mouseX, mouseY, x, y, w, "Water Above Crops", "☔ 40 above each growing crop that uses water, in its highlight colour; ? until a Hydro Can or Crop Diagnostics reads it",
				flag(() -> s.waterAboveCrops, value -> {
					s.waterAboveCrops = value;
					dev.ilyapple.ghc.mod.WaterHelper.refresh();
				}));
		y = subStepperRow(g, mouseX, mouseY, x, y, w, s.waterAboveCrops, "Range", s.waterLabelRange + " blocks",
				() -> s.waterLabelRange = Math.max(2, s.waterLabelRange - 2), () -> s.waterLabelRange = Math.min(64, s.waterLabelRange + 2));
		y = toggleRow(g, mouseX, mouseY, x, y, w, "Highlight Fully Grown Crops", "What's ready to harvest, outlined from the soil up",
				flag(() -> s.highlightHarvestable, value -> s.highlightHarvestable = value));
		y = subColorRow(g, mouseX, mouseY, x, y, w, s.highlightHarvestable, "Colour", () -> s.grownColor, color -> s.grownColor = color);
		y = toggleRow(g, mouseX, mouseY, x, y, w, "Highlight Crops That Need Water", "Outlines a crop that would reach −100 water before you're back, and one already at −100 (it stops growing)",
				flag(() -> s.highlightWater, value -> {
					s.highlightWater = value;
					dev.ilyapple.ghc.mod.WaterHelper.refresh();
				}));
		y = subStepperRow(g, mouseX, mouseY, x, y, w, s.highlightWater, "Harvest interval", s.waterIntervalLabel(),
				() -> {
					s.waterVisitsPerDay = s.waterVisitsPerDay <= 1 ? 4 : s.waterVisitsPerDay - 1;
					dev.ilyapple.ghc.mod.WaterHelper.refresh();
				},
				() -> {
					s.waterVisitsPerDay = s.waterVisitsPerDay >= 4 ? 1 : s.waterVisitsPerDay + 1;
					dev.ilyapple.ghc.mod.WaterHelper.refresh();
				});
		y = subColorRow(g, mouseX, mouseY, x, y, w, s.highlightWater, "Needs water", () -> s.needsWaterColor, color -> {
			s.needsWaterColor = color;
			dev.ilyapple.ghc.mod.WaterHelper.refresh();
		});
		y = subColorRow(g, mouseX, mouseY, x, y, w, s.highlightWater, "Dried out (−100)", () -> s.driedColor, color -> {
			s.driedColor = color;
			dev.ilyapple.ghc.mod.WaterHelper.refresh();
		});
		y = toggleRow(g, mouseX, mouseY, x, y, w, "Highlights Through Blocks", "Both highlights seen through cactus, canes and walls",
				flag(() -> s.highlightThroughBlocks, value -> s.highlightThroughBlocks = value));
		y = toggleRow(g, mouseX, mouseY, x, y, w, "Harvest Base Crops", "Off: Wheat, Cactus, Wild Rose and the other base crops are left to grow: no highlight, not counted as ready, no stage label",
				flag(() -> s.harvestBaseCrops, value -> s.harvestBaseCrops = value));
		y = subToggleRow(g, mouseX, mouseY, x, y, w, !s.harvestBaseCrops, "Protect fully grown base crops", "While base crops are left to grow, they can't be broken by accident either",
				flag(() -> s.protectGrownBaseCrops, value -> s.protectGrownBaseCrops = value));

		y = group(g, "Alerts", x, y + 6, w);
		y = toggleRow(g, mouseX, mouseY, x, y, w, "Title when it's time to swap", "", flag(() -> s.alertTitle, value -> s.alertTitle = value));
		y = subToggleRow(g, mouseX, mouseY, x, y, w, s.alertTitle, "Chime", "", flag(() -> s.alertSound, value -> s.alertSound = value));
		y = subToggleRow(g, mouseX, mouseY, x, y, w, s.alertTitle, "Chat line", "With an [Open] link", flag(() -> s.alertChat, value -> s.alertChat = value));
		y = toggleRow(g, mouseX, mouseY, x, y, w, "Chime on growth tick", "", flag(() -> s.tickChime, value -> s.tickChime = value));

		y = group(g, "Profit tracker", x, y + 6, w);
		y = toggleRow(g, mouseX, mouseY, x, y, w, "Track harvests", "Every greenhouse harvest that dropped something, and what it dropped, on the Profit page",
				flag(() -> s.recordDrops, value -> s.recordDrops = value));
		y = subToggleRow(g, mouseX, mouseY, x, y, w, s.recordDrops, "Icon on inventory and chat screens", "A Helianthus in the corner opens the tracker; Garden only",
				flag(() -> s.dropsIcon, value -> s.dropsIcon = value));
		y = subStepperRow(g, mouseX, mouseY, x, y, w, s.recordDrops && s.dropsIcon, "Corner", s.dropsIconCorner.name().toLowerCase(Locale.ROOT).replace('_', ' '),
				() -> s.dropsIconCorner = Settings.Corner.values()[Math.floorMod(s.dropsIconCorner.ordinal() - 1, Settings.Corner.values().length)],
				() -> s.dropsIconCorner = Settings.Corner.values()[Math.floorMod(s.dropsIconCorner.ordinal() + 1, Settings.Corner.values().length)]);

		y = group(g, "HUD and look", x, y + 6, w);
		y = toggleRow(g, mouseX, mouseY, x, y, w, "HUD", "Greenhouse, step, what to do and the next tick", flag(() -> s.hud, value -> s.hud = value));
		y = subToggleRow(g, mouseX, mouseY, x, y, w, s.hud, "Only in the Garden", "", flag(() -> s.hudOnlyInGarden, value -> s.hudOnlyInGarden = value));
		y = subStepperRow(g, mouseX, mouseY, x, y, w, s.hud, "Corner", s.hudCorner.name().toLowerCase(Locale.ROOT).replace('_', ' '),
				() -> s.hudCorner = Settings.Corner.values()[Math.floorMod(s.hudCorner.ordinal() - 1, Settings.Corner.values().length)],
				() -> s.hudCorner = Settings.Corner.values()[Math.floorMod(s.hudCorner.ordinal() + 1, Settings.Corner.values().length)]);
		y = stepperRow(g, mouseX, mouseY, x, y, w, "Window opacity", s.menuOpacity + "%", () -> s.menuOpacity = Math.max(40, s.menuOpacity - 10), () -> s.menuOpacity = Math.min(100, s.menuOpacity + 10));
		// Accent swatches
		g.text(font, "Accent", x + 8, y + 6, Theme.TEXT, false);
		int sx = x + w - 8;
		Settings.Accent[] accents = Settings.Accent.values();
		for (int i = accents.length - 1; i >= 0; i--) {
			Settings.Accent accent = accents[i];
			sx -= 16;
			boolean selected = s.accent == accent;
			Theme.rounded(g, sx, y + 4, 12, 12, accent.color);
			if (selected) Theme.border(g, sx - 2, y + 2, 16, 16, Theme.TEXT_BRIGHT);
			addHotspot(new Hotspot(sx, y + 4, 12, 12, event -> {
				s.accent = accent;
				s.save();
			}));
		}
		y += Theme.ROW_H + 4;

		// Debug: everything else, folded away
		int headY = y + 6;
		Theme.heading(g, font, (debugOpen ? "▾ " : "▸ ") + "Debug", x, headY + 4, w);
		if (headY + 14 > top) addHotspot(new Hotspot(x, Math.max(headY, top), w, 14, event -> debugOpen = !debugOpen));
		y = headY + 14;
		if (debugOpen) {
			y = toggleRow(g, mouseX, mouseY, x, y, w, "Check the soil", "A box and ghost soil where the ground isn't one the plant grows on (Shadevine: sand or farmland)",
					flag(() -> s.hologramGround, value -> s.hologramGround = value));
			y = toggleRow(g, mouseX, mouseY, x, y, w, "Overlay boxes through blocks", "The layout overlay's boxes drawn through cactus and canes",
					flag(() -> s.overlaySeeThrough, value -> s.overlaySeeThrough = value));
			y = toggleRow(g, mouseX, mouseY, x, y, w, "Devourer: ring only while needed", "Only asks for a Puffercloud/Zombud while a spot it touches is empty; other cells get Nether Wart (soul sand) or Cocoa Beans (farmland)",
					flag(() -> s.devourerRingOnlyWhenNeeded, value -> s.devourerRingOnlyWhenNeeded = value));
			y = stepperRow(g, mouseX, mouseY, x, y, w, "Cactus outline", s.cactusOutline == Settings.CactusOutline.BLOCK ? "block" : "hitbox",
					() -> s.cactusOutline = s.cactusOutline == Settings.CactusOutline.BLOCK ? Settings.CactusOutline.HITBOX : Settings.CactusOutline.BLOCK,
					() -> s.cactusOutline = s.cactusOutline == Settings.CactusOutline.BLOCK ? Settings.CactusOutline.HITBOX : Settings.CactusOutline.BLOCK);
			y = toggleRow(g, mouseX, mouseY, x, y, w, "Protect the stage-119 Jellybean look", "Placed Jellybeans look like 119; ones the mod saw you place are never protected",
					flag(() -> s.protectPlacedLook, value -> s.protectPlacedLook = value));
			y = toggleRow(g, mouseX, mouseY, x, y, w, "Say why a click was blocked", "A line above the hotbar",
					flag(() -> s.protectionMessage, value -> s.protectionMessage = value));
			y = toggleRow(g, mouseX, mouseY, x, y, w, "Local debug stream",
					"Read-only, this machine only: http://127.0.0.1:" + s.debugPort + "/events and /state, for following the mod live",
					flag(() -> s.debugStream, value -> {
						s.debugStream = value;
						dev.ilyapple.ghc.mod.DebugStream.apply(s);
					}));
		}
		y = wrap(g, "Keys (Options → Controls → Greenhouse Companion): open this window " + GreenhouseCompanion.openKey().getTranslatedKeyMessage().getString()
				+ ", break anyway " + bypass + ". Or type /ghc.", x + 8, y + 4, w - 16, Theme.MUTED);
		g.disableScissor();
		int total = y + settingsScroll - top;
		settingsScroll = Math.max(0, Math.min(settingsScroll, Math.max(0, total - (bottom - top) + 8)));
	}

	/** A setting under the one above it, indented; greyed out and inactive while {@code enabled} is false. */
	private int subToggleRow(GuiGraphicsExtractor g, int mouseX, int mouseY, int x, int y, int w, boolean enabled, String label, String description, Flag flag) {
		int indent = 16;
		int h = description.isEmpty() ? 18 : 26;
		boolean over = enabled && mouseX >= x + indent && mouseX < x + w && mouseY >= y && mouseY < y + h && mouseY >= contentTop();
		if (over) Theme.rounded(g, x + indent, y, w - indent, h, Theme.HOVER);
		g.fill(x + 10, y, x + 11, y + h / 2 + 1, Theme.BORDER_STRONG);
		g.fill(x + 10, y + h / 2, x + indent, y + h / 2 + 1, Theme.BORDER_STRONG);
		g.text(font, Theme.fit(font, label, w - indent - 50), x + indent + 6, y + 5, enabled ? Theme.TEXT : Theme.FAINT, false);
		if (!description.isEmpty()) Theme.small(g, font, Theme.fit(font, description, (w - indent - 50) * 2), x + indent + 6, y + 15, enabled ? Theme.MUTED : Theme.FAINT);
		Theme.toggle(g, x + w - 28, y + (h - 10) / 2, enabled && flag.get(), over);
		if (enabled && y + h > contentTop()) addHotspot(new Hotspot(x + indent, Math.max(y, contentTop()), w - indent, h, event -> {
			flag.set(!flag.get());
			settings().save();
		}));
		return y + h + 2;
	}

	private int subStepperRow(GuiGraphicsExtractor g, int mouseX, int mouseY, int x, int y, int w, boolean enabled, String label, String value, Runnable down, Runnable up) {
		int indent = 16;
		g.fill(x + 10, y, x + 11, y + 11, Theme.BORDER_STRONG);
		g.fill(x + 10, y + 10, x + indent, y + 11, Theme.BORDER_STRONG);
		g.text(font, label, x + indent + 6, y + 6, enabled ? Theme.TEXT : Theme.FAINT, false);
		int vx = x + w - 8 - 16;
		if (enabled) stepper(g, mouseX, mouseY, "+", vx, y + 3, event -> {
			up.run();
			settings().save();
		});
		vx -= font.width(value) + 8;
		g.text(font, value, vx + 4, y + 6, enabled ? Theme.accent() : Theme.FAINT, false);
		if (enabled) stepper(g, mouseX, mouseY, "−", vx - 16, y + 3, event -> {
			down.run();
			settings().save();
		});
		return y + 22;
	}

	/** A colour setting under the one above it: one swatch per {@link Settings#HIGHLIGHT_COLORS}, the chosen one ringed. */
	private int subColorRow(GuiGraphicsExtractor g, int mouseX, int mouseY, int x, int y, int w, boolean enabled, String label,
			java.util.function.IntSupplier get, java.util.function.IntConsumer set) {
		int indent = 16;
		g.fill(x + 10, y, x + 11, y + 11, Theme.BORDER_STRONG);
		g.fill(x + 10, y + 10, x + indent, y + 11, Theme.BORDER_STRONG);
		g.text(font, label, x + indent + 6, y + 6, enabled ? Theme.TEXT : Theme.FAINT, false);
		int sx = x + w - 8;
		int[] colors = Settings.HIGHLIGHT_COLORS;
		for (int i = colors.length - 1; i >= 0; i--) {
			int color = colors[i];
			sx -= 16;
			boolean selected = get.getAsInt() == color;
			Theme.rounded(g, sx, y + 4, 12, 12, enabled ? color : Theme.alpha(color, 0x55));
			if (selected) Theme.border(g, sx - 2, y + 2, 16, 16, enabled ? Theme.TEXT_BRIGHT : Theme.FAINT);
			if (enabled && y + 16 > contentTop()) addHotspot(new Hotspot(sx, Math.max(y + 4, contentTop()), 12, 12, event -> {
				set.accept(color);
				settings().save();
			}));
		}
		return y + 22;
	}

	private static Flag flag(java.util.function.BooleanSupplier get, Consumer<Boolean> set) {
		return new Flag() {
			@Override
			public boolean get() {
				return get.getAsBoolean();
			}

			@Override
			public void set(boolean value) {
				set.accept(value);
			}
		};
	}

	private int group(GuiGraphicsExtractor g, String title, int x, int y, int w) {
		Theme.heading(g, font, title, x, y + 4, w);
		return y + 14;
	}

	private int toggleRow(GuiGraphicsExtractor g, int mouseX, int mouseY, int x, int y, int w, String label, String description, Flag flag) {
		int h = description.isEmpty() ? 20 : 28;
		boolean over = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h && mouseY >= contentTop();
		if (over) Theme.rounded(g, x, y, w, h, Theme.HOVER);
		g.text(font, Theme.fit(font, label, w - 50), x + 8, y + 6, Theme.TEXT, false);
		if (!description.isEmpty()) Theme.small(g, font, Theme.fit(font, description, (w - 50) * 2), x + 8, y + 17, Theme.MUTED);
		Theme.toggle(g, x + w - 28, y + (h - 10) / 2, flag.get(), over);
		if (y + h > contentTop()) addHotspot(new Hotspot(x, Math.max(y, contentTop()), w, h, event -> {
			flag.set(!flag.get());
			settings().save();
		}));
		return y + h + 2;
	}

	private int stepperRow(GuiGraphicsExtractor g, int mouseX, int mouseY, int x, int y, int w, String label, String value, Runnable down, Runnable up) {
		g.text(font, label, x + 8, y + 6, Theme.TEXT, false);
		int vx = x + w - 8 - 16;
		stepper(g, mouseX, mouseY, "+", vx, y + 3, event -> {
			up.run();
			settings().save();
		});
		vx -= font.width(value) + 8;
		g.text(font, value, vx + 4, y + 6, Theme.accent(), false);
		stepper(g, mouseX, mouseY, "−", vx - 16, y + 3, event -> {
			down.run();
			settings().save();
		});
		return y + 22;
	}

	/** "4.6 Dustgrain, 47 Shadevine" for the plots shown; names with no Bazaar price are added to {@code unpriced}. */
	private String itemList(Map<String, Double> perPlot, List<String> unpriced) {
		StringBuilder text = new StringBuilder();
		perPlot.forEach((id, v) -> {
			if (!text.isEmpty()) text.append(", ");
			text.append(items(v * boardPlots)).append(' ').append(name(id));
			if (inputPrice(id) <= 0 && !unpriced.contains(name(id))) unpriced.add(name(id));
		});
		return text.toString();
	}

	/** One mutation (simulator id) at the Bazaar's instant-sell price, 0 when it has none. */
	private static double inputPrice(String id) {
		Double price = dev.ilyapple.ghc.mod.Prices.price(dev.ilyapple.ghc.core.drops.Items.itemOf(id), dev.ilyapple.ghc.mod.Prices.Source.BAZAAR);
		return price == null ? 0 : price;
	}

	// Small helpers

	private int button(GuiGraphicsExtractor g, int mouseX, int mouseY, String label, int x, int y, int accent, Runnable action) {
		int w = font.width(label) + 12;
		boolean over = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + Theme.BUTTON_H;
		Theme.card(g, x, y, w, Theme.BUTTON_H, Theme.alpha(accent, over ? 0x48 : 0x22), Theme.alpha(accent, over ? 0xCC : 0x88));
		g.text(font, label, x + 6, y + 5, over ? Theme.TEXT_BRIGHT : accent, false);
		addHotspot(new Hotspot(x, y, w, Theme.BUTTON_H, event -> action.run()));
		return w;
	}

	private int smallButton(GuiGraphicsExtractor g, int mouseX, int mouseY, String label, int x, int y, int accent, Runnable action) {
		int w = font.width(label) / 2 + 8;
		boolean over = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 10;
		Theme.rounded(g, x, y, w, 10, Theme.alpha(accent, over ? 0x55 : 0x22));
		Theme.small(g, font, label, x + 4, y + 3, over ? Theme.TEXT_BRIGHT : accent);
		addHotspot(new Hotspot(x, y, w, 10, event -> action.run()));
		return w;
	}

	private void stepper(GuiGraphicsExtractor g, int mouseX, int mouseY, String label, int x, int y, Consumer<MouseButtonEvent> action) {
		boolean over = mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 14;
		Theme.card(g, x, y, 16, 14, over ? Theme.CARD_HOVER : Theme.CARD_SUNK, Theme.BORDER_STRONG);
		g.text(font, label, x + 8 - font.width(label) / 2, y + 3, Theme.TEXT, false);
		addHotspot(new Hotspot(x, y, 16, 14, action));
	}

	private int wrap(GuiGraphicsExtractor g, String text, int x, int y, int width, int color) {
		if (text.isEmpty()) return y;
		for (FormattedCharSequence line : font.split(Component.literal(text), Math.max(20, width))) {
			g.text(font, line, x, y, color, false);
			y += font.lineHeight + 1;
		}
		return y;
	}

	private int smallLines(String text, int width) {
		return font.split(Component.literal(text), Math.max(20, width * 2)).size();
	}

	/** Wrapped half-size text; returns the y below it. */
	private int wrapSmall(GuiGraphicsExtractor g, String text, int x, int y, int width, int color) {
		for (FormattedCharSequence line : font.split(Component.literal(text), Math.max(20, width * 2))) {
			g.pose().pushMatrix();
			g.pose().translate(x, y);
			g.pose().scale(0.5f, 0.5f);
			g.text(font, line, 0, 0, color, false);
			g.pose().popMatrix();
			y += 5;
		}
		return y;
	}

	private String name(String id) {
		Plants.Plant plant = state().plants().byId(id);
		return plant == null ? id : plant.name();
	}

	private static String label(Flows.Plot plot, String stepId) {
		int index = plot.indexOf(stepId);
		return index < 0 ? stepId : plot.steps().get(index).label();
	}

	private String describe(Flows.Condition condition) {
		String name = name(condition.mutationId());
		int stage = condition.integer("stage", 0);
		int count = condition.integer("count", 1);
		return switch (condition.kind()) {
			case "cycles" -> condition.integer("n", 0) + " cycles in step";
			case "inventoryAtLeast" -> "inventory " + name(condition.string("item")) + " >= " + condition.integer("qty", 0);
			case "inventoryBelow" -> "inventory " + name(condition.string("item")) + " < " + condition.integer("qty", 0);
			case "collectedAtLeast" -> "collected " + name(condition.string("item")) + " >= " + condition.integer("qty", 0) + " in total";
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
			case "plantDecayed" -> "a " + name(condition.string("kindId")) + " decayed";
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
		return font.width(text) <= maxWidth ? text : lo + "+";
	}

	public static String duration(double cycles, long periodMs) {
		return duration(Math.round(cycles * periodMs));
	}

	/** {@code 1h 20m}, {@code 2d 3h}, {@code 45s}. */
	public static String duration(long ms) {
		if (ms < 0) return "0m";
		long seconds = ms / 1000;
		long days = seconds / 86_400;
		long hours = seconds % 86_400 / 3600;
		long minutes = seconds % 3600 / 60;
		if (days > 0) return days + "d " + hours + "h";
		if (hours > 0) return hours + "h " + minutes + "m";
		if (minutes > 0) return minutes + "m";
		return seconds + "s";
	}

	/** For the dev demo's screenshots, in the order DevDemo takes them. */
	public void demo(int index) {
		switch (index) {
			case 1 -> page = Page.FLOW;
			case 2 -> live = false;
			case 3 -> page = Page.IMPORT;
			case 4 -> page = Page.INVENTORY;
			case 5 -> page = Page.STAGES;
			case 6 -> page = Page.SETTINGS;
			case 7 -> page = Page.LEADERBOARD;
			case 8 -> {
				page = Page.OVERVIEW;
				profileMenu = true;
			}
			case 9 -> {
				profileMenu = false;
				page = Page.LEADERBOARD;
				boardMode = Leaderboard.Mode.FLOW;
				boardInterval = 4;
				boardSelected = "zombud";
			}
			case 10 -> page = Page.DROPS;
			case 11 -> {
				page = Page.INVENTORY;
				countItem = "magic_jellybean";
			}
			case 12 -> {
				page = Page.IMPORT;
				importLinks = true;
				Flows flows = state().flows();
				for (int i = 0; flows != null && i < Math.min(linkDrafts.size(), flows.plots().size()); i++) {
					linkDrafts.set(i, "https://greenhouse.skyshards.com/designer?layout=" + flows.plots().get(i).steps().getFirst().layoutCode());
				}
				linkDrafts.set(linkDrafts.size() - 1, "https://example.com/not-a-layout");
			}
			default -> page = Page.OVERVIEW;
		}
		rebuildWidgets();
	}

	// Input

	private MouseButtonEvent canvas(MouseButtonEvent event) {
		return new MouseButtonEvent(canvas(event.x()), canvas(event.y()), event.buttonInfo());
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		return super.mouseReleased(canvas(event));
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		return super.mouseDragged(canvas(event), canvas(dragX), canvas(dragY));
	}

	@Override
	public void mouseMoved(double mouseX, double mouseY) {
		super.mouseMoved(canvas(mouseX), canvas(mouseY));
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent screenEvent, boolean doubleClick) {
		MouseButtonEvent event = canvas(screenEvent);
		if (profileMenu) {
			for (int i = menuHotspots.size() - 1; i >= 0; i--) {
				Hotspot hotspot = menuHotspots.get(i);
				if (hotspot.contains(event.x(), event.y())) {
					hotspot.action().accept(event);
					Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
					return true;
				}
			}
			if (profileBox != null && profileBox.isMouseOver(event.x(), event.y())) return super.mouseClicked(event, doubleClick);
			boolean inside = event.x() >= MENU_X && event.x() < MENU_X + MENU_W && event.y() >= MENU_Y && event.y() < MENU_Y + profileMenuHeight();
			if (!inside) {
				profileMenu = false;
				if (renamingProfile) {
					renamingProfile = false;
					rebuildWidgets();
				}
			}
			return true;
		}
		if (super.mouseClicked(event, doubleClick)) return true;
		for (int i = hotspots.size() - 1; i >= 0; i--) {
			Hotspot hotspot = hotspots.get(i);
			if (hotspot.contains(event.x(), event.y())) {
				hotspot.action().accept(event);
				Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double screenX, double screenY, double scrollX, double scrollY) {
		double mouseX = canvas(screenX);
		double mouseY = canvas(screenY);
		if (super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) return true;
		int delta = scrollY > 0 ? -1 : 1;
		switch (page) {
			case FLOW -> {
				if (mouseX < contentX() + contentW() / 4.0) stepScroll += delta;
			}
			case INVENTORY -> inventoryScroll += delta;
			case IMPORT -> libraryScroll += delta;
			case LEADERBOARD -> {
				if (mouseX < contentX() + contentW() * 0.56) boardScroll += delta;
				else boardDetailScroll = Math.max(0, boardDetailScroll + delta * 12);
			}
			case STAGES -> stagesScroll += delta;
			case SETTINGS -> settingsScroll = Math.max(0, settingsScroll + delta * 20);
			case DROPS -> DropsPanel.scroll(delta);
			default -> {
			}
		}
		return true;
	}

	/** For the overview cards: Jellybeans of the greenhouse being watched, as the in-world labels show them. */
	static List<JellybeanView.Bean> beansHere() {
		JellybeanView view = GreenhouseCompanion.jellybeans();
		List<JellybeanView.Bean> beans = new ArrayList<>();
		for (int cell = 0; cell < 100; cell++) {
			JellybeanView.Bean bean = view.bean(cell);
			if (bean != null) beans.add(bean);
		}
		return beans;
	}
}
