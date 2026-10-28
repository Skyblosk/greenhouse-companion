package dev.ilyapple.ghc.mod;

import java.util.Locale;

import org.jspecify.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;

/**
 * Greenhouse geometry, the same as the stage recorder and Skyblocker: the Garden is a 5x5 grid of 96x96 plots
 * centred on (0, 0), each plot's greenhouse is the 10x10 square 43 blocks in from its min corner, floor at y=73.
 * Cell {@code x * 10 + z} is the column at {@code corner + (x, 0, z)}, matching Skyblocker's {@code [x][z]}.
 */
public final class GardenGrid {
	public static final int SIZE = 10;
	public static final int FLOOR_Y = 73;
	public static final double SCAN_BELOW = 2.0;
	/** Magic Jellybean is 10 blocks tall. */
	public static final double SCAN_ABOVE = 15.0;

	private GardenGrid() {}

	public static BlockPos cornerFor(BlockPos pos) {
		int plotX = Math.floorDiv(pos.getX() + 240, 96) * 96 - 240;
		int plotZ = Math.floorDiv(pos.getZ() + 240, 96) * 96 - 240;
		return new BlockPos(plotX + 43, FLOOR_Y, plotZ + 43);
	}

	public static int cellAt(BlockPos corner, double worldX, double worldY, double worldZ) {
		int x = (int) Math.floor(worldX) - corner.getX();
		int z = (int) Math.floor(worldZ) - corner.getZ();
		if (x < 0 || x >= SIZE || z < 0 || z >= SIZE) return -1;
		if (worldY < FLOOR_Y - SCAN_BELOW || worldY >= FLOOR_Y + SCAN_ABOVE) return -1;
		return x * SIZE + z;
	}

	public static int cellAt(BlockPos corner, Vec3 pos) {
		return cellAt(corner, pos.x, pos.y, pos.z);
	}

