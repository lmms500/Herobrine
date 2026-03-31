package com.sausaliens.herobrine.managers;

import com.sausaliens.herobrine.HerobrinePlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Controls the pacing and rhythm of Herobrine's haunting.
 * Instead of random appearances, this creates a deliberate escalation
 * that feels like a real horror experience:
 *
 * DORMANT  → Nothing happens. Player feels safe. (warmup period)
 * SUBTLE   → Ambient sounds, subtitles, pet reactions. No visual sightings.
 * ACTIVE   → Distant sightings, environmental manipulation, footsteps.
 * INTENSE  → Close encounters, stalking, structures, torch manipulation.
 * CLIMAX   → Direct confrontation, flashing text, screen shake, maze teleport.
 * COOLDOWN → Herobrine backs off. Player recovers. Then cycle resets.
 */
public class PacingManager implements Listener {
    private final HerobrinePlugin plugin;
    private final Map<UUID, PlayerHauntState> playerStates;
    private BukkitTask pacingTask;
    private long serverStartTime;

    public enum HauntPhase {
        DORMANT(0),
        SUBTLE(1),
        ACTIVE(2),
        INTENSE(3),
        CLIMAX(4),
        COOLDOWN(5);

        private final int level;
        HauntPhase(int level) { this.level = level; }
        public int getLevel() { return level; }
    }

    public PacingManager(HerobrinePlugin plugin) {
        this.plugin = plugin;
        this.playerStates = new HashMap<>();
        this.serverStartTime = System.currentTimeMillis();
        startPacingTask();
    }

    private void startPacingTask() {
        if (pacingTask != null) {
            pacingTask.cancel();
        }

        // Tick every 10 seconds (200 ticks)
        pacingTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!plugin.getConfigManager().isEnabled()) return;
            if (!plugin.getConfigManager().isPacingEnabled()) return;

