package ru.cherdakmd.chrdkartifacts;

import org.bukkit.GameMode;
import org.bukkit.Material;
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

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

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
    /**
     * Кто уже отвечает ударом в текущей цепочке событий. Два игрока в «Тернистом
     * Плаще» иначе обмениваются ответными ударами внутри одного и того же удара:
     * каждый ответ запускает новое событие, и оба умирают одновременно.
     */
    private final Set<UUID> retaliating = new HashSet<>();

    public ArtifactEventListener(ArtifactsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Перо Феникса: смертельный урон → крылья пламени и половина здоровья.
     *
     * <p>Проверка идёт на {@code HIGHEST} и уже после того, как сработал
     * ванильный тотем бессмертия: если тотем взял смерть на себя, перо молчит и
     * не сжигает десятиминутный кулдаун. Удар, который целиком погасили щиты
     * поглощения, смертельным не считается ({@link CombatMath}).</p>
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFatalDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim) || event.getDamage() <= 0) {
            return;
        }
        if (!CombatMath.isLethal(victim.getHealth(), event.getFinalDamage(),
                victim.getAbsorptionAmount())) {
            return;
        }
        // Тотем бессмертия Paper обработал раньше (LOWEST/HIGH): перо не дублирует спасение.
        if (victim.getInventory().getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING
                || victim.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING) {
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
        victim.setHealth(CombatMath.reviveHealth(max, PHOENIX_HEAL_FRACTION));
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
        if (damager == null || damager.equals(victim) || damager.isDead()) {
            return;
        }
        if (damager instanceof Player attacker
                && (attacker.getGameMode() == GameMode.CREATIVE
                || attacker.getGameMode() == GameMode.SPECTATOR)) {
            return;
        }
        // Если у нападавшего тоже плащ, его ответный удар вернётся сюда же — второй
        // раз в той же цепочке не отвечаем, иначе плащи убивают друг друга мгновенно.
        UUID damagerId = damager.getUniqueId();
        if (!retaliating.add(damagerId)) {
            return;
        }
        try {
            damager.damage(RETALIATE_DAMAGE, victim);
        } finally {
            retaliating.remove(damagerId);
        }
    }

    /** Есть ли у игрока артефакт с нужным идом (и включён ли он конфигом/входом). */
    private boolean carries(Player player, String id) {
        if (!player.hasPermission("chrdkartifacts.use") || !plugin.config().isEnabled(id)) {
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
