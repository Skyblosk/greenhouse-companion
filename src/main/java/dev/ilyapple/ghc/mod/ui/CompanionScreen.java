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
import dev.ilyapple.ghc.core.flow.FlowCheck;
import dev.ilyapple.ghc.core.flow.FlowRunner;
import dev.ilyapple.ghc.core.flow.Flows;
import dev.ilyapple.ghc.core.schematic.Schematic;
import dev.ilyapple.ghc.core.leaderboard.Leaderboard;
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

	record Hotspot(int x, int y, int w, int h, Consumer<MouseButtonEvent> action) {
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

	private final FlowPage flow = new FlowPage(this);
	private final LeaderboardPage leaderboard = new LeaderboardPage(this);
	private final SettingsPage settingsPage = new SettingsPage(this);
	private Page page;
	private int siteIndex;
	private @Nullable String siteKey;
	private int inventoryScroll;
	private int libraryScroll;
	private int stagesScroll;
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

	void addHotspot(Hotspot hotspot) {
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

	ModState state() {
		return GreenhouseCompanion.state();
	}

	Settings settings() {
		return GreenhouseCompanion.settings();
	}

	int contentX() {
		return Theme.SIDEBAR_W + Theme.PAD;
	}

	int contentW() {
		return width - contentX() - Theme.PAD;
	}

	int contentTop() {
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
	void tooltip(GuiGraphicsExtractor g, List<Component> lines, int mouseX, int mouseY) {
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
		flow.clearWidgets();
		int x = contentX();
		int w = contentW();
		if (renamingProfile) {
			profileBox = new EditBox(font, 0, -100, 150, 14, Component.literal("Profile name"));
			profileBox.setValue(state().viewedProfile().name());
			profileBox.setMaxLength(40);
			addRenderableWidget(profileBox);
			setFocused(profileBox);
		}
		if (page == Page.FLOW) flow.buildAmountBoxes();
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
		dev.ilyapple.ghc.mod.compat.Mc.setScreen(parent);
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
	List<ModState.Site> sites() {
		List<ModState.Site> sites = new ArrayList<>(state().sites(state().viewedProfile()));
		ModState.Site current = GreenhouseCompanion.currentSite();
		sites.sort(Comparator.comparing((ModState.Site site) -> !site.equals(current)).thenComparingInt(ModState.Site::plotNumber));
		return sites;
	}

	ModState.@Nullable Site site() {
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

	void moveSite(int delta) {
		List<ModState.Site> sites = sites();
		if (sites.isEmpty()) return;
		site();
		siteIndex = Math.floorMod(siteIndex + delta, sites.size());
		siteKey = sites.get(siteIndex).key();
		flow.followCurrentStep();
		refresh();
	}

	Greenhouse.Status status(ModState.Site site) {
		long now = System.currentTimeMillis();
		if (status == null || now - statusMs > 500 || !site.key().equals(statusSite)) {
			status = site.greenhouse().status(8);
			statusMs = now;
			statusSite = site.key();
		}
		return status;
	}

	void refresh() {
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
			case FLOW -> flow.render(g, mouseX, mouseY);
			case IMPORT -> importPage(g, mouseX, mouseY);
			case LEADERBOARD -> leaderboard.render(g, mouseX, mouseY);
			case DROPS -> DropsPanel.render(g, font, contentX(), contentTop(), Math.min(contentW(), 560), height - contentTop() - Theme.PAD, mouseX, mouseY,
					(hx, hy, hw, hh, action) -> addHotspot(new Hotspot(hx, hy, hw, hh, event -> action.run())), true);
			case INVENTORY -> inventoryPage(g, mouseX, mouseY);
			case STAGES -> stagesPage(g, mouseX, mouseY);
			case SETTINGS -> settingsPage.render(g, mouseX, mouseY);
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
				flow.followCurrentStep();
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
			flow.followCurrentStep();
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
				flow.followCurrentStep();
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
			flow.followCurrentStep();
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
				ModState.Profile target = profiles.get(Math.floorMod(index + (event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT ? -1 : 1), profiles.size()));
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

	/** Applies typed amounts once their box loses focus, and makes new boxes when the step shown or the flows change. */
	@Override
	public void tick() {
		super.tick();
		// A typed inventory count is set when its box loses focus (Enter, or a click elsewhere)
		if (page == Page.INVENTORY && countBox != null && !countBox.isFocused()) applyCount();
		if (page != Page.FLOW) return;
		flow.tick();
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		int key = event.key();
		boolean enter = key == com.mojang.blaze3d.platform.InputConstants.KEY_RETURN || key == com.mojang.blaze3d.platform.InputConstants.KEY_NUMPADENTER;
		boolean escape = key == com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE;
		if (profileMenu && escape) {
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
			if (escape) {
				countItem = null;
				rebuildWidgets();
				return true;
			}
		}
		if (enter && flow.enter()) return true;
		return super.keyPressed(event);
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
				dev.ilyapple.ghc.mod.compat.Mc.openFolder(state().flowFolder());
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
					// Right click: back to the sack count (the button numbers differ by version: 26.3 counts from 1)
					if (event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT) {
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

	/** 1, or 10 with Shift, 100 with Ctrl (the game's own checks: 26.3 numbers the modifier keys differently). */
	static int step(MouseButtonEvent event) {
		if (event.hasControlDown()) return 100;
		if (event.hasShiftDown()) return 10;
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
		for (Plants.Plant plant : state().plants().all()) if (plant.grows() || plant.mutation()) plants.add(plant);
		plants.sort(Comparator.comparing((Plants.Plant plant) -> table.uncovered(plant).isEmpty()).thenComparing(Plants.Plant::rarity).thenComparing(Plants.Plant::name));

		int nameW = Math.min(150, w / 3);
		int outlineX = x + w - 92;
		Theme.small(g, font, "HIGHLIGHT", outlineX, y, Theme.MUTED);
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
			g.text(font, jellybean ? "shape" : read.size() + "/" + plant.allStages().size(), x + nameW, ry + 4, read.size() == plant.allStages().size() || jellybean ? Theme.SUCCESS : Theme.DIM, false);
			StageSet modelled = models == null ? StageSet.EMPTY : models.covered(plant);
			g.text(font, modelled.isEmpty() ? "—" : modelled.size() + "/" + plant.allStages().size(), x + nameW + 60, ry + 4, modelled.isEmpty() ? Theme.FAINT : Theme.INFO, false);
			StageSet left = table.uncovered(plant);
			String unknown = plant.id().equals(Growth.ALOE) ? "✔ hologram" : jellybean ? "✔ shape" : left.isEmpty() ? "✔ all covered" : left.toString().replace("{", "").replace("}", "");
			g.text(font, Theme.fit(font, unknown, w - nameW - 220), x + nameW + 120, ry + 4, left.isEmpty() ? Theme.SUCCESS : Theme.WARNING, false);
			// Keep controls inside the visible rows, rather than giving clipped rows clickable areas.
			if (ry + rowH <= height - Theme.PAD) {
				String outline = settings().highlightOutline(plant.id()) == Settings.HighlightOutline.BLOCK ? "Block" : "Armor stand";
				pill(g, mouseX, mouseY, outline, outlineX, ry + 1, false, Theme.INFO, () -> settings().toggleHighlightOutline(plant.id()));
				if (mouseX >= outlineX && mouseX < x + w && mouseY >= ry && mouseY < ry + rowH)
					tooltip(g, List.of(Component.literal("Click: Block / Armor stand"),
							Component.literal("Harvest + water; falls back if absent.")), mouseX, mouseY);
			}
		}
		g.disableScissor();
	}

	static @Nullable Leaderboard board() {
		return LeaderboardPage.board();
	}

	net.minecraft.client.gui.Font font() {
		return font;
	}

	void addAmountBox(EditBox box) {
		addRenderableWidget(box);
	}

	void rebuildPage() {
		rebuildWidgets();
	}

	// Small helpers

	int pill(GuiGraphicsExtractor g, int mouseX, int mouseY, String label, int x, int y, boolean active, int accent, Runnable action) {
		int pw = font.width(label) + 12;
		boolean over = mouseX >= x && mouseX < x + pw && mouseY >= y && mouseY < y + 14;
		Theme.card(g, x, y, pw, 14, active ? Theme.alpha(accent, 0x33) : over ? Theme.CARD_HOVER : Theme.CARD, active ? Theme.alpha(accent, 0x99) : Theme.BORDER);
		g.text(font, label, x + 6, y + 3, active ? accent : Theme.TEXT, false);
		addHotspot(new Hotspot(x, y, pw, 14, event -> action.run()));
		return pw + 4;
	}


	int button(GuiGraphicsExtractor g, int mouseX, int mouseY, String label, int x, int y, int accent, Runnable action) {
		int w = font.width(label) + 12;
		boolean over = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + Theme.BUTTON_H;
		Theme.card(g, x, y, w, Theme.BUTTON_H, Theme.alpha(accent, over ? 0x48 : 0x22), Theme.alpha(accent, over ? 0xCC : 0x88));
		g.text(font, label, x + 6, y + 5, over ? Theme.TEXT_BRIGHT : accent, false);
		addHotspot(new Hotspot(x, y, w, Theme.BUTTON_H, event -> action.run()));
		return w;
	}

	int smallButton(GuiGraphicsExtractor g, int mouseX, int mouseY, String label, int x, int y, int accent, Runnable action) {
		int w = font.width(label) / 2 + 8;
		boolean over = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 10;
		Theme.rounded(g, x, y, w, 10, Theme.alpha(accent, over ? 0x55 : 0x22));
		Theme.small(g, font, label, x + 4, y + 3, over ? Theme.TEXT_BRIGHT : accent);
		addHotspot(new Hotspot(x, y, w, 10, event -> action.run()));
		return w;
	}

	void stepper(GuiGraphicsExtractor g, int mouseX, int mouseY, String label, int x, int y, Consumer<MouseButtonEvent> action) {
		boolean over = mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 14;
		Theme.card(g, x, y, 16, 14, over ? Theme.CARD_HOVER : Theme.CARD_SUNK, Theme.BORDER_STRONG);
		g.text(font, label, x + 8 - font.width(label) / 2, y + 3, Theme.TEXT, false);
		addHotspot(new Hotspot(x, y, 16, 14, action));
	}

	int wrap(GuiGraphicsExtractor g, String text, int x, int y, int width, int color) {
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

	String name(String id) {
		Plants.Plant plant = state().plants().byId(id);
		return plant == null ? id : plant.name();
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
			case 2 -> flow.previewLayout();
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
				leaderboard.demo();
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
				if (mouseX < contentX() + contentW() / 4.0) flow.scroll(delta);
			}
			case INVENTORY -> inventoryScroll += delta;
			case IMPORT -> libraryScroll += delta;
			case LEADERBOARD -> {
				leaderboard.scroll(mouseX, delta);
			}
			case STAGES -> stagesScroll += delta;
			case SETTINGS -> settingsPage.scroll(delta);
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
