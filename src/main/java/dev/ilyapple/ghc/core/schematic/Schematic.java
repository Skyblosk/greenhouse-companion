package dev.ilyapple.ghc.core.schematic;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import org.jspecify.annotations.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/**
 * A greenhouse layout in Skyblocker's schematic format: the 10x10 {@code targetGreenhouse} grid that
 * {@code /skyblocker garden greenhouse paste} previews, indexed {@code [x][z]} from the greenhouse's north-west
 * corner (the same corner and axes as the stage recorder's cells). A cell is -1 where the layout says nothing,
 * 0 for a spot a mutation should spawn in, or a {@link SkyblockerCrops} id.
 *
 * <p>Decoding is a copy of Skyblocker's {@code GreenhousePaste.extractLayoutCode}, {@code SkyShardsLayout.decode}
 * and {@code GreenhousePaste.importGreenhouse}, so the mod and Skyblocker read a link the same way. On top of the
 * grid it keeps what Skyblocker throws away: which mutation each target spot is for, and the top-left corner of
 * every placement (a 2x2 or 3x3 plant fills several cells but is one plant).
 *
 * <p>It also keeps the ground tiles a SkyShards layout sets (digits 0–5 in its grid) and can be turned by quarter
 * turns, for a greenhouse planted the layout's way round but facing another direction.
 *
 * @param ground   explicit ground per cell ({@code farmland}, {@code sand}, ...), where the layout sets one
 * @param code     the layout code the schematic came from, so it can be handed to Skyblocker unchanged
 * @param rotation quarter turns clockwise (seen from above) from the layout as drawn; 0 for the code itself
 */
