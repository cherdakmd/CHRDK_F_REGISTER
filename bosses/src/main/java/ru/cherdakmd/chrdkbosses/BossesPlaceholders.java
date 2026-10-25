package ru.cherdakmd.chrdkbosses;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Плейсхолдеры PlaceholderAPI: {@code %chrdkbosses_...%} (1.4.162).
 *
 * <p>Только чтение готового состояния — таб и скорборд спрашивают их часто.
 * Победы игрока берутся из общей памяти аддона, а живой бой и зона — у
 * сервиса: если босс не идёт, плейсхолдер отдаёт пустую строку, а не «0»,
 * чтобы условие в скорборде не срабатывало ложно.</p>
 */
public final class BossesPlaceholders extends PlaceholderExpansion {

    private final BossesPlugin plugin;

    BossesPlaceholders(BossesPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "chrdkbosses";
    }

    @Override
    public @NotNull String getAuthor() {
        return "cherdakmd";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        String key = params.toLowerCase(Locale.ROOT);
        BossConfig config = plugin.config();
        if (plugin.service() == null || config == null) {
            return "";
        }
        Player online = player == null ? null : player.getPlayer();
        if (key.startsWith("next_")) {
            String id = key.substring("next_".length());
            BossDefinition definition = config.boss(id);
            if (definition == null) {
                return "";
            }
            long seconds = plugin.service().secondsToSpawn(definition.id(),
                    System.currentTimeMillis());
            return seconds <= 0 ? "0" : BossMath.clock(seconds);
        }
        if (key.startsWith("kills_")) {
            String id = key.substring("kills_".length());
            BossDefinition definition = config.boss(id);
            return definition == null ? "" : Integer.toString(plugin.store().kills(definition.id()));
        }
        if (key.startsWith("alive_")) {
            String id = key.substring("alive_".length());
            BossDefinition definition = config.boss(id);
            if (definition == null) {
                return "";
            }
            ActiveBoss boss = plugin.service().current(definition.id());
            return boss == null ? "" : Integer.toString(Math.round(boss.progress() * 100));
        }
        return switch (key) {
            case "alive" -> Integer.toString(plugin.service().aliveCount());
            case "total" -> Integer.toString(config.bossList().size());
            case "kills" -> player == null ? "" : Integer.toString(plugin.store().killsAll());
            case "zones" -> Integer.toString(config.activeZones().size());
            case "zone" -> online == null ? "" : zoneName(plugin.service().zoneAt(online));
            case "zone_bonus" -> online == null ? "" : zoneBonus(plugin.service().zoneAt(online));
            case "zone_level" -> online == null ? "" : zoneLevel(plugin.service().zoneAt(online));
            case "enabled" -> config.enabled() ? "true" : "false";
            default -> "";
        };
    }

    private static String zoneName(DangerZone zone) {
        return zone == null ? "" : zone.name();
    }

    private static String zoneBonus(DangerZone zone) {
        return zone == null ? "" : Integer.toString(zone.xpBonusPercent());
    }

    private static String zoneLevel(DangerZone zone) {
        return zone == null ? "" : zone.levelLabel();
    }
}
