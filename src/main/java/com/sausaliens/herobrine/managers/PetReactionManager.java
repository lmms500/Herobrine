package com.sausaliens.herobrine.managers;

import com.sausaliens.herobrine.HerobrinePlugin;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.*;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Makes animals react to Herobrine's presence, giving players subtle warnings.
 * Inspired by "Sighting Sense" and "Haunted Herd" from From The Fog mod.
 *
 * Behaviors:
 * - Wolves growl/whimper when Herobrine NPC is nearby
 * - Cats hiss and arch their backs (flee from Herobrine)
 * - Passive animals (cows, sheep, pigs) stare at Herobrine's location
 * - Chickens scatter (run away from Herobrine's direction)
 * - Animals fall silent before Herobrine appears (ambient sound suppression)
 *
 * This creates an early warning system that observant players can learn to read.
 */
public class PetReactionManager implements Listener {
    private final HerobrinePlugin plugin;
    private final Random random;
    private BukkitTask reactionTask;

    // Cooldowns to avoid spamming reactions per animal
    private final Map<UUID, Long> animalCooldowns = new HashMap<>();
    private static final long ANIMAL_COOLDOWN = 30000L; // 30 seconds per animal

    public PetReactionManager(HerobrinePlugin plugin) {
        this.plugin = plugin;
        this.random = new Random();
        startReactionTask();
    }

    private void startReactionTask() {
        if (reactionTask != null) {
            reactionTask.cancel();
        }

        // Check every 5 seconds (100 ticks)
        reactionTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!plugin.getConfigManager().isEnabled()) return;
            if (!plugin.getConfigManager().isPetReactionsEnabled()) return;

            for (Player player : Bukkit.getOnlinePlayers()) {
                // Check pacing
                if (plugin.getPacingManager() != null && !plugin.getPacingManager().allowAmbientEffects(player)) {
                    continue;
                }

                checkAnimalReactions(player);
            }

            // Cleanup old cooldowns
            long now = System.currentTimeMillis();
            animalCooldowns.entrySet().removeIf(e -> now - e.getValue() > ANIMAL_COOLDOWN);
        }, 100L, 100L);
    }

    private void checkAnimalReactions(Player player) {
        // Find nearby Herobrine NPC
        Location herobrineLocation = findNearbyHerobrineNPC(player, 50);

        // Also check if there's a pending/imminent appearance based on pacing
        boolean heroBrinePresence = herobrineLocation != null;

        // If Herobrine is within 50 blocks, animals within pet_reaction_radius react
        if (!heroBrinePresence) {
            // Even without active Herobrine NPC, sometimes fake reactions to build suspense
            if (plugin.getPacingManager() != null) {
                PacingManager.HauntPhase phase = plugin.getPacingManager().getPhase(player);
                if (phase == PacingManager.HauntPhase.SUBTLE || phase == PacingManager.HauntPhase.ACTIVE) {
                    // Small chance for phantom reactions (no actual Herobrine, just unease)
                    if (random.nextDouble() < 0.05) {
                        // Pick a random direction for animals to "sense" something
                        Location phantomLoc = player.getLocation().add(
                            (random.nextDouble() - 0.5) * 40,
                            0,
                            (random.nextDouble() - 0.5) * 40
                        );
                        triggerReactionsNear(player, phantomLoc, true);
                    }
                }
            }
            return;
        }

        triggerReactionsNear(player, herobrineLocation, false);
    }

    private void triggerReactionsNear(Player player, Location threatLocation, boolean isPhantom) {
        int radius = plugin.getConfigManager().getPetReactionRadius();
        Location playerLoc = player.getLocation();

        for (Entity entity : player.getWorld().getNearbyEntities(playerLoc, radius, radius, radius)) {
            if (!(entity instanceof LivingEntity)) continue;
            if (entity instanceof Player) continue;

            // Check cooldown
            if (isOnCooldown(entity.getUniqueId())) continue;

            double distToThreat = entity.getLocation().distance(threatLocation);
            if (distToThreat > radius * 1.5) continue;

            // React based on animal type
            if (entity instanceof Wolf) {
                reactWolf((Wolf) entity, threatLocation, isPhantom, player);
            } else if (entity instanceof Cat || entity instanceof Ocelot) {
                reactCat((LivingEntity) entity, threatLocation, isPhantom);
            } else if (entity instanceof Chicken) {
                reactChicken((Chicken) entity, threatLocation);
            } else if (entity instanceof Cow || entity instanceof Sheep || entity instanceof Pig) {
                reactPassive((LivingEntity) entity, threatLocation, isPhantom);
            }
        }
    }

    private void reactWolf(Wolf wolf, Location threat, boolean isPhantom, Player nearPlayer) {
        setCooldown(wolf.getUniqueId());

        // Make wolf look at Herobrine location
        faceLocation(wolf, threat);

        if (wolf.isTamed()) {
            // Tamed wolves whimper (owner gets an extra warning)
            wolf.getWorld().playSound(wolf.getLocation(), Sound.ENTITY_WOLF_WHINE, 0.6f, 0.8f);

            // Send subtle subtitle to the wolf's owner
            if (wolf.getOwner() instanceof Player) {
                Player owner = (Player) wolf.getOwner();
                if (plugin.getSubtitleManager() != null && owner.isOnline()) {
                    plugin.getSubtitleManager().sendSubtitle(owner, "Your wolf senses something...");
                }
            }
        } else {
            // Wild wolves growl
            wolf.getWorld().playSound(wolf.getLocation(), Sound.ENTITY_WOLF_GROWL, 0.6f, 0.7f);
        }

        // If it's a real Herobrine presence (not phantom), wolves may become aggressive
        if (!isPhantom && wolf.isTamed() && random.nextDouble() < 0.3) {
            wolf.setAngry(true);

            // Calm down after a few seconds
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (wolf.isValid() && wolf.isAngry()) {
                    wolf.setAngry(false);
                }
            }, 60L + random.nextInt(40)); // 3-5 seconds
        }

        if (plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info("[PET] Wolf reacted at " + wolf.getLocation().getBlockX()
                + "," + wolf.getLocation().getBlockZ() + " (phantom=" + isPhantom + ")");
        }
    }

    private void reactCat(LivingEntity cat, Location threat, boolean isPhantom) {
        setCooldown(cat.getUniqueId());

        // Cats hiss
        cat.getWorld().playSound(cat.getLocation(), Sound.ENTITY_CAT_HISS, 0.5f, 1.0f);

        // Run away from threat
        org.bukkit.util.Vector away = cat.getLocation().toVector()
            .subtract(threat.toVector()).normalize().multiply(5);
        Location fleeTo = cat.getLocation().add(away);

        if (cat instanceof Creature) {
            // Make cat look at threat first, then flee
            faceLocation(cat, threat);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (cat.isValid()) {
                    org.bukkit.util.Vector velocity = fleeTo.toVector()
                        .subtract(cat.getLocation().toVector()).normalize().multiply(0.6);
                    cat.setVelocity(velocity);
                }
            }, 10L);
        }
    }

    private void reactChicken(Chicken chicken, Location threat) {
        setCooldown(chicken.getUniqueId());

        // Chickens panic and scatter
        chicken.getWorld().playSound(chicken.getLocation(), Sound.ENTITY_CHICKEN_HURT, 0.3f, 1.2f);

        org.bukkit.util.Vector away = chicken.getLocation().toVector()
            .subtract(threat.toVector()).normalize().multiply(3);
        // Add randomness to scatter direction
        away.add(new org.bukkit.util.Vector(
            (random.nextDouble() - 0.5) * 2,
            0.3,
            (random.nextDouble() - 0.5) * 2
        ));

        chicken.setVelocity(away.multiply(0.5));
    }

    private void reactPassive(LivingEntity animal, Location threat, boolean isPhantom) {
        setCooldown(animal.getUniqueId());

        // Passive animals stare at the threat location
        faceLocation(animal, threat);

        // Occasional distressed sounds
        if (random.nextDouble() < 0.4) {
            Sound sound;
            if (animal instanceof Cow) {
                sound = Sound.ENTITY_COW_AMBIENT;
            } else if (animal instanceof Sheep) {
                sound = Sound.ENTITY_SHEEP_AMBIENT;
            } else if (animal instanceof Pig) {
                sound = Sound.ENTITY_PIG_AMBIENT;
            } else {
                return;
            }
            animal.getWorld().playSound(animal.getLocation(), sound, 0.4f, 0.6f);
        }

        // If the presence is real and close, passive animals flee
        if (!isPhantom && animal.getLocation().distance(threat) < 15) {
            if (animal instanceof Creature) {
                org.bukkit.util.Vector away = animal.getLocation().toVector()
                    .subtract(threat.toVector()).normalize().multiply(0.5);
                away.setY(0.1);
                animal.setVelocity(away);
            }
        }
    }

    // ===================== HELPERS =====================

    /**
     * Make a living entity face a target location (Spigot-compatible alternative to Paper's lookAt)
     */
    private void faceLocation(LivingEntity entity, Location target) {
        Location entityLoc = entity.getLocation();
        double dx = target.getX() - entityLoc.getX();
        double dz = target.getZ() - entityLoc.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        entityLoc.setYaw(yaw);
        entity.teleport(entityLoc);
    }

    private Location findNearbyHerobrineNPC(Player player, double maxDistance) {
        if (plugin.getAppearanceManager() == null) return null;

        // Check for active Herobrine NPCs near this player
        for (Entity entity : player.getWorld().getNearbyEntities(player.getLocation(), maxDistance, maxDistance, maxDistance)) {
            if (entity.hasMetadata("NPC") && entity.getCustomName() != null
                && entity.getCustomName().equals("Herobrine")) {
                return entity.getLocation();
            }
        }

        return null;
    }

    private boolean isOnCooldown(UUID entityId) {
        Long lastTime = animalCooldowns.get(entityId);
        if (lastTime == null) return false;
        return (System.currentTimeMillis() - lastTime) < ANIMAL_COOLDOWN;
    }

    private void setCooldown(UUID entityId) {
        animalCooldowns.put(entityId, System.currentTimeMillis());
    }

    /**
     * Trigger a phantom reaction near a player (animals react to nothing visible).
     * Called by AppearanceManager during SUBTLE/ACTIVE pacing phases.
     */
    public void triggerPhantomReaction(Player player) {
        if (!plugin.getConfigManager().isPetReactionsEnabled()) return;
        if (random.nextDouble() >= 0.05) return; // 5% chance

        Location phantomLoc = player.getLocation().add(
            (random.nextDouble() - 0.5) * 40,
            0,
            (random.nextDouble() - 0.5) * 40
        );
        triggerReactionsNear(player, phantomLoc, true);
    }

    public void cleanup() {
        if (reactionTask != null) {
            reactionTask.cancel();
            reactionTask = null;
        }
        animalCooldowns.clear();
    }
}
