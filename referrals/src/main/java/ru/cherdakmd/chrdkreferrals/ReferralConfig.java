package ru.cherdakmd.chrdkreferrals;

import org.bukkit.configuration.file.FileConfiguration;

/** Тонкая обёртка над config.yml рефералки. */
public final class ReferralConfig {

    private final int bonusHoursInviter;
    private final int bonusHoursInvitee;
    private final boolean requireInviterRegistered;
    private final boolean joinHint;

    public ReferralConfig(FileConfiguration config) {
        // 1.4.184: бонус в часах, потолок одной награды — сутки. Старые ключи
        // bonus-days.* читаются как часы, чтобы конфиг без перезаписи не потерял бонус.
        this.bonusHoursInviter = Math.clamp(hours(config, "inviter", 7), 0, 24);
        this.bonusHoursInvitee = Math.clamp(hours(config, "invitee", 3), 0, 24);
        this.requireInviterRegistered = config.getBoolean("rules.require-inviter-registered", true);
        this.joinHint = config.getBoolean("rules.join-hint", true);
    }

    public int bonusHoursInviter() {
        return bonusHoursInviter;
    }

    public int bonusHoursInvitee() {
        return bonusHoursInvitee;
    }

    /** Часы бонуса: новый ключ {@code bonus-hours.<who>}, старый — {@code bonus-days.<who>}. */
    private static int hours(FileConfiguration config, String who, int fallback) {
        String key = "bonus-hours." + who;
        return config.contains(key) ? config.getInt(key, fallback)
                : config.getInt("bonus-days." + who, fallback);
    }

    public boolean requireInviterRegistered() {
        return requireInviterRegistered;
    }

    public boolean joinHintEnabled() {
        return joinHint;
    }
}
