package ru.cherdakmd.chrdkenchants;

/** Утилиты без добавочной добычи/XP; предохранитель отменяет добычу, не ремонтирует. */
enum UtilityEnchant {
    CAREFUL_WORK("careful_work", 3), GARDEN_STEP("garden_step", 1),
    AIR_RESERVE("air_reserve", 2), QUIET_STEP("quiet_step", 1),
    TOOL_SAFEGUARD("tool_safeguard", 1), FROST_RESISTANCE("frost_resistance", 2), HARVEST_REPLANT("harvest_replant", 1);
    final String id;
    final int maxLevel;
    UtilityEnchant(String id, int maxLevel) { this.id = id; this.maxLevel = maxLevel; }
    static UtilityEnchant byId(String id) {
        for (var value : values()) if (value.id.equals(id)) return value;
        return null;
    }
    int level(int value) { return Math.max(1, Math.min(maxLevel, value)); }
    int chance(int value) { return this == CAREFUL_WORK ? level(value) * 10 : 0; }
    int air(int value) { return this == AIR_RESERVE ? 20 + level(value) * 20 : 0; }
    int freezePercent(int value) { return this == FROST_RESISTANCE ? 25 * level(value) : 0; }
    static boolean unsafe(int maximum, int damage) { return maximum > 0 && (long) maximum - damage <= 2; }
    long cooldownMillis() { return this == CAREFUL_WORK ? 8000 : this == AIR_RESERVE ? 60000 : 0; }
}
