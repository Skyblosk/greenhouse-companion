package dev.ilyapple.ghc.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraft.core.BlockPos;

import dev.ilyapple.ghc.core.LookTable;
import dev.ilyapple.ghc.core.Plants;

/** Flows files on disk, the move from state.json's library, profiles per SkyBlock profile, and forgetting greenhouses. */
class ModStateTest {
	private static final Plants PLANTS = Plants.defaults();
	private static final LookTable TABLE = LookTable.of(PLANTS, List.of());
	private static final String FLOWS = flows();
	private static final BlockPos PLOT_4 = new BlockPos(-5, GardenGrid.FLOOR_Y, 91);
	private static final BlockPos PLOT_9 = new BlockPos(-5, GardenGrid.FLOOR_Y, -197);
	private static final String UUID_A = "1a2b3c4d-1111-4222-8333-444455556666";
	private static final String UUID_B = "0f0e0d0c-0b0a-4909-8807-060504030201";

	private static String flows() {
		try {
			return Files.readString(Path.of("src/test/resources/flows/jellybean-shadevine.json")).strip();
		} catch (java.io.IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static ModState load(Path dir) {
		return ModState.load(dir, PLANTS, TABLE, "test");
	}

	@Test
	void importsAreWrittenToTheirOwnFileAtOnce(@TempDir Path dir) throws Exception {
		ModState state = load(dir);
		state.importFlows(FLOWS, "MAGA Jellybean");
		state.addToLibrary(FLOWS, "Ironman: Magic Jellybean");
		// No save() call: the files and the index are written on import
		Path folder = dir.resolve("flows");
		assertEquals(FLOWS, Files.readString(folder.resolve("MAGA Jellybean.json")).strip());
		assertTrue(Files.exists(folder.resolve("Ironman_ Magic Jellybean.json")));
		ModState again = load(dir);
		List<String> names = again.library().stream().map(ModState.FlowFile::name).toList();
		assertTrue(names.containsAll(List.of("MAGA Jellybean", "Ironman: Magic Jellybean")), names.toString());
		assertEquals("MAGA Jellybean", again.activeFile().name());
		assertFalse(Files.readString(dir.resolve("state.json")).contains("\"text\""), "the text lives in the files, not state.json");

		again.rename(names.indexOf("MAGA Jellybean"), "Jellybeans");
		assertTrue(Files.exists(folder.resolve("Jellybeans.json")));
		assertFalse(Files.exists(folder.resolve("MAGA Jellybean.json")));
		assertEquals("Jellybeans", again.activeFile().name(), "the profile follows the renamed file");
		again.remove(again.library().stream().map(ModState.FlowFile::name).toList().indexOf("Ironman: Magic Jellybean"));
		assertFalse(Files.exists(folder.resolve("Ironman_ Magic Jellybean.json")));
	}

	@Test
	void filesDroppedInTheFolderArePickedUp(@TempDir Path dir) throws Exception {
		Files.createDirectories(dir.resolve("flows"));
		Files.writeString(dir.resolve("flows").resolve("From a friend.json"), FLOWS);
		Files.writeString(dir.resolve("flows").resolve("notes.txt"), "not a flow");
		ModState state = load(dir);
		List<String> names = state.library().stream().map(ModState.FlowFile::name).toList();
		assertTrue(names.contains("From a friend"), names.toString());
		assertFalse(names.contains("notes"));
	}

	@Test
	void theOldLibraryMovesToFilesAndBecomesProfileMain(@TempDir Path dir) throws Exception {
		Files.createDirectories(dir);
		com.google.gson.JsonObject root = new com.google.gson.JsonObject();
		root.addProperty("version", 2);
		com.google.gson.JsonArray library = new com.google.gson.JsonArray();
		com.google.gson.JsonObject entry = new com.google.gson.JsonObject();
		entry.addProperty("name", "MAGA Jellybean");
		entry.addProperty("text", FLOWS);
		entry.addProperty("importedMs", 1793090567320L);
		library.add(entry);
		root.add("library", library);
		root.addProperty("active", 0);
		com.google.gson.JsonArray greenhouses = new com.google.gson.JsonArray();
		greenhouses.add("hypixel.net|-5,91");
		greenhouses.add("hypixel.net|-5,-197");
		greenhouses.add("alpha.hypixel.net|-5,91");
		root.add("greenhouses", greenhouses);
		com.google.gson.JsonObject assignments = new com.google.gson.JsonObject();
		com.google.gson.JsonObject plot1 = new com.google.gson.JsonObject();
		plot1.addProperty("plot", 1);
		plot1.addProperty("auto", true);
		plot1.addProperty("rotation", 2);
		assignments.add("hypixel.net|-5,91", plot1);
		root.add("assignments", assignments);
		Files.writeString(dir.resolve("state.json"), root.toString());

		ModState state = load(dir);
		assertTrue(Files.exists(dir.resolve("flows").resolve("MAGA Jellybean.json")));
		assertEquals(1, state.profiles().size());
		ModState.Profile main = state.viewedProfile();
		assertEquals("Main", main.name());
		assertEquals("MAGA Jellybean", main.flowName());
		assertEquals(3, state.sites(main).size());
		ModState.Site plot4 = state.siteByKey("hypixel.net|-5,91");
		assertNotNull(plot4);
		assertEquals(1, state.assignment(plot4).plotId());
		assertNotNull(plot4.greenhouse().runner(), "it still follows flow plot 1");

		// Hypixel names the SkyBlock profile: the saved greenhouses on that server become that profile's
		String scope = state.onSkyblockProfile("hypixel.net", UUID_A, null);
		assertEquals("hypixel.net#" + UUID_A, scope);
		assertNull(state.siteByKey("hypixel.net|-5,91"));
		ModState.Site moved = state.siteByKey(scope + "|-5,91");
		assertNotNull(moved);
		assertEquals(1, state.assignment(moved).plotId(), "plot, step and turn come along");
		assertEquals(2, state.assignment(moved).rotation());
		assertEquals(main, state.profileOf(moved));
		assertTrue(main.scopes().contains(scope));
		assertNotNull(state.siteByKey("alpha.hypixel.net|-5,91"), "the alpha server waits for its own Profile ID");

		// Another SkyBlock profile on the same server: a profile of its own, and the same plot is another greenhouse
		String other = state.onSkyblockProfile("hypixel.net", UUID_B, "Apple");
		assertEquals(2, state.profiles().size());
		ModState.Profile apple = state.viewedProfile();
		assertEquals("Apple", apple.name());
		assertNull(apple.flowName());
		ModState.Site sameSpot = state.site(other, PLOT_4);
		assertNotEquals(moved.key(), sameSpot.key());
		assertEquals(apple, state.profileOf(sameSpot));
		assertNull(sameSpot.greenhouse().runner(), "the new profile follows no flows yet");
		// Back on the first profile: the window shows Main again
		state.onSkyblockProfile("hypixel.net", UUID_A, null);
		assertEquals(main, state.viewedProfile());

		state.save();
		ModState again = load(dir);
		assertEquals(2, again.profiles().size());
		assertNotNull(again.siteByKey(scope + "|-5,91"));
		assertEquals("Main", again.profileOf(again.siteByKey(scope + "|-5,91")).name());
	}

	@Test
	void profilesFollowTheirOwnFlowsAndGreenhousesCanBeForgotten(@TempDir Path dir) {
		ModState state = load(dir);
		state.importFlows(FLOWS, "Shadevine");
		ModState.Profile main = state.viewedProfile();
		ModState.Site a = state.site("hypixel.net", PLOT_4);
		state.markGreenhouse(a.key());
		state.assign(a, 1, true);
		assertNotNull(a.greenhouse().runner());

		ModState.Profile ironman = state.createProfile("Ironman");
		state.view(ironman);
		assertNull(state.flows(), "a new profile follows nothing");
		assertNotNull(state.flows(a), "the other profile's greenhouse keeps its flows");
		ModState.Site b = state.site("hypixel.net", PLOT_9);
		state.markGreenhouse(b.key());
		assertEquals(ironman, state.profileOf(b), "a new greenhouse joins the profile shown");
		assertTrue(state.inactive(b));
		assertEquals(3, state.freePlots(a).size(), "a greenhouse's own plot counts as free for it");

		state.moveTo(b, main);
		assertEquals(main, state.profileOf(b));
		assertEquals(List.of(2, 3), state.freePlots(b).stream().map(dev.ilyapple.ghc.core.flow.Flows.Plot::id).toList());

		state.forget(b);
		assertFalse(state.isGreenhouse(b.key()));
		assertEquals(1, state.sites(main).size());
		state.deleteProfile(ironman);
		assertEquals(1, state.profiles().size());
	}

	@Test
	void aNewInstallGetsTheSampleFlowWithoutFollowingIt(@TempDir Path dir) {
		ModState state = load(dir);
		assertEquals(List.of(ModState.TEST_FLOW_NAME), state.library().stream().map(ModState.FlowFile::name).toList());
		assertNull(state.viewedProfile().flowName(), "nothing follows the sample until it's picked");
	}

	@Test
	void flowsFileNamesStayInTheFlowsFolder() {
		assertTrue(ModState.plainFileName("MAGA Jellybean.json"));
		assertTrue(ModState.plainFileName("hybrid shadevine (2).txt"));
		assertFalse(ModState.plainFileName("../settings.json"));
		assertFalse(ModState.plainFileName(".."));
		assertFalse(ModState.plainFileName("/etc/passwd"));
		assertFalse(ModState.plainFileName("C:\\Windows\\win.ini"));
	}
}
