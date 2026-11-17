package ru.cherdakmd.chrdkgenerator;

/**
 * Значения для меню и PlaceholderAPI — чистая арифметика без Bukkit,
 * чтобы плейсхолдеры отвечали из памяти и не лезли в мир (их спрашивают
 * десятки раз в секунду, в том числе асинхронно).
 */
final class GeneratorStats {

    private GeneratorStats() {
    }

    /** Номер тира, 0 — генератора нет. */
    static int tier(GeneratorStore.Entry entry) {
        return entry == null ? 0 : entry.tier();
    }

    /** Заполненность бункера в процентах 0…100 (потолок = ёмкость × offline-hours). */
    static int fillPercent(GeneratorStore.Entry entry, GeneratorConfig config) {
        if (entry == null) {
            return 0;
        }
        long capacity = capacityUnits(entry, config);
        if (capacity <= 0) {
            return 0;
        }
        return (int) Math.max(0L, Math.min(100L, entry.bunker() * 100 / capacity));
    }

    /** Потолок бункера в единицах. */
    static long capacityUnits(GeneratorStore.Entry entry, GeneratorConfig config) {
        return (long) GeneratorMath.bunkerCapacity(config.bunkerBase(),
                entry == null ? 0 : entry.modules().getOrDefault("capacity", 0)) * config.offlineHours();
    }

    /** Длительность производственного цикла в секундах (модуль «Скорость»). */
    static int cycleSeconds(GeneratorStore.Entry entry, GeneratorConfig config) {
        return GeneratorMath.cycleSeconds(config.cycleSeconds(),
                entry == null ? 0 : entry.modules().getOrDefault("speed", 0));
    }
}
