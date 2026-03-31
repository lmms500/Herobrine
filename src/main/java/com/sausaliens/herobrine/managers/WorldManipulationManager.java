package com.sausaliens.herobrine.managers;

import com.sausaliens.herobrine.HerobrinePlugin;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.type.Candle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Painting;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Handles environmental/world manipulation to create an unsettling atmosphere.
 * These are subtle changes that make the player question reality:
 *
 * - Ghost Doors: Doors open/close on their own when player isn't looking
 * - Candle Snuffer: Candles go out in player's vicinity
 * - Painting Swap: Paintings change to different variants
 * - Redstone Flicker: Redstone torches/lamps flicker briefly
 */
public class WorldManipulationManager implements Listener {
    private final HerobrinePlugin plugin;
    private final Random random;
    private BukkitTask manipulationTask;

    // Track recent manipulations to avoid repeating same blocks
    private final Map<Location, Long> recentManipulations = new HashMap<>();
    private static final long MANIPULATION_COOLDOWN = 60000L; // 1 minute per block

    // Track door states to revert them
    private final Map<Location, BukkitTask> pendingDoorReverts = new HashMap<>();

    public WorldManipulationManager(HerobrinePlugin plugin) {
        this.plugin = plugin;
        this.random = new Random();
        startManipulationTask();
    }

    private void startManipulationTask() {
        if (manipulationTask != null) {
            manipulationTask.cancel();
        }

        // Run every 15-30 seconds (randomized per tick to feel less predictable)
        int baseInterval = plugin.getConfigManager().getWorldManipulationInterval() * 20;
        manipulationTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!plugin.getConfigManager().isEnabled()) return;
            if (!plugin.getConfigManager().isWorldManipulationEnabled()) return;

            for (Player player : Bukkit.getOnlinePlayers()) {
                // Check pacing system
                if (plugin.getPacingManager() != null && !plugin.getPacingManager().allowWorldManipulation(player)) {
                    continue;
                }

                // Random chance per player per tick
                double chance = plugin.getConfigManager().getWorldManipulationChance();

                // Modify chance based on pacing intensity
                if (plugin.getPacingManager() != null) {
                    chance *= plugin.getPacingManager().getIntensityMultiplier(player);
                }

                if (random.nextDouble() < chance) {
                    performRandomManipulation(player);
                }
            }

