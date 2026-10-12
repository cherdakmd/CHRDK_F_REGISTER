package ru.cherdakmd.chrdkartifacts;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Разбор config.yml: по-артефактные настройки (вкл/кулдаун/цена в уровнях).
 * Дефолты — из {@link ArtifactRegistry}; значения из файла клампятся.
 */
public final class ArtifactsConfig {

    public record Override(boolean enabled, int cooldownSeconds, int xpCostLevels) {
    }

    private final boolean requiresAuth;
    private final int refreshTicks;
    private final Map<String, Override> overrides;

    private ArtifactsConfig(boolean requiresAuth, int refreshTicks, Map<String, Override> overrides) {
        this.requiresAuth = requiresAuth;
        this.refreshTicks = refreshTicks;
        this.overrides = overrides;
    }

    public static ArtifactsConfig parse(FileConfiguration cfg, Logger logger) {
        boolean requiresAuth = cfg.getBoolean("general.require-auth", true);
        int refreshTicks = clamp(cfg.getInt("general.passive-refresh-ticks", 40), 10, 200);
        Map<String, Override> overrides = new HashMap<>();
        ConfigurationSection root = cfg.getConfigurationSection("artifacts");
        for (ArtifactDef def : ArtifactRegistry.all()) {
            boolean enabled = true;
            int cooldown = def.cooldownSeconds();
            int xp = def.xpCostLevels();
            if (root != null) {
                ConfigurationSection section = root.getConfigurationSection(def.id());
                if (section != null) {
                    enabled = section.getBoolean("enabled", true);
                    cooldown = section.getInt("cooldown-seconds", def.cooldownSeconds());
                    xp = section.getInt("xp-cost-levels", def.xpCostLevels());
                }
            }
            overrides.put(def.id(), new Override(enabled,
                    clamp(cooldown, 0, 86_400), clamp(xp, 0, 100)));
        }
        if (root != null) {
            for (String key : root.getKeys(false)) {
                if (ArtifactRegistry.byId(key) == null) {
                    logger.warning("[CHRDK_Artifacts] Неизвестный артефакт в config.yml: " + key);
                }
            }
        }
        return new ArtifactsConfig(requiresAuth, refreshTicks, Map.copyOf(overrides));
    }

    public boolean requiresAuth() {
        return requiresAuth;
    }

    public int refreshTicks() {
        return refreshTicks;
    }

    public boolean isEnabled(String artifactId) {
        Override ov = overrides.get(artifactId);
        return ov == null || ov.enabled();
    }

    public int cooldownSeconds(ArtifactDef def) {
        Override ov = overrides.get(def.id());
        return ov == null ? def.cooldownSeconds() : ov.cooldownSeconds();
    }

    public int xpCostLevels(ArtifactDef def) {
        Override ov = overrides.get(def.id());
        return ov == null ? def.xpCostLevels() : ov.xpCostLevels();
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }
}
