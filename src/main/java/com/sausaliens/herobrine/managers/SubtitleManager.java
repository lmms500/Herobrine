package com.sausaliens.herobrine.managers;

import com.sausaliens.herobrine.HerobrinePlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Sends subtle subtitle/actionbar messages to players to build unease.
 * Inspired by "Caution Caption" from From The Fog mod.
 *
 * Messages are context-aware:
 * - Underground: mining-related dread
 * - Night: darkness and pursuit themes
 * - Near structures: discovery and warning themes
 * - General: ambient unease
 *
 * Messages are rare and spaced out to avoid desensitization.
 */
public class SubtitleManager implements Listener {
    private final HerobrinePlugin plugin;
    private final Random random;
    private BukkitTask subtitleTask;
    private final Map<UUID, Long> lastSubtitleTime = new HashMap<>();

    // Minimum time between subtitles per player (milliseconds)
    private static final long SUBTITLE_COOLDOWN = 120000L; // 2 minutes

    // Context-aware message pools
    private static final String[] UNDERGROUND_MESSAGES = {
        "You are not alone down here...",
        "Something shifts in the dark...",
        "The stone remembers...",
        "Deeper... he waits deeper...",
        "The tunnels breathe...",
        "A presence watches from the walls...",
        "These caves were not always empty..."
    };

    private static final String[] NIGHT_MESSAGES = {
        "The night has eyes...",
        "Something follows in the dark...",
        "Don't look behind you...",
        "He walks when you sleep...",
        "The shadows are restless...",
        "Darkness falls... he rises...",
        "The moon watches with white eyes..."
    };

    private static final String[] FOREST_MESSAGES = {
        "The trees remember his name...",
        "Leaves fall without wind...",
        "Something watches from the treeline...",
        "The forest grows quiet...",
        "Branches crack with no one there..."
    };

    private static final String[] GENERAL_MESSAGES = {
        "You feel watched...",
        "A chill runs down your spine...",
        "Something feels wrong...",
        "The air grows cold...",
        "You hear breathing...",
        "Was that a footstep?",
        "He knows you're here...",
        "You are being remembered...",
        "The world shifts slightly...",
        "A presence lingers nearby..."
    };

    public SubtitleManager(HerobrinePlugin plugin) {
        this.plugin = plugin;
        this.random = new Random();
        startSubtitleTask();
    }

    private void startSubtitleTask() {
        if (subtitleTask != null) {
            subtitleTask.cancel();
        }

        // Check every 30 seconds (600 ticks)
        subtitleTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!plugin.getConfigManager().isEnabled()) return;
            if (!plugin.getConfigManager().isSubtitlesEnabled()) return;

