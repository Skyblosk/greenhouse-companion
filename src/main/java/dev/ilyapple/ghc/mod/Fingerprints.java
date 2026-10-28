package dev.ilyapple.ghc.mod;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.properties.Property;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Rotations;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import dev.ilyapple.ghc.mod.mixin.BlockDisplayInvoker;
import dev.ilyapple.ghc.mod.mixin.TextDisplayInvoker;

/**
 * The stage recorder's {@code Fingerprinter.fingerprint}: one cell's entities and blocks as the JSON the
 * calibration was recorded from, so {@link dev.ilyapple.ghc.core.LookKeys} gives live looks the same keys.
 * Change both copies together.
 */
public final class Fingerprints {
	private static final EquipmentSlot[] OTHER_SLOTS = {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
	private static final int MAX_BLOCKS = 13;
	private static final Map<String, String> TEXTURE_CACHE = new HashMap<>();

	private Fingerprints() {}

	/** Armor stands, displays and interactions, minus pest name tags (armor stands showing health with ❤). */
	public static boolean isModelEntity(Entity entity) {
		if (entity instanceof ArmorStand && entity.getCustomName() != null && entity.getCustomName().getString().contains("❤")) return false;
		return entity instanceof ArmorStand || entity instanceof Display || entity instanceof Interaction;
	}

	public static JsonObject fingerprint(Level level, BlockPos corner, int cell, List<Entity> entities) {
		int worldX = corner.getX() + GardenGrid.cellX(cell);
		int worldZ = corner.getZ() + GardenGrid.cellZ(cell);

		JsonArray entityArray = new JsonArray();
		for (Entity entity : entities) {
			JsonObject json = new JsonObject();
			json.addProperty("dy", round16(entity.getY() - GardenGrid.FLOOR_Y));
			String name = textOf(entity.getCustomName());

			switch (entity) {
				case ArmorStand stand -> {
					ItemStack head = stand.getItemBySlot(EquipmentSlot.HEAD);
					JsonObject slots = new JsonObject();
					for (EquipmentSlot slot : OTHER_SLOTS) {
						ItemStack stack = stand.getItemBySlot(slot);
						if (!stack.isEmpty()) slots.add(slot.getSerializedName(), item(stack));
					}
					// Blank markers and stands that only hold an item (a pet following the player) aren't plant parts
					if (head.isEmpty() && (!slots.isEmpty() || name.isEmpty())) continue;

					String tex = textureHash(head);
					json.addProperty("kind", "armor_stand");
					json.addProperty("head", head.isEmpty() ? "" : head.getHoverName().getString());
					if (!head.isEmpty()) json.addProperty("headItem", itemId(head));
					if (tex != null) json.addProperty("tex", tex);
					json.addProperty("small", stand.isSmall());
					json.addProperty("invisible", stand.isInvisible());
					json.addProperty("marker", stand.isMarker());
					json.add("pose", rotations(stand.getHeadPose()));
					if (!slots.isEmpty()) {
						json.add("slots", slots);
						json.add("rightArm", rotations(stand.getRightArmPose()));
						json.add("leftArm", rotations(stand.getLeftArmPose()));
					}
				}
				case Display.ItemDisplay display -> {
					json.addProperty("kind", "item_display");
					json.add("item", item(display.getSlot(0).get()));
				}
				case Display.BlockDisplay display -> {
					json.addProperty("kind", "block_display");
					json.addProperty("block", blockState(((BlockDisplayInvoker) display).ghc$getBlockState()));
				}
				case Display.TextDisplay display -> {
					json.addProperty("kind", "text_display");
					json.addProperty("text", textOf(((TextDisplayInvoker) display).ghc$getText()));
				}
				default -> {
					json.addProperty("kind", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
					json.addProperty("width", round16(entity.getBbWidth()));
					json.addProperty("height", round16(entity.getBbHeight()));
				}
			}
			if (!name.isEmpty()) json.addProperty("name", name);
			entityArray.add(json);
		}

		// Vanilla blocks from the soil up to the first air block
		JsonArray blocks = new JsonArray();
		for (int dy = 1; dy <= MAX_BLOCKS; dy++) {
			BlockState state = level.getBlockState(new BlockPos(worldX, GardenGrid.FLOOR_Y + dy, worldZ));
			if (state.isAir()) break;
			blocks.add(blockState(state));
		}

		JsonObject json = new JsonObject();
		json.add("entities", entityArray);
		json.add("blocks", blocks);
		return json;
	}

	public static String blockState(BlockState state) {
		StringBuilder builder = new StringBuilder(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
		List<String> values = state.getValues().map(Object::toString).toList();
		if (!values.isEmpty()) builder.append('[').append(String.join(",", values)).append(']');
		return builder.toString();
	}

	private static String itemId(ItemStack stack) {
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}

	private static JsonObject item(ItemStack stack) {
		JsonObject json = new JsonObject();
		json.addProperty("id", itemId(stack));
		json.addProperty("name", stack.getHoverName().getString());
		if (stack.getCount() != 1) json.addProperty("count", stack.getCount());
		String tex = textureHash(stack);
		if (tex != null) json.addProperty("tex", tex);
		DyedItemColor dye = stack.get(DataComponents.DYED_COLOR);
		if (dye != null) json.addProperty("dye", String.format("#%06x", dye.rgb() & 0xFFFFFF));
		return json;
	}

	/** The last path segment of a player head's skin URL; a hash of the property when it can't be decoded. */
	public static @Nullable String textureHash(ItemStack stack) {
		if (stack.isEmpty() || !stack.is(Items.PLAYER_HEAD)) return null;
		ResolvableProfile profile = stack.get(DataComponents.PROFILE);
		if (profile == null) return null;
		String value = profile.partialProfile().properties().get("textures").stream().map(Property::value).findFirst().orElse(null);
		if (value == null || value.isBlank()) return null;
		return TEXTURE_CACHE.computeIfAbsent(value, Fingerprints::decodeTextureUrl);
	}

	private static String decodeTextureUrl(String base64) {
		try {
			String decoded = new String(Base64.getMimeDecoder().decode(base64), StandardCharsets.UTF_8);
			JsonElement url = JsonParser.parseString(decoded).getAsJsonObject().getAsJsonObject("textures").getAsJsonObject("SKIN").get("url");
			String text = url.getAsString();
			return text.substring(text.lastIndexOf('/') + 1);
		} catch (RuntimeException e) {
			return "raw:" + String.format("%08x", base64.hashCode());
		}
	}

	private static JsonArray rotations(Rotations rotations) {
		JsonArray array = new JsonArray();
		array.add(round1(rotations.x()));
		array.add(round1(rotations.y()));
		array.add(round1(rotations.z()));
		return array;
	}

	public static String textOf(@Nullable Component component) {
		if (component == null) return "";
		return component.getString().replaceAll("§.", "").strip();
	}

	public static double round16(double value) {
		return Math.round(value * 16.0) / 16.0;
	}

	private static double round1(float value) {
		return Math.round(value * 10.0) / 10.0;
	}
}
