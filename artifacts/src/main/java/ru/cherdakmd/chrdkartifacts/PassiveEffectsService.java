package ru.cherdakmd.chrdkartifacts;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.Random;
import java.util.Set;

/**
 * Пульс пассивных артефактов: каждые {@code general.passive-refresh-ticks} тиков
 * перевешивает зельевые эффекты носителям (длительность с запасом — эффект не моргает)
 * и крутит случайные триггеры проклятий (поджог/яд).
 */
public final class PassiveEffectsService {

    private static final int POTION_BUFFER_TICKS = 60;
    private static final int IGNITE_TICKS = 80;
    private static final int POISON_TICKS = 160;

    private final ArtifactsPlugin plugin;
    private final Random random = new Random();
    private BukkitTask task;

    public PassiveEffectsService(ArtifactsPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::pulse,
                plugin.config().refreshTicks(), plugin.config().refreshTicks());
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void pulse() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isDead() || !player.isOnline()) {
                continue;
            }
            if (plugin.config().requiresAuth()
                    && (plugin.coreApi() == null || !plugin.coreApi().isAuthenticated(player))) {
                continue;
            }
            Set<String> ids = plugin.items().carriedIds(player);
            for (String id : ids) {
                ArtifactDef def = ArtifactRegistry.byId(id);
                if (def == null || def.mode() != ArtifactDef.Mode.PASSIVE
                        || !plugin.config().isEnabled(id)) {
                    continue;
                }
                applyPotions(player, id);
                rollTrigger(player, id);
                runSpecial(player, id);
            }
        }
    }

    private void applyPotions(Player player, String id) {
        for (ArtifactEffects.PotionSpec spec : ArtifactEffects.passivePotions(id)) {
            PotionEffectType type = Potions.resolve(spec.type());
            if (type == null) {
                continue;
            }
            // мгновенные эффекты (сытость) тикают раз за пульс, а не каждый тик запаса
            int duration = type.isInstant() ? 1 : POTION_BUFFER_TICKS;
            player.addPotionEffect(new PotionEffect(type, duration,
                    spec.amplifier(), true, false, true));
        }
    }

    private void rollTrigger(Player player, String id) {
        ArtifactEffects.TriggerSpec trigger = ArtifactEffects.passiveTrigger(id);
        if (trigger == null || random.nextDouble() >= trigger.chancePerCycle()) {
            return;
        }
        switch (trigger.kind()) {
            case IGNITE -> {
                player.setFireTicks(IGNITE_TICKS);
                plugin.messages().send(player, "curse-ignite");
            }
            case POISON -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.POISON,
                        POISON_TICKS, 0, true, false, true));
                plugin.messages().send(player, "curse-poison");
            }
            case BLEED -> {
                if (player.getHealth() > 1.0) {
                    player.damage(1.0);
                    plugin.messages().send(player, "curse-bleed");
                }
            }
            case WATER_HURT -> {
                if (player.isInWater()) {
                    player.damage(1.0);
                    plugin.messages().send(player, "curse-water");
                }
            }
            case BLINDNESS_PROC -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS,
                        60, 0, true, false, true));
                plugin.messages().send(player, "curse-blind");
            }
            case XP_DRAIN -> {
                if (player.getTotalExperience() > 10) {
                    player.giveExp(-10);
                    plugin.messages().send(player, "curse-xp");
                }
            }
            case WITHER_PROC -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.WITHER,
                        80, 0, true, false, true));
                plugin.messages().send(player, "curse-wither");
            }
        }
    }

    /** Пульсовая половина суперспособностей: Магнит Опыта и Ледяная Аура. */
    private void runSpecial(Player player, String id) {
        ArtifactEffects.SpecialKind special = ArtifactEffects.passiveSpecial(id);
        if (special == null) {
            return;
        }
        switch (special) {
            case XP_MAGNET -> {
                int pulled = 0;
                for (org.bukkit.entity.Entity entity : player.getNearbyEntities(8, 5, 8)) {
                    if (entity instanceof org.bukkit.entity.ExperienceOrb orb && !orb.isDead()) {
                        orb.teleport(player.getLocation());
                        if (++pulled >= 12) {
                            break;
                        }
                    }
                }
            }
            case FROST_AURA -> {
                for (org.bukkit.entity.Entity entity : player.getNearbyEntities(8, 5, 8)) {
                    if (entity instanceof org.bukkit.entity.Monster mob && !mob.isDead()) {
                        mob.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,
                                100, 1, true, false, true));
                        mob.setFreezeTicks(Math.max(mob.getFreezeTicks(), 40));
                    }
                }
            }
            default -> {
                // PHOENIX_REVIVE/SOUL_THIEF/THORNS_RETALIATE обслуживает ArtifactEventListener
            }
        }
    }
}
