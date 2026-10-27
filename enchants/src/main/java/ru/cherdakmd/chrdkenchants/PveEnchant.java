package ru.cherdakmd.chrdkenchants;

/** PvE-контроль и тактика: без урона, экономики и суммирования уровней брони. */
enum PveEnchant {
    CRIPPLING_EDGE("crippling_edge", Channel.MELEE, "SLOWNESS", 3, 12),
    SAPPING_EDGE("sapping_edge", Channel.MELEE, "WEAKNESS", 2, 15),
    PINNING_SHOT("pinning_shot", Channel.RANGED, "SLOWNESS", 3, 12),
    SAPPING_SHOT("sapping_shot", Channel.RANGED, "WEAKNESS", 2, 15),
    BRIAR_GUARD("briar_guard", Channel.GUARD, "SLOWNESS", 2, 15),
    FALTERING_GUARD("faltering_guard", Channel.GUARD, "WEAKNESS", 2, 15),
    PROVOKING_STRIKE("provoking_strike", Channel.MELEE, "TARGET", 2, 15),
    SIGNAL_GUARD("signal_guard", Channel.GUARD, "GLOWING", 2, 15);

    enum Channel { MELEE, RANGED, GUARD }
    final String id;
    final Channel channel;
    final String effect;
    final int maxLevel, cooldownSeconds;
    PveEnchant(String id, Channel channel, String effect, int maxLevel, int cooldownSeconds) {
        this.id = id; this.channel = channel; this.effect = effect;
        this.maxLevel = maxLevel; this.cooldownSeconds = cooldownSeconds;
    }
    static PveEnchant byId(String id) {
        for (PveEnchant value : values()) if (value.id.equals(id)) return value;
        return null;
    }
    int level(int level) { return Math.max(1, Math.min(maxLevel, level)); }
    int chance(int level) { return maxLevel == 3 ? 15 + level(level) * 5 : 10 + level(level) * 5; }
    int ticks(int level) { return this == PROVOKING_STRIKE ? 0 : (1 + level(level)) * 20; }
}
