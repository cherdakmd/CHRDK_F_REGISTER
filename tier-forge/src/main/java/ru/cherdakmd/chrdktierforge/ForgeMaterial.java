package ru.cherdakmd.chrdktierforge;

import java.util.Locale;

/** Три расходника, не дополнительные уровни силы и не денежная валюта. */
enum ForgeMaterial {
    ASCEND("ascend", "ECHO_SHARD"), TEMPER("temper", "NAUTILUS_SHELL"), REFORGE("reforge", "PAPER");
    final String id;
    final String material;
    ForgeMaterial(String id, String material) { this.id = id; this.material = material; }
    static ForgeMaterial byId(String id) {
        if (id == null) return null;
        for (var value : values()) if (value.id.equals(id.toLowerCase(Locale.ROOT))) return value;
        return null;
    }
    static final java.util.List<Integer> ASCEND_COSTS = java.util.List.of(1, 1, 2, 4, 8, 16);
    static final java.util.List<Integer> ASCEND_XP = java.util.List.of(9, 12, 15, 18, 21, 24);
    static int ascendCost(int targetIndex) {
        if (targetIndex < 1 || targetIndex > 6) throw new IllegalArgumentException("Target tier");
        return ASCEND_COSTS.get(targetIndex - 1);
    }
    static int ascendXp(int targetIndex) { ascendCost(targetIndex); return ASCEND_XP.get(targetIndex - 1); }
    static java.util.Random choice(String identity, long sequence, ForgeMaterial kind, String selected) {
        return new java.util.Random(java.util.Objects.hash(identity, sequence, kind.id, selected.toLowerCase(Locale.ROOT)));
    }
    static long nextSequence(long current) {
        if (current < 0 || current == Long.MAX_VALUE) throw new IllegalArgumentException("Forge sequence exhausted");
        return current + 1;
    }
    static double chance(double base, int level, int elite) {
        if (!Double.isFinite(base) || level < 1 || level > 30 || elite < 0 || elite > ru.cherdakmd.chrdkregister.api.MobProgressionKeys.MAX_ELITE) return 0;
        return Math.max(0, Math.min(100, base * (1 + (level - 1) / 29.0) * (elite > 0 ? 3 : 1)));
    }
}
