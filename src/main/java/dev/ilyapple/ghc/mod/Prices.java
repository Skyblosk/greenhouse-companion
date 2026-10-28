package dev.ilyapple.ghc.mod;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.jspecify.annotations.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;

import dev.ilyapple.ghc.core.Plants;
import dev.ilyapple.ghc.core.drops.Items;

/**
 * Item prices for the drop recorder's estimated value (GreenEssentials' {@code Prices}): NPC sell prices, and the
 * Bazaar's instant-sell price (the top buy order). Base crops' NPC prices ship with the mod (the simulator's
 * {@code data.json}); everything else comes from Hypixel's public, keyless endpoints {@code /v2/skyblock/bazaar} and
 * {@code /v2/resources/skyblock/items}, fetched at most every 15 minutes while the recorder is on screen and cached in
 * {@code config/greenhouse-companion/prices.json} for when they can't be reached. A compacted form's price per base
 * unit counts when it's higher (Enchanted Wheat ÷ 160).
 */
public final class Prices {
	public enum Source { NPC, BAZAAR }

	private static final long REFRESH_MS = 15 * 60_000;
	private static final Map<String, String> CROP_ITEMS = Map.ofEntries(
			Map.entry("wheat", "WHEAT"), Map.entry("potato", "POTATO_ITEM"), Map.entry("carrot", "CARROT_ITEM"), Map.entry("pumpkin", "PUMPKIN"),
			Map.entry("melon", "MELON"), Map.entry("cocoa_beans", "INK_SACK:3"), Map.entry("sugar_cane", "SUGAR_CANE"), Map.entry("cactus", "CACTUS"),
			Map.entry("nether_wart", "NETHER_STALK"), Map.entry("red_mushroom", "RED_MUSHROOM"), Map.entry("brown_mushroom", "BROWN_MUSHROOM"),
			Map.entry("moonflower", "MOONFLOWER"), Map.entry("sunflower", "DOUBLE_PLANT"), Map.entry("wild_rose", "WILD_ROSE"));

	private static final Map<String, Double> bundledNpc = new HashMap<>();
	private static volatile Map<String, Double> npc = Map.of();
	private static volatile Map<String, Double> bazaar = Map.of();
	private static volatile long fetchedMs;
	private static volatile String status = "Base crops only (NPC, bundled)";
	private static long nextTryMs;
	private static boolean pending;
	private static @Nullable Path cache;
	/** Prices looked up since the tables last changed (NaN: none known). */
	private static final Map<String, Double> lookups = new java.util.concurrent.ConcurrentHashMap<>();

	private Prices() {}

	static void init(Path configDir) {
		try (InputStream in = Prices.class.getResourceAsStream(Plants.RESOURCE)) {
			if (in != null) {
				JsonObject crops = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("crops");
				CROP_ITEMS.forEach((crop, item) -> {
					JsonObject json = crops.getAsJsonObject(crop);
					if (json != null && json.has("sell_price") && !json.get("sell_price").isJsonNull()) bundledNpc.put(item, json.get("sell_price").getAsDouble());
				});
			}
		} catch (IOException | RuntimeException e) {
			GreenhouseCompanion.LOGGER.warn("[GHC] Could not read the bundled crop prices", e);
		}
		cache = configDir.resolve("greenhouse-companion").resolve("prices.json");
		try {
			if (Files.exists(cache)) {
				JsonObject saved = JsonParser.parseString(Files.readString(cache, StandardCharsets.UTF_8)).getAsJsonObject();
				npc = read(saved.getAsJsonObject("npc"));
				bazaar = read(saved.getAsJsonObject("bazaar"));
				fetchedMs = saved.has("fetchedMs") ? saved.get("fetchedMs").getAsLong() : 0;
				status = "Saved prices from " + dev.ilyapple.ghc.mod.ui.CompanionScreen.duration(System.currentTimeMillis() - fetchedMs) + " ago";
			}
		} catch (IOException | RuntimeException e) {
			GreenhouseCompanion.LOGGER.warn("[GHC] Could not read {}", cache, e);
		}
	}

	public static String status() {
		return status;
	}

