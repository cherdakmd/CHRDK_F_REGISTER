package ru.cherdakmd.chrdkdisasters;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/** Тик расписания и ручной запуск катастроф. Всё на главном потоке сервера. */
public final class DisasterEngine {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final DisastersPlugin plugin;
    private BukkitTask task;
    private boolean restoreScheduled;

    public DisasterEngine(DisastersPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        long period = 20L * Math.max(5, plugin.settings().tickSeconds());
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, period, period);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        DisastersConfig config = plugin.settings();
        if (!config.enabled() || !plugin.runtimeEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("chrdkdisasters.bypass")) {
                continue;
            }
            if (!config.worldAllowed(player.getWorld().getName())) {
                continue;
            }
            Optional<DisasterScheduler.Scheduled> due = plugin.scheduler().poll(player.getName(), now);
            if (due.isEmpty()) {
                continue;
            }
            DisasterScheduler.Scheduled event = due.get();
            Location at = player.getLocation();
            // Площадная проверка приватов: если в радиусе есть чужой чанк —
            // катастрофа не начинается вовсе, а игроку выдаётся новая пауза.
            if (plugin.protection().areaBlocked(at, config.radius())) {
                plugin.scheduler().reset(event.player(), now);
                continue;
            }
            int changed = plugin.effects().run(event.kind(), at, event.level());
            plugin.broadcaster().announce(event.kind(), event.level(), List.of(player));
            if (changed > 0) {
                scheduleRestore(config);
            }
            plugin.getLogger().info("[CHRDK_Disasters] " + TIME.format(Instant.ofEpochMilli(now).atZone(MOSCOW))
                    + " МСК · " + event.kind().id() + " у " + player.getName()
                    + " · уровень " + event.level() + " · блоков изменено: " + changed);
        }
    }

    /** Ручной запуск админом: та же защита приватов, но оповещение без кулдауна. */
    public boolean runNow(DisasterKind kind, int level, Location at, Player target) {
        DisastersConfig config = plugin.settings();
        if (plugin.protection().areaBlocked(at, config.radius())) {
            return false;
        }
        int changed = plugin.effects().run(kind, at, level);
        plugin.broadcaster().announce(kind, level, List.of(target), true);
        if (changed > 0) {
            scheduleRestore(config);
        }
        return true;
    }

    private void scheduleRestore(DisastersConfig config) {
        if (restoreScheduled || config.restoreAfterSeconds() <= 0) {
            return;
        }
        restoreScheduled = true;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            restoreScheduled = false;
            plugin.restore().restoreAll();
        }, 20L * config.restoreAfterSeconds());
    }
}