	/**
	 * The cell a plant's entity belongs to: the one it stands in, except a young Magic Jellybean's held bean. At phases
	 * 0–2 of every 12 stages a Jellybean holds a full-size melon head out to the side, about (+0.44, −1.05) from its own
	 * cell's centre (MagicAddons' {@code HELD_BEAN_STAND_OFFSET}, turned with the plot), so it stands in the next cell
	 * over. Read there it made that cell look like a Jellybean, and a Plant Diagnostics click on it was put down to
	 * the wrong cell (the user's alpha Plot 4, cell 87, 27 Oct 2026). It belongs to the neighbour whose column is a
	 * Jellybean (sugar cane or a melon stem on the soil) and whose centre is about 1.14 blocks away.
	 */
	public static int plantCellOf(net.minecraft.world.level.Level level, BlockPos corner, Entity entity) {
		int cell = cellAt(corner, entity.position());
		if (cell < 0 || !(entity instanceof net.minecraft.world.entity.decoration.ArmorStand stand) || stand.isSmall()) return cell;
		var head = stand.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD);
		if (head.isEmpty()) return cell;
		String tex = Fingerprints.textureHash(head);
		boolean melon = tex != null ? tex.equals(dev.ilyapple.ghc.core.Jellybean.MELON_TEXTURE)
				: head.getHoverName().getString().equalsIgnoreCase("magicjellybean0");
		if (!melon) return cell;
		double sideX = entity.getX() - corner.getX();
		double sideZ = entity.getZ() - corner.getZ();
		int best = cell;
		double bestMiss = 0.3;
		int x = cellX(cell);
		int z = cellZ(cell);
		for (int[] step : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
			int nx = x + step[0];
			int nz = z + step[1];
			if (nx < 0 || nx >= SIZE || nz < 0 || nz >= SIZE) continue;
			var bottom = level.getBlockState(corner.offset(nx, 1, nz)).getBlock();
			if (bottom != net.minecraft.world.level.block.Blocks.SUGAR_CANE && bottom != net.minecraft.world.level.block.Blocks.MELON_STEM
					&& bottom != net.minecraft.world.level.block.Blocks.ATTACHED_MELON_STEM) continue;
			double miss = Math.abs(Math.hypot(sideX - (nx + 0.5), sideZ - (nz + 0.5)) - 1.14);
			if (miss < bestMiss) {
				bestMiss = miss;
				best = nx * SIZE + nz;
			}
		}
		return best;
	}

	public static int cellX(int cell) {
		return cell / SIZE;
	}

	public static int cellZ(int cell) {
		return cell % SIZE;
	}

	public static AABB scanBox(BlockPos corner) {
		return new AABB(corner.getX() - 1, FLOOR_Y - SCAN_BELOW - 2, corner.getZ() - 1,
				corner.getX() + SIZE + 1, FLOOR_Y + SCAN_ABOVE + 1, corner.getZ() + SIZE + 1);
	}

	/** Whether the player stands over the greenhouse (with a few blocks of margin), where every plant is loaded. */
	public static boolean near(Entity player, BlockPos corner) {
		double cx = corner.getX() + SIZE / 2.0;
		double cz = corner.getZ() + SIZE / 2.0;
		return Math.abs(player.getX() - cx) < 24 && Math.abs(player.getZ() - cz) < 24;
	}

	/** Hypixel's plot numbers on the 5x5 grid, north row first (from SkyHanni's GardenPlotApi); 0 is the barn. */
	private static final int[][] PLOT_NUMBERS = {
			{21, 13, 9, 14, 22},
			{15, 5, 1, 6, 16},
			{10, 2, 0, 3, 11},
			{17, 7, 4, 8, 18},
			{23, 19, 12, 20, 24},
	};

	/** The plot number Hypixel shows ("Plot - 7"), 1–24 around the barn, or 0 for the barn plot; -1 off the grid. */
	public static int plotNumber(BlockPos corner) {
		int column = Math.floorDiv(corner.getX() - 43 + 240, 96);
		int row = Math.floorDiv(corner.getZ() - 43 + 240, 96);
		if (column < 0 || column > 4 || row < 0 || row > 4) return -1;
		return PLOT_NUMBERS[row][column];
	}

	public static String plotName(BlockPos corner) {
		int number = plotNumber(corner);
		return number < 0 ? "(" + corner.getX() + ", " + corner.getZ() + ")" : Integer.toString(number);
	}

	/** Whether the plot with this greenhouse corner has its Carpenter NPC, which only greenhouse plots have (Skyblocker's test). */
	public static boolean hasCarpenter(net.minecraft.world.level.Level level, BlockPos corner) {
		AABB plot = new AABB(corner.getX() - 43, 60, corner.getZ() - 43, corner.getX() + 53, 90, corner.getZ() + 53);
		for (Entity entity : level.getEntities((Entity) null, plot, entity -> entity.getCustomName() != null)) {
			if (entity.getCustomName().getString().contains("Carpenter")) return true;
		}
		return false;
	}

	/** Whether a block is inside the greenhouse's 10x10 columns, from the soil up. */
	public static boolean inColumns(BlockPos corner, BlockPos pos) {
		int x = pos.getX() - corner.getX();
		int z = pos.getZ() - corner.getZ();
		return x >= 0 && x < SIZE && z >= 0 && z < SIZE && pos.getY() > FLOOR_Y - 1 && pos.getY() < FLOOR_Y + SCAN_ABOVE;
	}

	/** The cell a block is in, or -1. */
	public static int cellOf(BlockPos corner, BlockPos pos) {
		return inColumns(corner, pos) ? (pos.getX() - corner.getX()) * SIZE + pos.getZ() - corner.getZ() : -1;
	}

	/** "⏣ The Garden" or "⏣ Plot - N" on the SkyBlock sidebar. */
	public static boolean sidebarSaysGarden(Minecraft client) {
		if (client.level == null) return false;
		Scoreboard scoreboard = client.level.getScoreboard();
		Objective objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
		if (objective == null) return false;
		for (ScoreHolder holder : scoreboard.getTrackedPlayers()) {
			if (!scoreboard.listPlayerScores(holder).containsKey(objective)) continue;
			PlayerTeam team = scoreboard.getPlayersTeam(holder.getScoreboardName());
			if (team == null) continue;
			String line = ChatFormatting.stripFormatting(team.getPlayerPrefix().getString() + team.getPlayerSuffix().getString());
			if (line != null && (line.contains("The Garden") || line.contains("Plot -"))) return true;
		}
		return false;
	}

	/** Which server's greenhouses these are: the alpha and the main network keep separate ones. */
	public static String server(Minecraft client) {
		ServerData data = client.getCurrentServer();
		if (data == null) return "singleplayer";
		String ip = data.ip.toLowerCase(Locale.ROOT);
		return ip.contains("alpha") ? "alpha.hypixel.net" : ip.contains("hypixel") ? "hypixel.net" : ip;
	}

	/** The SkyBlock profile's name from the tab list's "Profile: Apple" line, or {@code null}. */
	public static @Nullable String tabProfileName(Minecraft client) {
		if (client.getConnection() == null) return null;
		for (net.minecraft.client.multiplayer.PlayerInfo info : client.getConnection().getListedOnlinePlayers()) {
			if (info.getTabListDisplayName() == null) continue;
			String line = ChatFormatting.stripFormatting(info.getTabListDisplayName().getString());
			if (line == null) continue;
			java.util.regex.Matcher matcher = TAB_PROFILE.matcher(line.strip());
			if (matcher.find()) return matcher.group(1);
		}
		return null;
	}

	private static final java.util.regex.Pattern TAB_PROFILE = java.util.regex.Pattern.compile("^Profile: ([A-Za-z]+)");

	/** The cell the crosshair is on: the hit entity's or block's cell, or -1. */
	public static int targetedCell(Minecraft client, BlockPos corner) {
		HitResult hit = client.hitResult;
		if (hit == null) return -1;
		return switch (hit) {
			case EntityHitResult entityHit -> client.level == null ? cellAt(corner, entityHit.getEntity().position())
					: plantCellOf(client.level, corner, entityHit.getEntity());
			case BlockHitResult blockHit when blockHit.getType() == HitResult.Type.BLOCK -> {
				BlockPos pos = blockHit.getBlockPos();
				yield cellAt(corner, pos.getX() + 0.5, Math.max(pos.getY(), FLOOR_Y) + 0.5, pos.getZ() + 0.5);
			}
			default -> -1;
		};
	}

	public static @Nullable String key(String server, BlockPos corner) {
		return server + "|" + corner.getX() + "," + corner.getZ();
	}
}
