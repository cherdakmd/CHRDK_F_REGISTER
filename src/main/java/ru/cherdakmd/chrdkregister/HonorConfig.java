package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Настройки блока {@code honors:} в config.yml. Значения проверяются при чтении. */
public record HonorConfig(boolean enabled, boolean announceGrant, int maxReasonLength,
                          String defaultReason, boolean showInTab,
                          List<InitialGrant> initialGrants) {

    /**
     * Персональная благодарность, которую владелец прописал в конфиге.
     *
     * <p>Применяется только при первом запуске, когда {@code honors.json} ещё
     * нет: существующий файл с решениями администрации никогда не переписывается.
     * Причина и автор видны в {@code /honor list}.</p>
     */
    public record InitialGrant(String player, String honorId, String reason) {
    }

    public static final String DEFAULT_REASON = "За выслугу лет по решению владельца";
    public static final int MAX_INITIAL_GRANTS = 32;

    public static final HonorConfig DEFAULT =
            new HonorConfig(true, false, 160, DEFAULT_REASON, true, List.of());

    public static HonorConfig fromConfig(FileConfiguration config) {
        if (config == null) return DEFAULT;
        boolean enabled = config.getBoolean("honors.enabled", true);
        boolean announce = config.getBoolean("honors.announce-grant", false);
        int limit = clamp(config.getInt("honors.max-reason-length", 160), 20, 500);
        String reason = config.getString("honors.default-reason", DEFAULT_REASON);
        if (reason == null || reason.isBlank()) reason = DEFAULT_REASON;
        boolean tab = config.getBoolean("honors.show-in-tab", true);
        return new HonorConfig(enabled, announce, limit, reason.trim(), tab,
                initialGrants(config, reason.trim(), limit));
    }

    /** Список из конфига: кривые ники и неизвестные звания молча отбрасываются. */
    private static List<InitialGrant> initialGrants(FileConfiguration config,
                                                    String defaultReason, int reasonLimit) {
        List<InitialGrant> out = new ArrayList<>();
        for (Map<?, ?> entry : config.getMapList("honors.initial-grants")) {
            if (entry == null || out.size() >= MAX_INITIAL_GRANTS) break;
            String player = text(entry.get("player"));
            String honor = HonorDefinition.normalizeId(text(entry.get("honor")));
            if (!AccessManager.isValidMinecraftName(player) || honor.isEmpty()) continue;
            String reason = text(entry.get("reason"));
            if (reason.isEmpty()) reason = defaultReason;
            if (reason.length() > reasonLimit) reason = reason.substring(0, reasonLimit).trim();
            out.add(new InitialGrant(player, honor, reason));
        }
        return List.copyOf(out);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
