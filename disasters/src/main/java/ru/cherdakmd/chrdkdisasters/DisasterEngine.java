package ru.cherdakmd.chrdkdisasters;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Движок второй итерации: расписание игроков, реестр активных катастроф и
 * машина фаз. Две задачи на главном потоке — редкий тик расписания (по
 * {@code tick-seconds}) и частый тик фаз (по {@code phases.tick-seconds}).
 *
 * <p>Реестр нужен для {@code /disastersadmin list} и остановки конкретного
 * события по id. Каждый запуск меняет мир только в активной фазе и порциями,
 * а по завершении снимает эффекты состояния и планирует общий откат блоков.</p>
 */
public final class DisasterEngine {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final DisastersPlugin plugin;
    private final Map<Integer, DisasterRun> active = new LinkedHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Random random = new Random();
    private BukkitTask scheduleTask;
    private BukkitTask phaseTask;
    private boolean restoreScheduled;

    public DisasterEngine(DisastersPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        DisastersConfig config = plugin.settings();
        long schedule = 20L * Math.max(5, config.tickSeconds());
        long phase = 20L * Math.max(1, config.phases().tickSeconds());
        scheduleTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickSchedule, schedule, schedule);
        phaseTask = Bukkit.getScheduler().runTaskTimer(plugin, this::pulse, phase, phase);
    }

    /** Снимает задачи и активные эффекты; откат блоков делает вызывающий. */
    public void stop() {
        cancelTasks();
        for (DisasterRun run : active.values()) {
            plugin.effects().clearEffects(run);
        }
        active.clear();
    }

    private void cancelTasks() {
        if (scheduleTask != null) {
            scheduleTask.cancel();
            scheduleTask = null;
        }
        if (phaseTask != null) {
            phaseTask.cancel();
            phaseTask = null;
        }
    }

    /** Редкий тик: у кого истёк таймер — начинаем новую катастрофу. */
    private void tickSchedule() {
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
            DisasterProfile profile = plugin.progress().profile(player.getUniqueId());
            if (profile.isWardActive(now)) {
                // Купленный в магазине пропуск: пауза обновляется, события нет.
                plugin.scheduler().reset(player.getName(), now);
                continue;
            }
            Optional<DisasterScheduler.Scheduled> due = plugin.scheduler().poll(player.getName(), now);
            if (due.isEmpty()) {
                continue;
            }
            int steadiness = DisasterProgressMath.levelForXp(profile.xp(),
                    config.progression().baseXp(), config.progression().maxLevel());
            if (random.nextDouble() < DisasterProgressMath.avoidChance(steadiness)) {
                // Перк стойкости: опытный игрок иногда отделается предупреждением.
                plugin.scheduler().reset(player.getName(), now);
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
            int nearby = 0;
            double radiusSquared = (double) config.radius() * config.radius();
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (other.getWorld() == player.getWorld()
                        && !other.hasPermission("chrdkdisasters.bypass")
                        && other.getLocation().distanceSquared(at) <= radiusSquared) {
                    nearby++;
                }
            }
            int scaledLevel = DisasterProgressMath.scaledLevel(
                    event.level(), nearby, config.maxLevel());
            int id = begin(event.kind(), scaledLevel, at, player.getName(), false);
            plugin.getLogger().info("[CHRDK_Disasters] "
                    + TIME.format(Instant.ofEpochMilli(now).atZone(MOSCOW))
                    + " МСК · #" + id + " " + event.kind().id() + " у " + player.getName()
                    + " · уровень " + scaledLevel + " · игроков рядом " + nearby
                    + " · фаз " + config.phases().totalTicks());
        }
    }

    /** Частый тик: один шаг каждой активной катастрофы. */
    private void pulse() {
        DisastersConfig config = plugin.settings();
        if (!config.enabled() || !plugin.runtimeEnabled()) {
            for (DisasterRun run : List.copyOf(active.values())) {
                run.stopNow();
                finish(run, config);
            }
            return;
        }
        for (DisasterRun run : List.copyOf(active.values())) {
            if (!active.containsKey(run.id())) {
                continue;
            }
            switch (run.phase()) {
                case WARNING -> {
                    int step = run.plan().warnTicks() - run.ticksLeft();
                    plugin.effects().warning(run, Math.max(0, step));
                }
                case ACTIVE -> {
                    int changed = plugin.effects().active(run);
                    run.addChanged(changed);
                    run.countStep();
                }
                case FADING -> plugin.effects().fading(run);
                default -> {
                    // Завершённые запуски забирает finish() ниже.
                }
            }
            run.advance();
            if (run.done()) {
                finish(run, config);
            }
        }
    }

    private int begin(DisasterKind kind, int level, Location at, String player, boolean force) {
        DisastersConfig config = plugin.settings();
        if (active.size() >= config.maxConcurrent()) {
            return 0;
        }
        int id = nextId.getAndIncrement();
        DisasterRun run = new DisasterRun(id, kind, level, player,
                at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), config.phases());
        active.put(id, run);
        // Предупреждение покажет первый тик фаз: дублировать его здесь не нужно.
        Player target = Bukkit.getPlayerExact(player);
        List<Player> audience = target == null ? List.of() : List.of(target);
        plugin.broadcaster().announce(kind, level, audience, force);
        return id;
    }

    private void finish(DisasterRun run, DisastersConfig config) {
        active.remove(run.id());
        plugin.effects().clearEffects(run);
        // Награды начисляются и при досрочной остановке: игрок всё равно
        // пережил предупреждение и часть активной фазы.
        plugin.rewards().grant(run, center(run));
        if (run.changed() > 0) {
            scheduleRestore(config);
        }
        plugin.getLogger().info("[CHRDK_Disasters] #" + run.id() + " " + run.kind().id()
                + " завершена · блоков изменено: " + run.changed()
                + (run.stopped() ? " · остановлена вручную" : ""));
    }

    private Location center(DisasterRun run) {
        World world = Bukkit.getWorld(run.world());
        return world == null ? null : new Location(world, run.x(), run.y(), run.z());
    }

    /** @return id запуска или 0, если старт отменён приватом/спавном. */
    public int runNow(DisasterKind kind, int level, Location at, Player target) {
        DisastersConfig config = plugin.settings();
        if (plugin.protection().areaBlocked(at, config.radius())) {
            return 0;
        }
        return begin(kind, level, at, target.getName(), true);
    }

    /** Неизменяемый снимок реестра для команд и тестов. */
    public List<DisasterRun> active() {
        return List.copyOf(active.values());
    }

    /** Остановить одну катастрофу по id; блоки вернутся, если других нет. */
    public boolean stopOne(int id) {
        DisasterRun run = active.get(id);
        if (run == null) {
            return false;
        }
        run.stopNow();
        plugin.effects().clearEffects(run);
        finish(run, plugin.settings());
        if (active.isEmpty() && plugin.restore().size() > 0) {
            plugin.restore().restoreAll();
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
