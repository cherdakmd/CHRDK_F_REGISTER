package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.FileConfiguration;

/** Настройки блока {@code honors:} в config.yml. Значения проверяются при чтении. */
public record HonorConfig(boolean enabled, boolean announceGrant, int maxReasonLength,
                          String defaultReason, boolean showInTab) {

    public static final String DEFAULT_REASON = "За выслугу лет по решению владельца";

    public static final HonorConfig DEFAULT =
            new HonorConfig(true, false, 160, DEFAULT_REASON, true);

    public static HonorConfig fromConfig(FileConfiguration config) {
        if (config == null) return DEFAULT;
        boolean enabled = config.getBoolean("honors.enabled", true);
        boolean announce = config.getBoolean("honors.announce-grant", false);
        int limit = clamp(config.getInt("honors.max-reason-length", 160), 20, 500);
        String reason = config.getString("honors.default-reason", DEFAULT_REASON);
        if (reason == null || reason.isBlank()) reason = DEFAULT_REASON;
        boolean tab = config.getBoolean("honors.show-in-tab", true);
        return new HonorConfig(enabled, announce, limit, reason.trim(), tab);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
