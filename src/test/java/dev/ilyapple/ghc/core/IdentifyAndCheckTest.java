package dev.ilyapple.ghc.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.ilyapple.ghc.core.flow.FlowCheck;
import dev.ilyapple.ghc.core.flow.Flows;
import dev.ilyapple.ghc.core.flow.LayoutMatch;
import dev.ilyapple.ghc.core.schematic.Schematic;
import dev.ilyapple.ghc.core.schematic.SkyblockerCrops;

class IdentifyAndCheckTest {
	private static Flows flows(String name) throws IOException {
		return Flows.parse(Files.readString(Path.of("src/test/resources/flows/" + name)));
	}

	/** What a greenhouse holds when every placement of the layout is in place. */
	private static Map<Integer, String> planted(Schematic layout) {
		Map<Integer, String> cells = new HashMap<>();
		layout.placements().forEach((cell, crop) -> {
			SkyblockerCrops.Crop known = SkyblockerCrops.byId(crop);
			if (known != null && known.simId() != null) cells.put(cell, known.simId());
		});
		return cells;
	}

	@Test
	void findsTheStepWhoseLayoutIsPlanted() throws IOException {
		Flows flows = flows("hybrid-aloe-flow.json");
		Flows.Step second = flows.plots().getFirst().steps().get(1);
		LayoutMatch.Verdict verdict = LayoutMatch.identify(flows, planted(second.schematic()));
		assertNotNull(verdict.best());
		assertEquals(1, verdict.best().stepIndex());
		assertTrue(verdict.confident());
	}

	@Test
	void oneLinkPerPlotKeepsThePlotNumbers() throws IOException {
		Flows source = flows("hybrid-aloe-flow.json");
		String first = source.plots().getFirst().steps().getFirst().layoutCode();
		String second = source.plots().getFirst().steps().get(1).layoutCode();
		Map<Integer, String> links = new HashMap<>();
		links.put(1, "https://greenhouse.skyshards.com/designer?layout=" + first);
		links.put(2, "  ");
		links.put(3, "https://api.skyshards.com/share/" + second);
		Flows flows = Flows.parse(Flows.fromPlotLinks(links));
		assertEquals(List.of(1, 3), flows.plots().stream().map(Flows.Plot::id).toList());
		assertEquals(1, flows.plots().getFirst().steps().size());
		assertTrue(flows.plots().getFirst().steps().getFirst().schematic().sameAs(Schematic.fromLink(first)));
		assertTrue(flows.plots().get(1).steps().getFirst().schematic().sameAs(Schematic.fromLink(second)));
		assertTrue(flows.plots().get(1).steps().getFirst().exits().isEmpty());
		Map<Integer, String> bad = Map.of(1, "https://example.com/not-a-layout");
		org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> Flows.fromPlotLinks(bad));
		org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> Flows.fromPlotLinks(Map.of(1, "")));
	}

	@Test
	void plotsWithTheSameLayoutsAreAllRight() throws IOException {
		Flows flows = flows("jellybean-buffs-flow.json");
		Flows.Step snooze = flows.plots().get(1).steps().get(1);
		LayoutMatch.Verdict verdict = LayoutMatch.identify(flows, planted(snooze.schematic()));
		assertTrue(verdict.confident(), "the three plots run the same layouts, so any of them is a confident match");
		assertEquals(1, verdict.best().stepIndex());
	}

	/** The flow preloaded for testing: plots 1 and 2 run the same layouts, plot 3 a Duskbloom variant. */
	@Test
	void identifiesTheJellybeanShadevineStep() throws IOException {
		Flows flows = flows("jellybean-shadevine.json");
		Flows.Step growing = flows.plots().getFirst().steps().get(1);
		assertEquals("Jellybean + Shadevine", growing.label());
		LayoutMatch.Verdict verdict = LayoutMatch.identify(flows, planted(growing.schematic()));
		assertTrue(verdict.confident());
		assertEquals(1, verdict.best().stepIndex());
		assertTrue(verdict.best().plotId() == 1 || verdict.best().plotId() == 2, "plot " + verdict.best().plotId());
		// No errors: every exit is one the mod can follow
		assertTrue(FlowCheck.check(flows, Plants.defaults()).stream().noneMatch(problem -> problem.level() == FlowCheck.Level.ERROR));
	}

	@Test
	void anEmptyGreenhouseMatchesNothing() throws IOException {
		LayoutMatch.Verdict verdict = LayoutMatch.identify(flows("hybrid-aloe-flow.json"), Map.of());
		assertFalse(verdict.confident());
	}

	@Test
	void checksFlagWhatCantBeFollowed() {
		Flows flows = Flows.parse("""
				{"kind": "skyshards-greenhouse-flows", "version": 1, "plots": [{"id": 1, "flow": {"loop": true, "startIndex": 0, "steps": [
				  {"id": "a", "label": "A", "layout": {"code": ""}, "exits": [{"to": "gone", "when": [{"kind": "decayImminent", "withinCycles": 2}]}]},
				  {"id": "b", "label": "B", "layout": {"code": ""}, "exits": [{"when": [{"kind": "madeUp"}, {"kind": "fullyGrown", "mutationId": "nope"}]}]}
				]}, "script": {"source": "plot.hold()", "enabled": true}}]}""");
		List<FlowCheck.Problem> problems = FlowCheck.check(flows, Plants.defaults());
		String all = problems.stream().map(FlowCheck.Problem::text).reduce("", (a, b) -> a + "\n" + b);
		assertTrue(all.contains("no longer exists"), all);
		assertTrue(all.contains("can't be seen in game"), all);
		assertTrue(all.contains("Unknown condition"), all);
		assertTrue(all.contains("isn't a plant the mod knows"), all);
		assertTrue(all.contains("plot script"), all);
	}
}