            // Cleanup old manipulation cooldowns
            long now = System.currentTimeMillis();
            recentManipulations.entrySet().removeIf(e -> now - e.getValue() > MANIPULATION_COOLDOWN);
        }, baseInterval, baseInterval);
    }

    private void performRandomManipulation(Player player) {
        // Choose a random manipulation type
        double roll = random.nextDouble();

        if (roll < 0.35 && plugin.getConfigManager().isGhostDoorsEnabled()) {
            manipulateNearbyDoor(player);
        } else if (roll < 0.60 && plugin.getConfigManager().isCandleSnufferEnabled()) {
            snuffNearbyCandles(player);
        } else if (roll < 0.80 && plugin.getConfigManager().isPaintingSwapEnabled()) {
            swapNearbyPainting(player);
        } else {
            flickerRedstoneLights(player);
        }
    }

    // ===================== GHOST DOORS =====================

    private void manipulateNearbyDoor(Player player) {
        Location playerLoc = player.getLocation();
        int radius = plugin.getConfigManager().getGhostDoorsRadius();

        // Find doors nearby but NOT in player's direct line of sight
        List<Block> doors = new ArrayList<>();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -radius; z <= radius; z++) {
                    Location loc = playerLoc.clone().add(x, y, z);
                    Block block = loc.getBlock();

                    if (isDoor(block) && !isRecentlyManipulated(loc)) {
                        // Only manipulate doors the player isn't looking directly at
                        if (!isPlayerLookingAt(player, loc, 30)) {
                            doors.add(block);
                        }
                    }
                }
            }
        }

        if (doors.isEmpty()) return;

        Block door = doors.get(random.nextInt(doors.size()));
        Location doorLoc = door.getLocation();

        // Toggle the door
        if (door.getBlockData() instanceof Openable) {
            Openable openable = (Openable) door.getBlockData();
            boolean wasOpen = openable.isOpen();
            openable.setOpen(!wasOpen);
            door.setBlockData(openable);

            // Play door sound
            Sound sound = wasOpen ? Sound.BLOCK_WOODEN_DOOR_CLOSE : Sound.BLOCK_WOODEN_DOOR_OPEN;
            if (door.getType().name().contains("IRON")) {
                sound = wasOpen ? Sound.BLOCK_IRON_DOOR_CLOSE : Sound.BLOCK_IRON_DOOR_OPEN;
            }
            player.playSound(doorLoc, sound, 0.6f, 0.8f + random.nextFloat() * 0.2f);

            markManipulated(doorLoc);

            // Revert the door after 3-8 seconds
            int revertDelay = (60 + random.nextInt(100));
            BukkitTask revertTask = new BukkitRunnable() {
                @Override
                public void run() {
                    Block b = doorLoc.getBlock();
                    if (b.getBlockData() instanceof Openable) {
                        Openable o = (Openable) b.getBlockData();
                        o.setOpen(wasOpen);
                        b.setBlockData(o);

                        // Play closing sound quietly
                        Sound revertSound = wasOpen ? Sound.BLOCK_WOODEN_DOOR_OPEN : Sound.BLOCK_WOODEN_DOOR_CLOSE;
                        doorLoc.getWorld().playSound(doorLoc, revertSound, 0.3f, 0.9f);
                    }
                    pendingDoorReverts.remove(doorLoc);
                }
            }.runTaskLater(plugin, revertDelay);

            pendingDoorReverts.put(doorLoc, revertTask);

            // Record encounter for paranoia
            if (plugin.getParanoiaManager() != null) {
                plugin.getParanoiaManager().recordEncounter(player, ParanoiaManager.EncounterType.AMBIENT);
            }

            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("[WORLD] Ghost door " + (wasOpen ? "closed" : "opened")
                    + " at " + doorLoc.getBlockX() + "," + doorLoc.getBlockY() + "," + doorLoc.getBlockZ()
                    + " for " + player.getName());
            }
        }
    }

    private boolean isDoor(Block block) {
        String name = block.getType().name();
        return name.endsWith("_DOOR") && !name.equals("IRON_DOOR")
            || name.endsWith("_TRAPDOOR") || name.endsWith("_FENCE_GATE");
    }

    // ===================== CANDLE SNUFFER =====================

    private void snuffNearbyCandles(Player player) {
        Location playerLoc = player.getLocation();
        int radius = plugin.getConfigManager().getCandleSnufferRadius();

        List<Block> litCandles = new ArrayList<>();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -3; y <= 3; y++) {
                for (int z = -radius; z <= radius; z++) {
                    Location loc = playerLoc.clone().add(x, y, z);
                    Block block = loc.getBlock();

                    if (block.getType().name().contains("CANDLE") && !isRecentlyManipulated(loc)) {
                        if (block.getBlockData() instanceof Candle) {
                            Candle candle = (Candle) block.getBlockData();
                            if (candle.isLit()) {
                                litCandles.add(block);
                            }
                        }
                    }
                }
            }
        }

        if (litCandles.isEmpty()) return;

        // Extinguish 1-3 candles with staggered timing
        int count = Math.min(litCandles.size(), 1 + random.nextInt(3));
        Collections.shuffle(litCandles, random);

        for (int i = 0; i < count; i++) {
            Block candle = litCandles.get(i);
            int delay = i * (10 + random.nextInt(20)); // Staggered: 0.5-1.5s apart

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (candle.getBlockData() instanceof Candle) {
                    Candle candleData = (Candle) candle.getBlockData();
                    if (candleData.isLit()) {
                        candleData.setLit(false);
                        candle.setBlockData(candleData);

                        Location loc = candle.getLocation().add(0.5, 0.5, 0.5);
                        candle.getWorld().spawnParticle(Particle.SMOKE, loc, 5, 0.1, 0.1, 0.1, 0.01);
                        player.playSound(loc, Sound.BLOCK_CANDLE_EXTINGUISH, 0.4f, 0.9f + random.nextFloat() * 0.2f);

                        markManipulated(candle.getLocation());
                    }
                }
            }, delay);
        }

        if (plugin.getParanoiaManager() != null) {
            plugin.getParanoiaManager().recordEncounter(player, ParanoiaManager.EncounterType.AMBIENT);
        }

        if (plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info("[WORLD] Snuffed " + count + " candles near " + player.getName());
        }
    }

    // ===================== PAINTING SWAP =====================

    private void swapNearbyPainting(Player player) {
        Location playerLoc = player.getLocation();
        int radius = plugin.getConfigManager().getPaintingSwapRadius();

        List<Painting> paintings = new ArrayList<>();
        for (Entity entity : player.getWorld().getNearbyEntities(playerLoc, radius, radius, radius)) {
            if (entity instanceof Painting) {
                Painting painting = (Painting) entity;
                if (!isRecentlyManipulated(painting.getLocation()) && !isPlayerLookingAt(player, painting.getLocation(), 40)) {
                    paintings.add(painting);
                }
            }
        }

        if (paintings.isEmpty()) return;

        Painting target = paintings.get(random.nextInt(paintings.size()));
        Art originalArt = target.getArt();

        // Get all possible art types and pick a different one
        Art[] allArt = Art.values();
        Art newArt;
        int attempts = 0;
        do {
            newArt = allArt[random.nextInt(allArt.length)];
            attempts++;
        } while (newArt == originalArt && attempts < 20);

        if (newArt == originalArt) return;

        // Try to set the new art (may fail if painting doesn't fit)
        if (target.setArt(newArt, true)) {
            markManipulated(target.getLocation());

            // Subtle sound effect
            player.playSound(target.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.2f, 0.5f);

            if (plugin.getParanoiaManager() != null) {
                plugin.getParanoiaManager().recordEncounter(player, ParanoiaManager.EncounterType.AMBIENT);
            }

            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("[WORLD] Swapped painting from " + originalArt + " to " + newArt
                    + " near " + player.getName());
            }
        }
    }

    // ===================== REDSTONE FLICKER =====================

    private void flickerRedstoneLights(Player player) {
        Location playerLoc = player.getLocation();
        int radius = 8;

        List<Block> redstoneLights = new ArrayList<>();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -3; y <= 3; y++) {
                for (int z = -radius; z <= radius; z++) {
                    Location loc = playerLoc.clone().add(x, y, z);
                    Block block = loc.getBlock();

                    if ((block.getType() == Material.REDSTONE_LAMP ||
                         block.getType() == Material.LANTERN ||
                         block.getType() == Material.SOUL_LANTERN)
                        && !isRecentlyManipulated(loc)) {
                        redstoneLights.add(block);
                    }
                }
            }
        }

        if (redstoneLights.isEmpty()) return;

        Block light = redstoneLights.get(random.nextInt(redstoneLights.size()));
        Location lightLoc = light.getLocation();

        // Play redstone torch burnout sound
        player.playSound(lightLoc, Sound.BLOCK_REDSTONE_TORCH_BURNOUT, 0.3f, 0.5f + random.nextFloat() * 0.3f);
        player.spawnParticle(Particle.SMOKE, lightLoc.clone().add(0.5, 0.5, 0.5), 3, 0.1, 0.1, 0.1, 0.01);

        markManipulated(lightLoc);

        if (plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info("[WORLD] Flickered light at " + lightLoc.getBlockX() + ","
                + lightLoc.getBlockY() + "," + lightLoc.getBlockZ() + " for " + player.getName());
        }
    }

    // ===================== UTILITIES =====================

    private boolean isPlayerLookingAt(Player player, Location target, double maxAngle) {
        Location eyeLoc = player.getEyeLocation();
        org.bukkit.util.Vector toTarget = target.toVector().add(new org.bukkit.util.Vector(0.5, 0.5, 0.5))
            .subtract(eyeLoc.toVector()).normalize();
        org.bukkit.util.Vector lookDir = eyeLoc.getDirection().normalize();
        double dot = toTarget.dot(lookDir);
        double angle = Math.toDegrees(Math.acos(Math.min(1.0, Math.max(-1.0, dot))));
        return angle < maxAngle;
    }

    private boolean isRecentlyManipulated(Location loc) {
        Long lastTime = recentManipulations.get(loc);
        if (lastTime == null) return false;
        return (System.currentTimeMillis() - lastTime) < MANIPULATION_COOLDOWN;
    }

    private void markManipulated(Location loc) {
        recentManipulations.put(loc, System.currentTimeMillis());
    }

    public void cleanup() {
        if (manipulationTask != null) {
            manipulationTask.cancel();
            manipulationTask = null;
        }

        // Cancel pending door reverts
        for (BukkitTask task : pendingDoorReverts.values()) {
            task.cancel();
        }
        pendingDoorReverts.clear();
        recentManipulations.clear();
    }
}
