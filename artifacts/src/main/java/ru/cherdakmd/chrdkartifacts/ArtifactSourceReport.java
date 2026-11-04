package ru.cherdakmd.chrdkartifacts;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import java.util.List;
import java.util.Locale;

/** Читает только разрешённые публичные поля sources, цены/предметы/коллекцию не меняет. */
final class ArtifactSourceReport {
    record Row(String key, double chance) { }
    private final ArtifactsPlugin plugin;
    ArtifactSourceReport(ArtifactsPlugin plugin) { this.plugin = plugin; }
    static String percent(double chance) {
        return Double.isFinite(chance) ? String.format(Locale.ROOT, "%.2f", chance) : "—";
    }
    static List<Row> chests(FileConfiguration raw, ArtifactsConfig config) {
        var section = raw.getConfigurationSection("sources.chests");
        if (section == null) return List.of();
        return section.getKeys(false).stream().sorted().map(key -> new Row(key, config.chestChancePercent(key))).toList();
    }
    static List<Row> mobs(FileConfiguration raw, ArtifactsConfig config) {
        var section = raw.getConfigurationSection("sources.mobs");
        if (section == null) return List.of();
        return section.getKeys(false).stream().sorted().flatMap(key -> {
            try {
                var type = EntityType.valueOf(key.toUpperCase(Locale.ROOT));
                return java.util.stream.Stream.of(new Row(type.name(), config.mobChancePercent(type)));
            } catch (IllegalArgumentException unknown) { return java.util.stream.Stream.<Row>empty(); }
        }).toList();
    }
    void send(Player player) {
        if (!player.isOnline() || !player.hasPermission("chrdkartifacts.use") || plugin.coreApi() == null
                || !plugin.coreApi().isAuthenticated(player)) return;
        var config = plugin.config();
        plugin.messages().send(player, "sources-header");
        var chests = chests(plugin.getConfig(), config);
        var mobs = mobs(plugin.getConfig(), config);
        if (chests.isEmpty()) plugin.messages().send(player, "sources-empty");
        for (Row row : chests) plugin.messages().send(player, "sources-chest", "key", row.key(), "chance", percent(row.chance()));
        if (mobs.isEmpty()) plugin.messages().send(player, "sources-empty");
        for (Row row : mobs) plugin.messages().send(player, "sources-mob", "type", row.key(), "chance", percent(row.chance()));
        plugin.messages().send(player, "sources-fishing", "chance", percent(config.fishingTreasurePercent()));
        plugin.messages().send(player, "sources-side", "good", config.goodSideWeightPercent(), "cursed", 100 - config.goodSideWeightPercent());
        plugin.messages().send(player, "sources-footer");
    }
}