	/** One base unit of {@code item}, or {@code null} when no price is known. */
	public static @Nullable Double price(String item, Source source) {
		double found = lookups.computeIfAbsent(source + "|" + item, key -> lookup(item, source));
		return Double.isNaN(found) ? null : found;
	}

	private static double lookup(String item, Source source) {
		Map<String, Double> table = source == Source.NPC ? npc : bazaar;
		double best = table.getOrDefault(item, source == Source.NPC ? bundledNpc.getOrDefault(item, 0.0) : 0.0);
		for (Map.Entry<String, Double> entry : table.entrySet()) {
			Items.Unit unit = Items.unit(entry.getKey());
			if (unit.amount() > 1 && unit.base().equals(item)) best = Math.max(best, entry.getValue() / unit.amount());
		}
		return best > 0 && Double.isFinite(best) ? best : Double.NaN;
	}

	/** Fetches new prices when the old ones are 15 minutes old. Call while the recorder is on screen. */
	public static void refresh() {
		long now = System.currentTimeMillis();
		if (pending || now < nextTryMs || now - fetchedMs < REFRESH_MS) return;
		pending = true;
		nextTryMs = now + 60_000;
		CompletableFuture.supplyAsync(Prices::fetch).whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
			pending = false;
			if (error != null || result == null) {
				status = (npc.isEmpty() ? "Base crops only (NPC, bundled)" : "Saved prices") + " · couldn't reach api.hypixel.net";
				return;
			}
			npc = result[0];
			bazaar = result[1];
			lookups.clear();
			fetchedMs = System.currentTimeMillis();
			status = "NPC and Bazaar prices from api.hypixel.net";
			save();
		}));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Double>[] fetch() {
		try {
			HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
			JsonObject items = get(http, "https://api.hypixel.net/v2/resources/skyblock/items");
			Map<String, Double> npcPrices = new HashMap<>();
			for (JsonElement element : items.getAsJsonArray("items")) {
				JsonObject item = element.getAsJsonObject();
				if (item.has("npc_sell_price") && item.has("id")) npcPrices.put(item.get("id").getAsString(), item.get("npc_sell_price").getAsDouble());
			}
			JsonObject products = get(http, "https://api.hypixel.net/v2/skyblock/bazaar").getAsJsonObject("products");
			Map<String, Double> market = new HashMap<>();
			for (Map.Entry<String, JsonElement> entry : products.entrySet()) {
				// Hypixel calls this side sell_summary: the price of selling at once into buy orders
				var orders = entry.getValue().getAsJsonObject().getAsJsonArray("sell_summary");
				if (orders != null && !orders.isEmpty()) market.put(entry.getKey(), orders.get(0).getAsJsonObject().get("pricePerUnit").getAsDouble());
			}
			return new Map[] {npcPrices, market};
		} catch (IOException | RuntimeException e) {
			throw new IllegalStateException(e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	private static JsonObject get(HttpClient http, String url) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("User-Agent", "GreenhouseCompanion").GET().build();
		HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + " from " + url);
		JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
		if (!json.has("success") || !json.get("success").getAsBoolean()) throw new IOException("Not successful: " + url);
		return json;
	}

	private static Map<String, Double> read(@Nullable JsonObject json) {
		Map<String, Double> map = new HashMap<>();
		if (json != null) json.entrySet().forEach(entry -> map.put(entry.getKey(), entry.getValue().getAsDouble()));
		return map;
	}

	private static void save() {
		if (cache == null) return;
		JsonObject root = new JsonObject();
		root.addProperty("fetchedMs", fetchedMs);
		JsonObject npcJson = new JsonObject();
		npc.forEach(npcJson::addProperty);
		JsonObject bazaarJson = new JsonObject();
		bazaar.forEach(bazaarJson::addProperty);
		root.add("npc", npcJson);
		root.add("bazaar", bazaarJson);
		try {
			Files.createDirectories(cache.getParent());
			Path temp = cache.resolveSibling("prices.json.tmp");
			Files.writeString(temp, root.toString(), StandardCharsets.UTF_8);
			Files.move(temp, cache, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			GreenhouseCompanion.LOGGER.warn("[GHC] Could not save {}", cache, e);
		}
	}
}
