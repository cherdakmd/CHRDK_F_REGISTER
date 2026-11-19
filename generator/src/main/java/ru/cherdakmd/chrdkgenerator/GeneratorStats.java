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

    /**
     * Прогресс до следующего тира, 0…100: доля накопленных денег (копилки)
     * от денежной части цены шага; на максимуме — 100.
     */
    static int tierProgressPercent(GeneratorStore.Entry entry, GeneratorConfig config) {
        if (entry == null) {
            return 0;
        }
        if (entry.tier() >= GeneratorConfig.TIERS) {
            return 100;
        }
        long cost = GeneratorUpgrades.tierCost(config, entry.tier() + 1).money();
        if (cost <= 0) {
            return 100;
        }
        return (int) Math.max(0L, Math.min(100L, entry.money() * 100 / cost));
    }

    /**
     * Секунд до полного бункера при текущем темпе (с бонусом перерождений);
     * {@code -1} — генератор не установлен, не производит или бункер полон.
     */
    static long etaSeconds(GeneratorStore.Entry entry, GeneratorConfig config) {
        if (entry == null || !entry.installed()) {
            return -1L;
        }
        long room = capacityUnits(entry, config) - entry.bunker();
        if (room <= 0) {
            return -1L;
        }
        double bonus = GeneratorMath.rebirthBonusPercent(
                config.rebirthPercentPerRebirth(), config.rebirthDecay(),
                config.rebirthBonusCapPercent(), entry.rebirths());
        double unitsPerCycle = config.tier(entry.tier()).unitsPerCycle() * (1.0 + bonus / 100.0);
        if (unitsPerCycle <= 0) {
            return -1L;
        }
        return (long) Math.ceil(room * cycleSeconds(entry, config) / unitsPerCycle);
    }

    /** Время до полного бункера текстом («3ч 20м»); «—» — считать нечего. */
    static String etaText(GeneratorStore.Entry entry, GeneratorConfig config) {
        long seconds = etaSeconds(entry, config);
        if (seconds < 0) {
            return "—";
        }
        long minutes = (seconds + 59) / 60;
        if (minutes < 60) {
            return minutes + "м";
        }
        return (minutes / 60) + "ч " + String.format(java.util.Locale.ROOT, "%02d", minutes % 60) + "м";
    }
}
