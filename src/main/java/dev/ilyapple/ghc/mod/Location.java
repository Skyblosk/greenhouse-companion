package dev.ilyapple.ghc.mod;

import org.jspecify.annotations.Nullable;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * Whether the player is in their Garden. Like Waypointer, it listens to the Hypixel Mod API's location packet when
 * that mod is installed (the {@code map} is "Garden") and falls back to the sidebar ("The Garden" / "Plot -")
 * otherwise, or while no packet has arrived since joining.
 */
public final class Location {
	private static volatile @Nullable String apiMap;
	private static boolean apiReady;
	private boolean sidebarGarden;
	private long tick;

	void register() {
		if (FabricLoader.getInstance().isModLoaded("hypixel-mod-api")) {
			try {
				HypixelApi.subscribe(map -> apiMap = map);
				apiReady = true;
			} catch (Throwable e) {
				GreenhouseCompanion.LOGGER.warn("[GHC] Hypixel Mod API present but unusable, reading the sidebar instead", e);
			}
		}
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> apiMap = null);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> apiMap = null);
	}

	void tick(Minecraft client) {
		if (++tick % 20 == 0) sidebarGarden = GardenGrid.sidebarSaysGarden(client);
	}

	public boolean inGarden() {
		String map = apiMap;
		if (apiReady && map != null) return map.equalsIgnoreCase("Garden");
		return sidebarGarden;
	}

	/** What says so, for the status page. */
	public String source() {
		return apiReady && apiMap != null ? "Hypixel Mod API (" + apiMap + ")" : "sidebar";
	}

	/** Kept apart so the API's classes only load when the mod is installed. */
	private static final class HypixelApi {
		static void subscribe(java.util.function.Consumer<@Nullable String> onMap) {
			net.hypixel.modapi.HypixelModAPI api = net.hypixel.modapi.HypixelModAPI.getInstance();
			api.subscribeToEventPacket(net.hypixel.modapi.packet.impl.clientbound.event.ClientboundLocationPacket.class);
			api.createHandler(net.hypixel.modapi.packet.impl.clientbound.event.ClientboundLocationPacket.class,
					packet -> onMap.accept(packet.getMap().orElse(null)));
		}
	}
}
