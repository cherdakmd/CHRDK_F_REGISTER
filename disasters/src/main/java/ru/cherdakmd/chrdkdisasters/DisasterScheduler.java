package ru.cherdakmd.chrdkdisasters;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * Расписание катастроф: по одному таймеру на игрока, без Bukkit и без сервера.
 * Выделено в отдельный класс, чтобы всю логику можно было проверить JUnit.
 *
 * <p>Таймеры живут в памяти и не переживают рестарт сервера — это осознанный
 * выбор первой версии: после рестарта всем выдаётся новая пауза, а не
 * «накопленные» катастрофы. Игроки с правом {@code chrdkdisasters.bypass}
 * сюда просто не попадают.</p>
 */
public final class DisasterScheduler {

    /** Одно запланированное событие. */
    public record Scheduled(String player, DisasterKind kind, int level, long at) {
    }

    private final Map<String, Long> nextAt = new HashMap<>();
    private final long minMillis;
    private final long spread;
    private final int maxLevel;
    private final List<DisasterKind> kinds;
    private final Random random;

    public DisasterScheduler(long minMillis, long maxMillis, int maxLevel,
                             List<DisasterKind> kinds, long seed) {
        this.minMillis = Math.max(0L, minMillis);
        this.spread = Math.max(0L, maxMillis - minMillis);
        this.maxLevel = Math.max(1, maxLevel);
        this.kinds = kinds == null ? List.of() : List.copyOf(kinds);
        this.random = new Random(seed);
    }

    public static String canonical(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    /** Когда игроку «положена» следующая катастрофа; при первом обращении — запись. */
    public long nextFor(String player, long now) {
        String key = canonical(player);
        Long known = nextAt.get(key);
        if (known != null) {
            return known;
        }
        long jitter = spread == 0 ? 0 : (long) (random.nextDouble() * spread);
        long at = now + minMillis + jitter;
        nextAt.put(key, at);
        return at;
    }

    /** Срабатывание: возвращает событие только когда пауза истекла, и сразу перепланирует. */
    public Optional<Scheduled> poll(String player, long now) {
        String key = canonical(player);
        if (key.isEmpty() || kinds.isEmpty()) {
            return Optional.empty();
        }
        if (now < nextFor(key, now)) {
            return Optional.empty();
        }
        DisasterKind kind = kinds.get(random.nextInt(kinds.size()));
        int level = 1 + random.nextInt(maxLevel);
        Scheduled event = new Scheduled(key, kind, level, now);
        reset(key, now);
        return Optional.of(event);
    }

    /** Новая пауза (в том числе когда старт отменили из-за привата или спавна). */
    public void reset(String player, long now) {
        String key = canonical(player);
        long jitter = spread == 0 ? 0 : (long) (random.nextDouble() * spread);
        nextAt.put(key, now + minMillis + jitter);
    }

    public void forget(String player) {
        nextAt.remove(canonical(player));
    }

    public int size() {
        return nextAt.size();
    }
}
