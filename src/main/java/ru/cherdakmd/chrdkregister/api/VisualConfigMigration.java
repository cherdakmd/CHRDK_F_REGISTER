package ru.cherdakmd.chrdkregister.api;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Explicitly opted-in, one-time visual settings migration. Never operates on game data. */
public final class VisualConfigMigration {
    public static final String OPT_IN = "enable-chrdk-textures-once";
    private static final String MARKER = "chrdk-textures-rc61-migrated";
    static final Map<String, List<String>> PATHS = Map.ofEntries(
            Map.entry("CHRDK_Core", List.of("server-menu.textures.enabled", "guide.textures.book-item",
                    "core-gui-textures.pass-menu", "core-gui-textures.donate-menu", "core-gui-textures.rank-menu",
                    "core-gui-textures.profile-menu", "core-gui-textures.keypad-controls", "core-gui-textures.labels-menu",
                    "core-gui-textures.admin-donations", "core-gui-textures.admin-panel", "core-gui-textures.honor-menu")),
            Map.entry("CHRDK_Achievements", List.of("textures.menu", "textures.atlas-menu")),
            Map.entry("CHRDK_Artifacts", List.of("textures.artifacts", "textures.collection-menu")),
            Map.entry("CHRDK_BattlePass", List.of("textures.atlas-menu", "textures.mission-menu", "textures.guide-menu", "textures.expedition-menu")),
            Map.entry("CHRDK_Claims", List.of("textures.menu")),
            Map.entry("CHRDK_Cosmetics", List.of("textures.menu")),
            Map.entry("CHRDK_CustomItems", List.of("prototype-textures", "textures.catalog-menu")),
            Map.entry("CHRDK_DailyRewards", List.of("textures.menu")),
            Map.entry("CHRDK_DiamondShop", List.of("textures.book-items", "textures.quantity-menu", "textures.market-menu", "textures.admin-menu")),
            Map.entry("CHRDK_Enchants", List.of("textures.books", "textures.hub-menu", "textures.catalog-menu", "textures.workshop-menu", "textures.new-villager-books", "textures.new-table-books")),
            Map.entry("CHRDK_Fishing", List.of("textures.enabled", "textures.menu", "textures.album-menu", "textures.bait-menus", "textures.bait-items", "textures.dish-items", "textures.cooking-menu", "textures.orders-menu", "textures.sale-menu", "textures.rod-menu")),
            Map.entry("CHRDK_Generator", List.of("textures.core-items", "textures.menu")),
            Map.entry("CHRDK_Guilds", List.of("textures.party-menu", "textures.recruit-menu", "textures.guild-menu", "textures.style-menu")),
            Map.entry("CHRDK_Mobs", List.of("textures.loot-items")),
            Map.entry("CHRDK_Quests", List.of("textures.menu")),
            Map.entry("CHRDK_Skills", List.of("textures.menu", "textures.crystals", "textures.rebirth-items")),
            Map.entry("CHRDK_VK", List.of("textures.menu"))
    );
    private VisualConfigMigration() { }

    /** Called immediately after saveDefaultConfig, before using the settings. */
    public static void apply(JavaPlugin plugin) {
        List<String> paths = PATHS.get(plugin.getName());
        if (paths == null) return;
        Path dir = plugin.getDataFolder().toPath();
        Path parent = dir.getParent();
        if (parent == null) return;
        Path optIn = parent.resolve("CHRDK_Core").resolve(OPT_IN);
        if (!Files.isRegularFile(optIn, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            if (migrate(dir.resolve("config.yml"), paths)) {
                plugin.reloadConfig();
                plugin.getLogger().info("Оформление CHRDK включено однократно; исходный конфиг сохранён как config-before-chrdk-textures-rc61.yml");
            }
        } catch (IOException | InvalidConfigurationException | RuntimeException e) {
            // Parser exception messages may contain credentials. Do not log them.
            plugin.getLogger().severe("Не удалось включить оформление CHRDK (" + e.getClass().getSimpleName()
                    + "); изменения конфига не подтверждены. Проверьте резервную копию и права доступа.");
        }
    }

    static boolean migrate(Path file, List<String> paths) throws IOException, InvalidConfigurationException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("unsafe config");
        byte[] original = Files.readAllBytes(file);
        YamlConfiguration config = new YamlConfiguration();
        config.options().parseComments(true);
        config.loadFromString(new String(original, StandardCharsets.UTF_8));
        if (config.contains(MARKER, true)) {
            if (!config.isBoolean(MARKER)) throw new IOException("invalid marker");
            if (config.getBoolean(MARKER)) return false;
            throw new IOException("marker is false");
        }
        for (String path : paths) {
            String[] segments = path.split("\\.");
            String prefix = "";
            for (int i = 0; i < segments.length - 1; i++) {
                prefix = prefix.isEmpty() ? segments[i] : prefix + "." + segments[i];
                if (config.contains(prefix, true) && !config.isConfigurationSection(prefix))
                    throw new IOException("visual parent is not a section");
            }
            if (config.contains(path, true) && !config.isBoolean(path)) throw new IOException("visual setting is not a boolean");
        }
        Map<String, Object> untouched = scalarValues(config, new HashSet<>(paths));
        for (String path : paths) config.set(path, true);
        config.set(MARKER, true);
        String rendered = config.saveToString();
        YamlConfiguration check = new YamlConfiguration();
        check.options().parseComments(true);
        check.loadFromString(rendered);
        if (!untouched.equals(scalarValues(check, new HashSet<>(paths)))) throw new IOException("unrelated configuration changed");
        if (!check.getBoolean(MARKER)) throw new IOException("marker missing");
        for (String path : paths) if (!check.isBoolean(path) || !check.getBoolean(path)) throw new IOException("visual setting missing");
        Path backup = file.resolveSibling("config-before-chrdk-textures-rc61.yml");
        if (!Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) Files.copy(file, backup, StandardCopyOption.COPY_ATTRIBUTES);
        if (!Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS) || !Arrays.equals(original, Files.readAllBytes(backup)))
            throw new IOException("existing backup does not match configuration");
        Path temp = Files.createTempFile(file.toAbsolutePath().getParent(), "chrdk-textures-", ".tmp");
        try {
            try { Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS)); }
            catch (UnsupportedOperationException ignored) { }
            Files.writeString(temp, rendered, StandardCharsets.UTF_8);
            if (!Arrays.equals(original, Files.readAllBytes(file))) throw new IOException("config changed during migration");
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
        return true;
    }

    private static Map<String, Object> scalarValues(ConfigurationSection config, Set<String> excluded) {
        Map<String, Object> result = new LinkedHashMap<>();
        config.getValues(true).forEach((key, value) -> {
            if (!(value instanceof ConfigurationSection) && !excluded.contains(key) && !key.equals(MARKER)) result.put(key, value);
        });
        return result;
    }
}