            for (Player player : Bukkit.getOnlinePlayers()) {
                // Check pacing
                if (plugin.getPacingManager() != null && !plugin.getPacingManager().allowAmbientEffects(player)) {
                    continue;
                }

                // Check cooldown
                Long lastTime = lastSubtitleTime.get(player.getUniqueId());
                if (lastTime != null && (System.currentTimeMillis() - lastTime) < SUBTITLE_COOLDOWN) {
                    continue;
                }

                // Random chance
                double chance = plugin.getConfigManager().getSubtitleChance();

                // Modify based on pacing intensity
                if (plugin.getPacingManager() != null) {
                    chance *= plugin.getPacingManager().getIntensityMultiplier(player);
                }

                if (random.nextDouble() < chance) {
                    sendContextualSubtitle(player);
                }
            }
        }, 600L, 600L);
    }

    private void sendContextualSubtitle(Player player) {
        String message = selectMessage(player);

        // Format as a subtle subtitle
        String formatted = ChatColor.GRAY + "" + ChatColor.ITALIC + message;

        // Send as subtitle (empty title, subtitle text, quick fade)
        player.sendTitle("", formatted, 10, 60, 20);

        // Also send as actionbar for additional visibility
        player.spigot().sendMessage(
            net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
            new net.md_5.bungee.api.chat.TextComponent(formatted)
        );

        lastSubtitleTime.put(player.getUniqueId(), System.currentTimeMillis());

        // Record ambient encounter for paranoia
        if (plugin.getParanoiaManager() != null) {
            plugin.getParanoiaManager().recordEncounter(player, ParanoiaManager.EncounterType.AMBIENT);
        }

        if (plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info("[SUBTITLE] Sent to " + player.getName() + ": " + message);
        }
    }

    private String selectMessage(Player player) {
        // Determine player context
        boolean isUnderground = player.getLocation().getBlockY() <
            player.getWorld().getHighestBlockYAt(player.getLocation()) - 5;
        boolean isNight = player.getWorld().getTime() > 13000 && player.getWorld().getTime() < 23000;
        boolean isInForest = isNearTrees(player);

        // Build weighted pool
        List<String[]> pools = new ArrayList<>();
        List<Double> weights = new ArrayList<>();

        if (isUnderground) {
            pools.add(UNDERGROUND_MESSAGES);
            weights.add(3.0);
        }

        if (isNight) {
            pools.add(NIGHT_MESSAGES);
            weights.add(2.5);
        }

        if (isInForest) {
            pools.add(FOREST_MESSAGES);
            weights.add(2.0);
        }

        // Always include general messages with lower weight
        pools.add(GENERAL_MESSAGES);
        weights.add(1.0);

        // Select pool using weights
        double totalWeight = weights.stream().mapToDouble(Double::doubleValue).sum();
        double roll = random.nextDouble() * totalWeight;
        double cumulative = 0;

        for (int i = 0; i < pools.size(); i++) {
            cumulative += weights.get(i);
            if (roll < cumulative) {
                String[] pool = pools.get(i);
                return pool[random.nextInt(pool.length)];
            }
        }

        return GENERAL_MESSAGES[random.nextInt(GENERAL_MESSAGES.length)];
    }

    private boolean isNearTrees(Player player) {
        org.bukkit.Location loc = player.getLocation();
        int radius = 10;
        int treeCount = 0;

        for (int x = -radius; x <= radius; x += 3) {
            for (int z = -radius; z <= radius; z += 3) {
                org.bukkit.block.Block block = loc.clone().add(x, 0, z).getBlock();
                // Check a few Y levels
                for (int y = -1; y <= 5; y++) {
                    if (block.getRelative(0, y, 0).getType().name().endsWith("LOG")) {
                        treeCount++;
                        break;
                    }
                }
            }
        }

        return treeCount >= 3;
    }

    /**
     * Try to send a contextual subtitle to a player (called by AppearanceManager during pacing).
     * Respects cooldown and chance settings.
     */
    public void trySendSubtitle(Player player) {
        if (!plugin.getConfigManager().isSubtitlesEnabled()) return;

        Long lastTime = lastSubtitleTime.get(player.getUniqueId());
        if (lastTime != null && (System.currentTimeMillis() - lastTime) < SUBTITLE_COOLDOWN) {
            return;
        }

        double chance = plugin.getConfigManager().getSubtitleChance();
        if (plugin.getPacingManager() != null) {
            chance *= plugin.getPacingManager().getIntensityMultiplier(player);
        }

        if (random.nextDouble() < chance) {
            sendContextualSubtitle(player);
        }
    }

    /**
     * Force send a specific subtitle to a player (for use by other managers)
     */
    public void sendSubtitle(Player player, String message) {
        String formatted = ChatColor.GRAY + "" + ChatColor.ITALIC + message;
        player.sendTitle("", formatted, 10, 60, 20);
        lastSubtitleTime.put(player.getUniqueId(), System.currentTimeMillis());
    }

    /**
     * Force send a warning-style subtitle (red, more urgent)
     */
    public void sendWarning(Player player, String message) {
        String formatted = ChatColor.DARK_RED + "" + ChatColor.ITALIC + message;
        player.sendTitle("", formatted, 5, 40, 10);
        lastSubtitleTime.put(player.getUniqueId(), System.currentTimeMillis());
    }

    public void cleanup() {
        if (subtitleTask != null) {
            subtitleTask.cancel();
            subtitleTask = null;
        }
        lastSubtitleTime.clear();
    }
}
