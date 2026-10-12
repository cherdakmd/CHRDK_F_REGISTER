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
            }
        }
    }

    private void applyPotions(Player player, String id) {
        for (ArtifactEffects.PotionSpec spec : ArtifactEffects.passivePotions(id)) {
            PotionEffectType type = Potions.resolve(spec.type());
            if (type == null) {
                continue;
            }
            player.addPotionEffect(new PotionEffect(type, POTION_BUFFER_TICKS,
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
        }
    }
}
