package ru.cherdakmd.chrdkreferrals;

import org.bukkit.configuration.file.FileConfiguration;

/** Тонкая обёртка над config.yml рефералки. */
public final class ReferralConfig {

    private final int bonusDaysInviter;
    private final int bonusDaysInvitee;
    private final boolean requireInviterRegistered;
    private final boolean joinHint;

    public ReferralConfig(FileConfiguration config) {
        this.bonusDaysInviter = Math.clamp(config.getInt("bonus-days.inviter", 7), 0, 365);
        this.bonusDaysInvitee = Math.clamp(config.getInt("bonus-days.invitee", 3), 0, 365);
        this.requireInviterRegistered = config.getBoolean("rules.require-inviter-registered", true);
        this.joinHint = config.getBoolean("rules.join-hint", true);
    }

    public int bonusDaysInviter() {
        return bonusDaysInviter;
    }

    public int bonusDaysInvitee() {
        return bonusDaysInvitee;
    }

    public boolean requireInviterRegistered() {
        return requireInviterRegistered;
    }

    public boolean joinHintEnabled() {
        return joinHint;
    }
}
