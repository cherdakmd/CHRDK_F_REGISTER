package ru.cherdakmd.chrdkartifacts;

import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Set;

/**
 * Событийная половина суперспособностей доброй волны (1.4.58): Перо Феникса
 * (спасение от смертельного удара раз в 10 минут), Жнец Душ (лечение за души)
 * и Тернистый Плащ (ответный шок нападавшему). Пульсовые двойники — в
 * {@link PassiveEffectsService}. Метка нососримости — только факт предмета в
 * инвентаре (как у всех пассивных артефактов).
 */
public final class ArtifactEventListener implements Listener {

    /** Перо Феникса: спасение раз в 10 минут. */
    static final int PHOENIX_COOLDOWN_SECONDS = 600;
    /** Во сколько сердец воскрешает феникс (доля от макс. здоровья). */
    static final double PHOENIX_HEAL_FRACTION = 0.5;
    /** Жнец Душ: сколько здоровья возвращает убийство. */
    static final double SOUL_HEAL = 4.0;
    /** Тернистый Плащ: ответный урон нападавшему. */
    static final double RETALIATE_DAMAGE = 2.0;

    private final ArtifactsPlugin plugin;

    public ArtifactEventListener(ArtifactsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Перо Феникса: смертельный урон → крылья пламени и половина здоровья. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFatalDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)
                || event.getFinalDamage() < victim.getHealth()) {
            return;
        }
        if (!carries(victim, "pero-feniksa")) {
            return;
        }
        long now = System.currentTimeMillis();
        if (plugin.cooldowns().isCoolingDown(victim.getUniqueId(), "pero-feniksa", now)) {
            return;
        }
        double max = victim.getAttribute(Attribute.MAX_HEALTH) != null
                ? victim.getAttribute(Attribute.MAX_HEALTH).getValue() : 20.0;
        event.setDamage(0.0);
        victim.setHealth(Math.max(1.0, max * PHOENIX_HEAL_FRACTION));
        victim.setFireTicks(0);
        victim.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 80, 1,
                true, false, true));
        plugin.cooldowns().start(victim.getUniqueId(), "pero-feniksa", now,
                PHOENIX_COOLDOWN_SECONDS);
        plugin.messages().send(victim, "phoenix-saved");
    }

    /** Жнец Душ: душа павшего подкрепляет жнеца. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKillerHeal(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null || killer.isDead() || !carries(killer, "zhnej-dush")) {
            return;
        }
        double max = killer.getAttribute(Attribute.MAX_HEALTH) != null
                ? killer.getAttribute(Attribute.MAX_HEALTH).getValue() : 20.0;
        if (killer.getHealth() < max) {
            try {
                killer.setHealth(Math.min(max, killer.getHealth() + SOUL_HEAL));
            } catch (IllegalArgumentException ignored) {
                // здоровье шевельнулось под нами — лечение пропускаем
            }
        }
    }

    /** Тернистый Плащ: иголки отвечают сами. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRetaliate(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim) || !carries(victim, "ternistyj-plasch")) {
            return;
        }
        LivingEntity damager = event.getDamager() instanceof LivingEntity d ? d : null;
        if (damager == null || damager.equals(victim)) {
            return;
        }
        damager.damage(RETALIATE_DAMAGE, victim);
    }

    /** Есть ли у игрока артефакт с нужным идом (и включён ли он конфигом/входом). */
    private boolean carries(Player player, String id) {
        if (!plugin.config().isEnabled(id)) {
            return false;
        }
        if (plugin.config().requiresAuth()
                && (plugin.coreApi() == null || !plugin.coreApi().isAuthenticated(player))) {
            return false;
        }
        Set<String> ids = plugin.items().carriedIds(player);
        return ids.contains(id);
    }
}
