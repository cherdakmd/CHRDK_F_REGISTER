package ru.cherdakmd.chrdkhunt;

/** Только при первом природном появлении: минимальный уровень региона, без элиты. */
record NewcomerProtection(boolean enabled, int maxSkills, double radius) {
    record Spawn(int level, int elite) { }
    static NewcomerProtection settings(boolean enabled, int maxSkills, double radius) {
        return new NewcomerProtection(enabled, Math.clamp(maxSkills, 10, 50),
                Double.isFinite(radius) ? Math.clamp(radius, 4, 24) : 16);
    }
    boolean candidate(int skills, double distanceSquared) {
        return enabled && skills >= 10 && skills <= maxSkills && Double.isFinite(distanceSquared)
                && distanceSquared >= 0 && distanceSquared <= radius * radius;
    }
    Spawn apply(MobProgressionPolicy.Range range, int level, int elite, boolean nearbyNewcomer) {
        return enabled && nearbyNewcomer ? new Spawn(range.min(), 0) : new Spawn(level, elite);
    }
}