            for (Player player : Bukkit.getOnlinePlayers()) {
                tickPlayer(player);
            }
        }, 200L, 200L);
    }

    private void tickPlayer(Player player) {
        PlayerHauntState state = getPlayerState(player);
        long now = System.currentTimeMillis();
        long timeInPhase = now - state.phaseStartTime;

        // Check if we should advance to the next phase
        long phaseDuration = getPhaseDuration(state.phase);

        if (timeInPhase >= phaseDuration) {
            advancePhase(player, state);
        }

        // Debug logging
        if (plugin.getConfigManager().isDebugMode()) {
            long remaining = Math.max(0, phaseDuration - timeInPhase);
            plugin.getLogger().info("[PACING] " + player.getName() + ": " + state.phase
                + " (" + (timeInPhase / 1000) + "s / " + (phaseDuration / 1000) + "s)"
                + " next in " + (remaining / 1000) + "s");
        }
    }

    private long getPhaseDuration(HauntPhase phase) {
        // Durations in milliseconds
        switch (phase) {
            case DORMANT:
                return plugin.getConfigManager().getPacingDormantDuration() * 1000L;
            case SUBTLE:
                return plugin.getConfigManager().getPacingSubtleDuration() * 1000L;
            case ACTIVE:
                return plugin.getConfigManager().getPacingActiveDuration() * 1000L;
            case INTENSE:
                return plugin.getConfigManager().getPacingIntenseDuration() * 1000L;
            case CLIMAX:
                return plugin.getConfigManager().getPacingClimaxDuration() * 1000L;
            case COOLDOWN:
                return plugin.getConfigManager().getPacingCooldownDuration() * 1000L;
            default:
                return 300000L; // 5 minutes fallback
        }
    }

    private void advancePhase(Player player, PlayerHauntState state) {
        HauntPhase oldPhase = state.phase;

        switch (state.phase) {
            case DORMANT:
                state.phase = HauntPhase.SUBTLE;
                break;
            case SUBTLE:
                state.phase = HauntPhase.ACTIVE;
                break;
            case ACTIVE:
                state.phase = HauntPhase.INTENSE;
                break;
            case INTENSE:
                state.phase = HauntPhase.CLIMAX;
                break;
            case CLIMAX:
                state.phase = HauntPhase.COOLDOWN;
                state.cycleCount++;
                break;
            case COOLDOWN:
                // Reset to SUBTLE (skip DORMANT on subsequent cycles)
                state.phase = HauntPhase.SUBTLE;
                break;
        }

        state.phaseStartTime = System.currentTimeMillis();

        if (plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info("[PACING] " + player.getName() + ": " + oldPhase + " → " + state.phase
                + " (cycle #" + state.cycleCount + ")");
        }
    }

    /**
     * Check if a player is in the server warmup period (global dormancy after server start)
     */
    public boolean isInWarmup() {
        if (!plugin.getConfigManager().isPacingEnabled()) return false;
        long warmupMs = plugin.getConfigManager().getPacingWarmupMinutes() * 60 * 1000L;
        return (System.currentTimeMillis() - serverStartTime) < warmupMs;
    }

    /**
     * Get the current haunt phase for a player
     */
    public HauntPhase getPhase(Player player) {
        if (!plugin.getConfigManager().isPacingEnabled()) return HauntPhase.ACTIVE;
        if (isInWarmup()) return HauntPhase.DORMANT;
        return getPlayerState(player).phase;
    }

    /**
     * Check if NPC appearances are allowed for this player in their current phase
     */
    public boolean allowAppearance(Player player) {
        HauntPhase phase = getPhase(player);
        return phase == HauntPhase.ACTIVE || phase == HauntPhase.INTENSE || phase == HauntPhase.CLIMAX;
    }

    /**
     * Check if only distant/subtle appearances should happen (no close encounters)
     */
    public boolean onlyDistantAppearances(Player player) {
        return getPhase(player) == HauntPhase.ACTIVE;
    }

    /**
     * Check if ambient/environmental effects are allowed (subtitles, sounds, pet reactions)
     */
    public boolean allowAmbientEffects(Player player) {
        HauntPhase phase = getPhase(player);
        return phase != HauntPhase.DORMANT && phase != HauntPhase.COOLDOWN;
    }

    /**
     * Check if world manipulation (doors, candles, paintings) is allowed
     */
    public boolean allowWorldManipulation(Player player) {
        HauntPhase phase = getPhase(player);
        return phase == HauntPhase.ACTIVE || phase == HauntPhase.INTENSE || phase == HauntPhase.CLIMAX;
    }

    /**
     * Check if intense effects (screen shake, flashing text, maze) are allowed
     */
    public boolean allowIntenseEffects(Player player) {
        HauntPhase phase = getPhase(player);
        return phase == HauntPhase.INTENSE || phase == HauntPhase.CLIMAX;
    }

    /**
     * Get an intensity multiplier based on current phase (0.0 to 1.0)
     */
    public double getIntensityMultiplier(Player player) {
        switch (getPhase(player)) {
            case DORMANT:   return 0.0;
            case SUBTLE:    return 0.2;
            case ACTIVE:    return 0.4;
            case INTENSE:   return 0.7;
            case CLIMAX:    return 1.0;
            case COOLDOWN:  return 0.1;
            default:        return 0.5;
        }
    }

    /**
     * Force a player into a specific phase (for admin commands)
     */
    public void setPhase(Player player, HauntPhase phase) {
        PlayerHauntState state = getPlayerState(player);
        state.phase = phase;
        state.phaseStartTime = System.currentTimeMillis();
    }

    /**
     * Record that an encounter happened for a player.
     * This can be used to influence phase transitions (e.g., faster escalation).
     */
    public void recordEncounter(Player player) {
        PlayerHauntState state = getPlayerState(player);
        state.encounterCount++;
    }

    private PlayerHauntState getPlayerState(Player player) {
        return playerStates.computeIfAbsent(player.getUniqueId(), k -> new PlayerHauntState());
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // New players always start in DORMANT for a brief personal warmup
        PlayerHauntState state = new PlayerHauntState();
        playerStates.put(event.getPlayer().getUniqueId(), state);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Keep the state for when they rejoin (they'll reset to DORMANT via onPlayerJoin)
        playerStates.remove(event.getPlayer().getUniqueId());
    }

    public void cleanup() {
        if (pacingTask != null) {
            pacingTask.cancel();
            pacingTask = null;
        }
        playerStates.clear();
    }

    private static class PlayerHauntState {
        HauntPhase phase = HauntPhase.DORMANT;
        long phaseStartTime = System.currentTimeMillis();
        int cycleCount = 0;
        int encounterCount = 0;
    }
}
