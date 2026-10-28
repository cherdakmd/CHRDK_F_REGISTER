package ru.cherdakmd.chrdkteleports;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * «Разбег» телепорта и кулдауны. Перед телепортом игрок ждёт delaySeconds, не двигаясь
 * и не получая урона (отключаемо); кулдауны — на команду, срабатываются при успехе.
 */
public final class TeleportService implements Listener {

    /** Состояние ожидающего телепорта. */
    private static final class Pending {
        BukkitTask task;
        String world;
        double fromX, fromY, fromZ;
    }

    private final TeleportsPlugin plugin;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<String, Map<UUID, Long>> cooldowns = new HashMap<>();

    public TeleportService(TeleportsPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isBusy(UUID uuid) {
        return pending.containsKey(uuid);
    }

    /** Остаток кулдауна, секунд (0 — доступно). */
    public long cooldownLeft(String key, UUID uuid, long cooldownSeconds, long nowMillis) {
        Long stamp = cooldowns.getOrDefault(key, Map.of()).get(uuid);
        if (stamp == null) {
            return 0;
        }
        long leftMillis = stamp + cooldownSeconds * 1000L - nowMillis;
        return leftMillis <= 0 ? 0 : (leftMillis + 999) / 1000;
    }

    public void markCooldown(String key, UUID uuid, long nowMillis) {
        cooldowns.computeIfAbsent(key.toLowerCase(Locale.ROOT), k -> new HashMap<>()).put(uuid, nowMillis);
    }

    /**
     * Запускает разбег: через delaySeconds выполнит {@code onGo} (главная нить). Возвращает
     * false, если у игрока уже «в взлёте» (двойного — не будет).
     */
    public boolean start(Player player, Runnable onGo) {
        if (isBusy(player.getUniqueId())) {
            return false;
        }
        int delay = plugin.config().delaySeconds();
        Pending current = new Pending();
        current.world = player.getWorld().getName();
        current.fromX = player.getLocation().getX();
        current.fromY = player.getLocation().getY();
        current.fromZ = player.getLocation().getZ();
        if (delay <= 0) {
            pending.put(player.getUniqueId(), current);
            try { onGo.run(); } finally { pending.remove(player.getUniqueId()); }
            return true;
        }
        plugin.messages().send(player, "tp.warmup-start", "seconds", delay);
        Pending target = current;
        target.task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pending.remove(player.getUniqueId(), target);
            if (player.isOnline() && player.getWorld().getName().equals(target.world)) {
                onGo.run();
            }
        }, delay * 20L);
        pending.put(player.getUniqueId(), target);
        return true;
    }

    /** Ручная отмена (сообщение укажет причину). */
    public void cancel(UUID uuid, String messageKey) {
        Pending removed = pending.remove(uuid);
        if (removed != null) {
            if (removed.task != null) {
                removed.task.cancel();
            }
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                plugin.messages().send(player, messageKey);
            }
        }
    }

    public void shutdown() { for (var p:pending.values()) if (p.task!=null) p.task.cancel(); pending.clear(); }

    /* ------------------------------- слушатели отмен ------------------------------- */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.config().cancelOnMove()) return;
        Pending current = pending.get(event.getPlayer().getUniqueId());
        if (current == null || event.getTo()==null) return;
        double dX = event.getTo().getX() - current.fromX;
        double dY = event.getTo().getY() - current.fromY;
        double dZ = event.getTo().getZ() - current.fromZ;
        if (!event.getTo().getWorld().getName().equals(current.world) || Math.abs(dX) > 0.15 || Math.abs(dZ) > 0.15 || Math.abs(dY) > 0.5) {
            cancel(event.getPlayer().getUniqueId(), "tp.warmup-cancelled-move");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!plugin.config().cancelOnDamage()) return;
        if (event.getEntity() instanceof Player player
                && pending.containsKey(player.getUniqueId())) {
            cancel(player.getUniqueId(), "tp.warmup-cancelled-damage");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Pending removed = pending.remove(event.getPlayer().getUniqueId());
        if (removed != null && removed.task != null) {
            removed.task.cancel();
        }
    }
}