public record Schematic(int[][] grid, Map<Integer, Integer> targets, Map<Integer, Integer> placements, Map<Integer, String> ground, String code,
		Source source, int rotation) {
	public enum Source { SKY_SHARDS, SKY_MUTATIONS }

	/** SkyShards' ground tiles, by the digit its layout grid uses for them. */
	public static final String[] GROUNDS = {"farmland", "sand", "soul_sand", "mycelium", "netherrack", "end_stone"};

	public Schematic(int[][] grid, Map<Integer, Integer> targets, Map<Integer, Integer> placements, String code, Source source) {
		this(grid, targets, placements, Map.of(), code, source, 0);
	}

	/**
	 * This layout turned {@code quarterTurns} quarter turns clockwise seen from above (Skyblocker's "rotate right":
	 * cell {@code (x, z)} moves to {@code (9 - z, x)}). A 2x2 or 3x3 plant keeps its top-left anchor.
	 */
	public Schematic rotated(int quarterTurns) {
		int turns = Math.floorMod(quarterTurns, 4);
		if (turns == 0) return this;
		int[][] grid = this.grid;
		Map<Integer, Integer> targets = this.targets;
		Map<Integer, Integer> placements = this.placements;
		Map<Integer, String> ground = this.ground;
		for (int turn = 0; turn < turns; turn++) {
			int[][] next = new int[SIZE][SIZE];
			for (int x = 0; x < SIZE; x++) for (int z = 0; z < SIZE; z++) next[SIZE - 1 - z][x] = grid[x][z];
			grid = next;
			targets = turnAnchors(targets);
			placements = turnAnchors(placements);
			Map<Integer, String> turned = new LinkedHashMap<>();
			ground.forEach((cell, tile) -> turned.put(cell(SIZE - 1 - cell % SIZE, cell / SIZE), tile));
			ground = turned;
		}
		return new Schematic(grid, targets, placements, ground, code, source, (rotation + turns) % 4);
	}

	private static Map<Integer, Integer> turnAnchors(Map<Integer, Integer> anchors) {
		Map<Integer, Integer> turned = new LinkedHashMap<>();
		anchors.forEach((cell, cropId) -> {
			int size = SkyblockerCrops.sizeOf(cropId);
			int x = cell / SIZE;
			int z = cell % SIZE;
			// The footprint x..x+size-1, z..z+size-1 lands on x' = 9-z-(size-1)..9-z, z' = x..x+size-1
			turned.put(cell(SIZE - 1 - z - (size - 1), x), cropId);
		});
		return turned;
	}

	/** Same plants in the same places, whatever the code or turn that produced them. */
	public boolean sameAs(@Nullable Schematic other) {
		return other != null && Arrays.deepEquals(grid, other.grid) && targets.equals(other.targets) && placements.equals(other.placements);
	}

	public static final int SIZE = 10;
	private static final int TOTAL_CELLS = SIZE * SIZE;
	private static final int LETTERS = 26;
	private static final int MAX_DECOMPRESSED_BYTES = 4096;
	private static final int PALETTE_CROPS = 17;
	private static final int MUTATION_COUNT = 40;
	// SkyShards lists the plantable crops first, in its own order, before every mutation
	private static final String[] PALETTE_CROP_NAMES = {
			"Wheat Seeds", "Potato", "Carrot", "Pumpkin Seeds", "Melon Seeds", "Cocoa Beans", "Sugar Cane", "Cactus",
			"Nether Wart", "Red Mushroom", "Brown Mushroom", "Moonflower", "Sunflower", "Wild Rose", "Fire", "Dead Plants", "Fermento"};

	/** Cell index used everywhere in this mod: {@code x * 10 + z}. */
	public static int cell(int x, int z) {
		return x * SIZE + z;
	}

	public int at(int x, int z) {
		return grid[x][z];
	}

	/** The crop a layout cell wants placed, or {@code null} for an ignored or target cell. */
	public SkyblockerCrops.@Nullable Crop cropAt(int x, int z) {
		int id = grid[x][z];
		return id > 0 ? SkyblockerCrops.byId(id) : null;
	}

	/** How many of each crop the layout places, by simulator id: one per plant, not per cell. */
	public Map<String, Integer> inputCounts() {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (int cropId : placements.values()) {
			SkyblockerCrops.Crop crop = SkyblockerCrops.byId(cropId);
			if (crop != null && crop.simId() != null) counts.merge(crop.simId(), 1, Integer::sum);
		}
		return counts;
	}

	/** How many target spots each mutation has, by simulator id. */
	public Map<String, Integer> targetCounts() {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (int cropId : targets.values()) {
			SkyblockerCrops.Crop crop = SkyblockerCrops.byId(cropId);
			if (crop != null && crop.simId() != null) counts.merge(crop.simId(), 1, Integer::sum);
		}
		return counts;
	}

	/** Reads a SkyShards designer or share link, a SkyMutations link, or a bare code, like Skyblocker's paste. */
	public static @Nullable Schematic fromLink(String text) {
		String code = extractLayoutCode(text);
		if (code.isEmpty()) return null;
		String host = hostOf(text);
		if (host.contains("skymutations")) return fromSkyMutations(code);
		if (host.contains("skyshards")) return fromSkyShards(code);
		// SkyShards codes validate strictly, so try them first and fall back to the SkyMutations format
		Schematic schematic = fromSkyShards(code);
		return schematic != null ? schematic : fromSkyMutations(code);
	}

	/** Skyblocker's {@code extractLayoutCode}: both sites put the code in a {@code layout} parameter or after {@code /share/}. */
	public static String extractLayoutCode(String clipboard) {
		String trimmed = clipboard.strip();
		int layoutIndex = trimmed.indexOf("layout=");
		if (layoutIndex >= 0) return endOfCode(trimmed.substring(layoutIndex + "layout=".length()));
		int shareIndex = trimmed.indexOf("/share/");
		if (shareIndex >= 0) return endOfCode(trimmed.substring(shareIndex + "/share/".length()));
		return trimmed;
	}

	private static String endOfCode(String code) {
		for (int i = 0; i < code.length(); i++) {
			char c = code.charAt(i);
			boolean valid = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '_' || c == '=' || c == '+';
			if (!valid) return code.substring(0, i);
		}
		return code;
	}

	private static String hostOf(String clipboard) {
		String host = clipboard.strip().toLowerCase(Locale.ENGLISH);
		int schemeEnd = host.indexOf("://");
		if (schemeEnd >= 0) host = host.substring(schemeEnd + "://".length());
		for (int i = 0; i < host.length(); i++) {
			char c = host.charAt(i);
			if (c == '/' || c == '?' || c == '#') return host.substring(0, i);
		}
		return host;
	}

	/** Skyblocker's {@code SkyShardsLayout.decode}: a raw-deflated, base64url {@code inputs|targets|grid} string. */
	public static @Nullable Schematic fromSkyShards(String code) {
		try {
			String gridString = decompress(code);
			if (gridString == null) return null;
			String[] parts = gridString.split("\\|", -1);
			if (parts.length != 3) return null;
			int[] inputPalette = parsePalette(parts[0]);
			int[] targetPalette = parsePalette(parts[1]);
			if (inputPalette == null || targetPalette == null) return null;

			String grid = parts[2];
			int cellWidth = grid.length() == TOTAL_CELLS ? 1 : grid.length() == TOTAL_CELLS * 2 ? 2 : 0;
			if (cellWidth == 0) return null;

			// Decoded in screen space first, so placements expand from their top left corner
			int[][] screen = new int[SIZE][SIZE];
			for (int[] row : screen) Arrays.fill(row, -1);
			Map<Integer, Integer> targets = new LinkedHashMap<>();
			Map<Integer, Integer> placements = new LinkedHashMap<>();
			Map<Integer, String> ground = new LinkedHashMap<>();
			for (int index = 0; index < TOTAL_CELLS; index++) {
				String chars = grid.substring(index * cellWidth, (index + 1) * cellWidth);
				if (Character.isDigit(chars.charAt(0))) {
					int tile = chars.charAt(0) - '0';
					if (tile < GROUNDS.length) ground.put(cell(index % SIZE, index / SIZE), GROUNDS[tile]);
					continue;
				}
				int paletteIndex = paletteIndexOf(chars);
				if (paletteIndex < 0) continue;
				boolean target = chars.equals(chars.toUpperCase(Locale.ENGLISH));
				int[] palette = target ? targetPalette : inputPalette;
				if (paletteIndex >= palette.length) continue;

				int cropId = palette[paletteIndex];
				int value = target ? 0 : cropId;
				int row = index / SIZE;
				int col = index % SIZE;
				// The top of the layout faces north, so rows run along +z and columns along +x
				(target ? targets : placements).put(cell(col, row), cropId);
				int size = SkyblockerCrops.sizeOf(cropId);
				for (int dr = 0; dr < size && row + dr < SIZE; dr++) {
					for (int dc = 0; dc < size && col + dc < SIZE; dc++) {
						screen[row + dr][col + dc] = value;
					}
				}
			}

			int[][] result = new int[SIZE][SIZE];
			for (int row = 0; row < SIZE; row++) {
				for (int col = 0; col < SIZE; col++) result[col][row] = screen[row][col];
			}
			return new Schematic(result, targets, placements, ground, code, Source.SKY_SHARDS, 0);
		} catch (Exception e) {
			return null;
		}
	}

	/** Encodes plant anchors in the same SkyShards format we import. */
	public static Schematic fromAnchors(Map<Integer, Integer> placements, Map<Integer, Integer> targets) {
		List<Integer> inputs = placements.values().stream().distinct().sorted().toList();
		List<Integer> spots = targets.values().stream().distinct().sorted().toList();
		StringBuilder tiles = new StringBuilder("..".repeat(TOTAL_CELLS));
		for (Map<Integer, Integer> anchors : List.of(placements, targets)) {
			boolean target = anchors == targets;
			List<Integer> palette = target ? spots : inputs;
			for (var entry : anchors.entrySet()) {
				int cell = entry.getKey();
				int size = SkyblockerCrops.sizeOf(entry.getValue());
				if (cell < 0 || cell >= TOTAL_CELLS || cell / SIZE + size > SIZE || cell % SIZE + size > SIZE)
					throw new IllegalArgumentException("Plant footprint outside the greenhouse");
				int index = palette.indexOf(entry.getValue());
				char base = target ? 'A' : 'a';
				int offset = (cell % SIZE * SIZE + cell / SIZE) * 2;
				tiles.setCharAt(offset, (char) (base + index / LETTERS));
				tiles.setCharAt(offset + 1, (char) (base + index % LETTERS));
			}
		}
		String raw = encodePalette(inputs) + "|" + encodePalette(spots) + "|" + tiles;
		java.util.zip.Deflater deflater = new java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true);
		try {
			java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
			try (var out = new java.util.zip.DeflaterOutputStream(bytes, deflater)) {
				out.write(raw.getBytes(StandardCharsets.UTF_8));
			}
			String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
			return Objects.requireNonNull(fromSkyShards(code));
		} catch (java.io.IOException e) {
			throw new IllegalStateException("Could not encode layout", e);
		} finally {
			deflater.end();
		}
	}

	private static String encodePalette(List<Integer> palette) {
		List<String> values = new ArrayList<>();
		for (int id : palette) {
			int index = id >= 1 && id <= MUTATION_COUNT ? PALETTE_CROPS + id - 1 : -1;
			for (int i = 0; i < PALETTE_CROP_NAMES.length; i++) {
				if (Objects.requireNonNull(SkyblockerCrops.byName(PALETTE_CROP_NAMES[i])).id() == id) index = i;
			}
			if (index < 0) throw new IllegalArgumentException("Unsupported crop " + id);
			values.add(Integer.toString(index, 36));
		}
		return String.join(",", values);
	}

	private static @Nullable String decompress(String code) throws Exception {
		byte[] compressed = Base64.getUrlDecoder().decode(code);
		try (InputStream in = new InflaterInputStream(new ByteArrayInputStream(compressed), new Inflater(true))) {
			byte[] decompressed = in.readNBytes(MAX_DECOMPRESSED_BYTES);
			if (in.read() != -1) return null;
			return new String(decompressed, StandardCharsets.UTF_8);
		}
	}

	private static int @Nullable [] parsePalette(String palette) {
		if (palette.isEmpty()) return new int[0];
		String[] indices = palette.split(",", -1);
		int[] cropIds = new int[indices.length];
		for (int i = 0; i < indices.length; i++) {
			int index;
			try {
				index = Integer.parseInt(indices[i], 36);
			} catch (NumberFormatException e) {
				return null;
			}
			if (index < 0 || index >= PALETTE_CROPS + MUTATION_COUNT) return null;
			cropIds[i] = index < PALETTE_CROPS
					? Objects.requireNonNull(SkyblockerCrops.byName(PALETTE_CROP_NAMES[index]), PALETTE_CROP_NAMES[index]).id()
					: index - PALETTE_CROPS + 1;
		}
		return cropIds;
	}

	private static int paletteIndexOf(String chars) {
		int index = 0;
		for (int i = 0; i < chars.length(); i++) {
			char c = Character.toLowerCase(chars.charAt(i));
			if (c < 'a' || c > 'z') return -1;
			index = index * LETTERS + (c - 'a');
		}
		// Mixed case is neither an input nor a target
		if (!chars.equals(chars.toUpperCase(Locale.ENGLISH)) && !chars.equals(chars.toLowerCase(Locale.ENGLISH))) return -1;
		return index;
	}

	/**
	 * Skyblocker's {@code importGreenhouse}: an lz-string JSON array of {@code [x, z, "crop name", value]}, where value
	 * 0 marks a mutation spot. SkyMutations counts x from the other side, hence {@code 9 - x}.
	 */
	public static @Nullable Schematic fromSkyMutations(String code) {
		try {
			String json = LZString.decompressFromEncodedURIComponent(code);
			if (json == null || json.isEmpty()) return null;
			JsonArray entries = JsonParser.parseString(json).getAsJsonArray();
			int[][] grid = new int[SIZE][SIZE];
			for (int[] column : grid) Arrays.fill(column, -1);
			Map<Integer, Integer> targets = new LinkedHashMap<>();
			Map<Integer, Integer> placements = new LinkedHashMap<>();
			for (JsonElement element : entries) {
				JsonArray entry = element.getAsJsonArray();
				int x = entry.get(0).getAsInt();
				int z = entry.get(1).getAsInt();
				String cropName = entry.get(2).getAsString();
				int value = entry.get(3).getAsInt();
				if (x < 0 || x >= SIZE || z < 0 || z >= SIZE) continue;
				SkyblockerCrops.Crop crop = SkyblockerCrops.byName(cropName);
				if (value == 0) {
					grid[9 - x][z] = 0;
					if (crop != null) targets.put(cell(9 - x, z), crop.id());
					continue;
				}
				if (crop == null) continue;
				grid[9 - x][z] = crop.id();
				placements.put(cell(9 - x, z), crop.id());
			}
			return new Schematic(grid, targets, placements, code, Source.SKY_MUTATIONS);
		} catch (Exception e) {
			return null;
		}
	}

	/** Skyblocker's debug print: one line per z, x across. */
	public String describe() {
		List<String> lines = new ArrayList<>();
		for (int z = 0; z < SIZE; z++) {
			StringBuilder row = new StringBuilder();
			for (int x = 0; x < SIZE; x++) row.append(grid[x][z] == -1 ? " - " : String.format("%2d ", grid[x][z]));
			lines.add(row.toString());
		}
		return String.join("\n", lines);
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof Schematic schematic && Arrays.deepEquals(grid, schematic.grid) && targets.equals(schematic.targets)
				&& placements.equals(schematic.placements);
	}

	@Override
	public int hashCode() {
		return Arrays.deepHashCode(grid) * 31 + targets.hashCode();
	}
}
