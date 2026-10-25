package ru.cherdakmd.chrdkclasses;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * Боевые эффекты класса (1.4.164).
 *
 * <p>Урон и защита считаются в событиях, а не атрибутами: бонус ближнего боя
 * нужен только по игроку-атакующему, бонус стрел — только когда стрелял игрок,
 * а снижение урона зависит от причины (падение — отдельно). Атрибуты такого
 * «кто и чем» не различают.</p>
 *
 * <p>Порядок не важен: множители атакующего и жертвы перемножаются, а не
 * складываются, поэтому класс не может «съесть» чужой урон полностью.</p>
 */
final class ClassListener implements Listener {

    private final ClassesPlugin plugin;

    ClassListener(ClassesPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.store().rename(player.getUniqueId(), player.getName());
        plugin.effects().forget(player.getUniqueId());
        // Уровень запоминается молча: накопленное до входа — не «повышение».
        plugin.progress().baseline(player.getUniqueId(),
                plugin.service().level(player.getUniqueId()));
        // Атрибуты применяются после входа: навыки могли ещё не отдать уровень.
        Bukkit.getScheduler().runTask(plugin, () -> plugin.effects().apply(player));
        // Навыки подтягиваются не мгновенно — через десять секунд запоминаем
        // уровень ещё раз, чтобы вход не выглядел повышением класса.
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> plugin.progress().baseline(player.getUniqueId(),
                        plugin.service().level(player.getUniqueId())), 200L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        plugin.effects().clear(player);
        plugin.progress().forget(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> plugin.effects().apply(player));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        plugin.effects().forget(event.getEntity().getUniqueId());
    }

    /** Бонус атакующего: ближний бой воина или урон стрел стрелка. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamage() <= 0.0) {
            return;
        }
        Player attacker = attacker(event);
        if (attacker == null) {
            return;
        }
        boolean arrow = event.getDamager() instanceof Projectile;
        double bonus = plugin.service().effect(attacker.getUniqueId(),
                arrow ? EffectKind.ARROW_DAMAGE : EffectKind.MELEE_DAMAGE);
        if (bonus > 0.0) {
            event.setDamage(event.getDamage() * (1.0 + bonus));
        }
    }

    /** Защита жертвы и отметка боя: пока игрок получает урон, регенерация молчит. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim) || event.getDamage() <= 0.0) {
            return;
        }
        plugin.effects().markCombat(victim.getUniqueId(), System.currentTimeMillis());
        boolean fall = event.getCause() == EntityDamageEvent.DamageCause.FALL;
        double reduction = plugin.service().effect(victim.getUniqueId(),
                fall ? EffectKind.FALL_REDUCTION : EffectKind.DAMAGE_REDUCTION);
        if (reduction > 0.0) {
            event.setDamage(event.getDamage() * (1.0 - Math.min(0.9, reduction)));
        }
    }

    /** Атакующий-игрок для ближнего удара или выстрела. */
    private static Player attacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            return player;
        }
        if (event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }
}
