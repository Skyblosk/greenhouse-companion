package dev.ilyapple.ghc.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

/**
 * Checks the converted MagicAddons models against the stage recorder's readings: for every reading of a plant the
 * book has models for, the models must either not recognise the look or allow the stage the menu said.
 */
class ModelAgreementTest {
	private static final Path CALIBRATION = Path.of("src/main/resources/assets/greenhouse-companion/calibration.json");

	@Test
	void modelsAgreeWithTheRecorder() throws IOException {
		Plants plants = Plants.defaults();
		ModelBook book = ModelBook.bundled();
		Map<String, int[]> perPlant = new TreeMap<>();
		int matched = 0;
		int agree = 0;
		StringBuilder disagreements = new StringBuilder();
		for (LookTable.Observation observation : LookTable.readCalibration(plants, CALIBRATION)) {
			Plants.Plant plant = plants.byId(observation.plantId());
			if (plant == null || !book.has(plant.id()) || !plant.grows()) continue;
			int[] counts = perPlant.computeIfAbsent(plant.name(), name -> new int[3]);
			StageSet stages = book.stages(plant, observation.fingerprint());
			StageSet truth = observation.fullyGrown() ? Growth.fullyGrownStages(plant) : StageSet.of(observation.stage());
			if (stages == null) {
				counts[0]++;
				continue;
			}
			matched++;
			if (!stages.intersect(truth).isEmpty()) {
				agree++;
				counts[1]++;
			} else {
				counts[2]++;
				if (disagreements.length() < 4000) disagreements.append(plant.name()).append(" read ").append(truth).append(", models say ").append(stages).append('\n');
			}
		}
		StringBuilder report = new StringBuilder("plant: not recognised / agree / disagree\n");
		perPlant.forEach((name, counts) -> report.append(name).append(": ").append(counts[0]).append(" / ").append(counts[1]).append(" / ").append(counts[2]).append('\n'));
		System.out.println(report);
		System.out.println(disagreements);
		assertTrue(matched > 100, "the models should recognise many recorded looks, recognised " + matched);
		assertTrue(agree >= matched * 0.97, agree + " of " + matched + " recognised looks agree");
	}

	/** Prints, per plant, the stages neither the recorder nor the models have a look for: what's left to record. */
	@Test
	void printWhatIsLeftToRecord() throws IOException {
		Plants plants = Plants.defaults();
		LookTable table = LookTable.fromCalibration(plants, CALIBRATION).withModels(ModelBook.bundled());
		StringBuilder left = new StringBuilder("Still unknown:\n");
		for (Plants.Plant plant : plants.all()) {
			if (!plant.grows()) continue;
			StageSet uncovered = table.uncovered(plant);
			if (!uncovered.isEmpty()) left.append(plant.name()).append(" (").append(plant.maxStage()).append(" stages): ").append(uncovered).append('\n');
		}
		System.out.println(left);
	}
}
