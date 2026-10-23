package ru.cherdakmd.chrdktreasures;

import java.util.Map;
import java.util.Random;

/**
 * Клад-карта (1.4.153): где зарыт тайник, какую зону копать и сколько
 * осталось.
 *
 * <p>Карта — не второй бросок клада, а другая работа: она не выпадает из блока
 * сама по себе, а ведёт в конкретное место. Поэтому содержимое тайника зависит
 * от редкости карты, а не от породы под ногами. Зона намеренно широкая:
 * игрок копает в отмеченном круге, и каждый честный блок приближает тайник.
 * Это честнее, чем «попади в один блок из миллиона», и не превращается в
 * бесконечную лотерею.</p>
 *
 * <p>Класс не зависит от Bukkit: карта описывается примитивами и проверяется
 * юнит-тестами. Bukkit-обвязка (предмет, PDC, мир) живёт в
 * {@link TreasureMapService}.</p>
 *
 * @param region    регион тайника — он же пул именных находок
 * @param tier      редкость тайника (гарантированная, не бросок по блоку)
 * @param world     имя мира; пусто — мир не сохранён (старая карта)
 * @param x         координата тайника по X
 * @param z         координата тайника по Z
 * @param radius    радиус зоны раскопок в блоках
 * @param needed    сколько блоков нужно раскопать всего
 * @param remaining сколько осталось раскопать
 * @param expiresAt время сгорания в миллисекундах; 0 — бессрочно
 */
public record TreasureMap(TreasureRegion region, TreasureTier tier, String world,
                          int x, int z, int radius, int needed, int remaining,
                          long expiresAt) {

    /** Меньше восьми блоков — уже не зона, а точка. */
    public static final int MIN_RADIUS = 8;
    /** Больше шестидесяти четырёх — карта перестаёт что-либо значить. */
    public static final int MAX_RADIUS = 64;
    /** Потолок раскопок: дольше игрок устанет раньше, чем найдёт. */
    public static final int MAX_DIGS = 64;

    public TreasureMap {
        region = region == null ? TreasureRegion.FOREST : region;
        tier = tier == null ? TreasureTier.COMMON : tier;
        world = world == null ? "" : world.trim();
        radius = Math.clamp(radius, MIN_RADIUS, MAX_RADIUS);
        needed = Math.clamp(needed, 1, MAX_DIGS);
        // Остаток не может быть больше целого: битый файл не сделает карту
        // «отрицательно раскопанной».
        remaining = Math.clamp(remaining, 0, needed);
    }

    /** Лежит ли точка в зоне этой карты. Мир обязан совпадать. */
    public boolean inZone(String worldName, int blockX, int blockZ) {
        if (worldName == null || !worldName.equals(world)) {
            return false;
        }
        long dx = (long) blockX - x;
        long dz = (long) blockZ - z;
        return dx * dx + dz * dz <= (long) radius * radius;
    }

    /** Сгорела ли карта. {@code 0} в {@code expiresAt} — бессрочная. */
    public boolean expired(long now) {
        return expiresAt > 0L && now >= expiresAt;
    }

    /** Тайник уже найден. */
    public boolean done() {
        return remaining <= 0;
    }

    /** Сколько блоков уже раскопано. */
    public int dug() {
        return needed - remaining;
    }

    /** Прогресс раскопок 0..100. */
    public int percent() {
        return (int) (dug() * 100L / needed);
    }

    /** Новый остаток; границы те же, что у конструктора. */
    public TreasureMap withRemaining(int value) {
        return new TreasureMap(region, tier, world, x, z, radius, needed, value, expiresAt);
    }

    /** Сколько осталось жить карте в миллисекундах (0 — бессрочно или истекла). */
    public long remainingMillis(long now) {
        return expiresAt <= 0L ? 0L : Math.max(0L, expiresAt - now);
    }

    /**
     * Сколько блоков копать по настройкам. Разброс обязателен: одинаковая
     * цифра на каждой карте превращает поиск в счётчик.
     */
    public static int digs(int min, int max, Random random) {
        int low = Math.clamp(min, 1, MAX_DIGS);
        int high = Math.clamp(max, low, MAX_DIGS);
        if (high <= low) {
            return low;
        }
        return random == null ? low : low + random.nextInt(high - low + 1);
    }

    /**
     * Редкость тайника по весам конфига. Веса нормализуются, поэтому админ
     * может писать любые числа: {@code 40/30/18/9/3} и {@code 4/3/1.8/0.9/0.3}
     * дают одно и то же распределение. Нулевая или битая таблица — обычный
     * тайник, а не исключение.
     */
    public static TreasureTier pickTier(Map<TreasureTier, Double> weights, Random random) {
        if (weights == null || weights.isEmpty()) {
            return TreasureTier.COMMON;
        }
        double total = 0.0;
        for (Double weight : weights.values()) {
            if (weight != null && Double.isFinite(weight) && weight > 0.0) {
                total += weight;
            }
        }
        if (total <= 0.0 || random == null) {
            return TreasureTier.COMMON;
        }
        double roll = random.nextDouble() * total;
        double passed = 0.0;
        for (TreasureTier tier : TreasureTier.values()) {
            Double weight = weights.get(tier);
            if (weight == null || !Double.isFinite(weight) || weight <= 0.0) {
                continue;
            }
            passed += weight;
            if (roll < passed) {
                return tier;
            }
        }
        // Погрешность double: добираем последнюю непустую редкость.
        for (int index = TreasureTier.values().length - 1; index >= 0; index--) {
            TreasureTier tier = TreasureTier.values()[index];
            Double weight = weights.get(tier);
            if (weight != null && Double.isFinite(weight) && weight > 0.0) {
                return tier;
            }
        }
        return TreasureTier.COMMON;
    }
}
