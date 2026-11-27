package ru.cherdakmd.chrdkregister.api;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class VisualConfigMigrationTest {
    @TempDir Path dir;
    @Test void oneTimeMigrationPreservesUnrelatedValuesAndBackup() throws Exception {
        Path path = dir.resolve("config.yml");
        String original = "# sensitive settings\nauth:\n  enabled: true\n  password: 'not-a-real-secret'\nserver-menu:\n  enabled: true\n  title: Hello\ntextures:\n  crystals: false\n  menu: false\nitems:\n  - name: first\n    count: 3\n";
        Files.writeString(path, original);
        var targets = List.of("textures.crystals", "textures.menu", "server-menu.textures.enabled");
        assertTrue(VisualConfigMigration.migrate(path, targets));
        assertEquals(original, Files.readString(dir.resolve("config-before-chrdk-textures-rc61.yml")));
        var actual = YamlConfiguration.loadConfiguration(path.toFile());
        assertTrue(actual.getBoolean("textures.crystals"));
        assertTrue(actual.getBoolean("textures.menu"));
        assertTrue(actual.getBoolean("server-menu.textures.enabled"));
        assertTrue(actual.getBoolean("auth.enabled"));
        assertEquals("not-a-real-secret", actual.getString("auth.password"));
        assertEquals("Hello", actual.getString("server-menu.title"));
        assertEquals(3, actual.getMapList("items").get(0).get("count"));
        assertTrue(Files.readString(path).contains("# sensitive settings"));
        actual.set("textures.crystals", false); actual.save(path.toFile());
        byte[] modified = Files.readAllBytes(path);
        assertFalse(VisualConfigMigration.migrate(path, targets));
        assertArrayEquals(modified, Files.readAllBytes(path));
    }
    @Test void conflictingScalarIsNeverReplacedAndNoBackupCreated() throws Exception {
        Path path = dir.resolve("config.yml"); String original = "textures: custom\nprivate: keep\n";
        Files.writeString(path, original);
        assertThrows(Exception.class, () -> VisualConfigMigration.migrate(path, List.of("textures.menu")));
        assertEquals(original, Files.readString(path));
        assertFalse(Files.exists(dir.resolve("config-before-chrdk-textures-rc61.yml")));
    }
    @Test void mismatchedExistingBackupStopsMigration() throws Exception {
        Path path=dir.resolve("config.yml"); Files.writeString(path,"textures:\n  menu: false\n");
        Files.writeString(dir.resolve("config-before-chrdk-textures-rc61.yml"),"other\n");
        assertThrows(Exception.class, () -> VisualConfigMigration.migrate(path, List.of("textures.menu")));
        assertEquals("textures:\n  menu: false\n", Files.readString(path));
    }
    @Test void everyDefaultConfigMigratesWithoutChangingUnrelatedData() throws Exception {
        var directories = java.util.Map.ofEntries(
                java.util.Map.entry("CHRDK_Core", "."), java.util.Map.entry("CHRDK_Achievements", "achievements"),
                java.util.Map.entry("CHRDK_Artifacts", "artifacts"), java.util.Map.entry("CHRDK_BattlePass", "battlepass"),
                java.util.Map.entry("CHRDK_Claims", "claims"), java.util.Map.entry("CHRDK_Cosmetics", "cosmetics"),
                java.util.Map.entry("CHRDK_CustomItems", "custom-items"), java.util.Map.entry("CHRDK_DailyRewards", "daily-rewards"),
                java.util.Map.entry("CHRDK_DiamondShop", "diamond-shop"), java.util.Map.entry("CHRDK_Enchants", "enchants"),
                java.util.Map.entry("CHRDK_Fishing", "fishing"), java.util.Map.entry("CHRDK_Generator", "generator"),
                java.util.Map.entry("CHRDK_Guilds", "guilds"), java.util.Map.entry("CHRDK_Mobs", "mobs"),
                java.util.Map.entry("CHRDK_Quests", "quests"), java.util.Map.entry("CHRDK_Skills", "skills"),
                java.util.Map.entry("CHRDK_VK", "vk"));
        assertEquals(VisualConfigMigration.PATHS.keySet(), directories.keySet());
        for (var entry : directories.entrySet()) {
            Path folder = dir.resolve(entry.getKey()); Files.createDirectory(folder);
            Path file = folder.resolve("config.yml");
            Files.copy(Path.of(entry.getValue(), "src/main/resources/config.yml"), file);
            assertTrue(VisualConfigMigration.migrate(file, VisualConfigMigration.PATHS.get(entry.getKey())), entry.getKey());
            var config = YamlConfiguration.loadConfiguration(file.toFile());
            for (var setting : VisualConfigMigration.PATHS.get(entry.getKey()))
                assertTrue(config.getBoolean(setting), entry.getKey()+" "+setting);
            assertFalse(VisualConfigMigration.migrate(file, VisualConfigMigration.PATHS.get(entry.getKey())));
        }
    }
    @Test void pluginDoesNotMigrateWithoutExplicitOptIn() throws Exception {
        Path pluginFolder = dir.resolve("CHRDK_Skills"); Files.createDirectories(pluginFolder);
        Path config = pluginFolder.resolve("config.yml"); Files.writeString(config, "textures:\n  crystals: false\n");
        var plugin = org.mockito.Mockito.mock(org.bukkit.plugin.java.JavaPlugin.class);
        org.mockito.Mockito.when(plugin.getName()).thenReturn("CHRDK_Skills");
        org.mockito.Mockito.when(plugin.getDataFolder()).thenReturn(pluginFolder.toFile());
        VisualConfigMigration.apply(plugin);
        assertEquals("textures:\n  crystals: false\n",Files.readString(config));
        org.mockito.Mockito.verify(plugin, org.mockito.Mockito.never()).reloadConfig();
    }
    @Test void optedInPluginMigratesOnceAndThenRespectsOptOut() throws Exception {
        Path core = dir.resolve("CHRDK_Core"), skill = dir.resolve("CHRDK_Skills");
        Files.createDirectories(core); Files.createDirectories(skill);
        Files.writeString(core.resolve(VisualConfigMigration.OPT_IN), "");
        Path config = skill.resolve("config.yml"); Files.writeString(config, "textures:\n  crystals: false\n");
        var plugin = org.mockito.Mockito.mock(org.bukkit.plugin.java.JavaPlugin.class);
        org.mockito.Mockito.when(plugin.getName()).thenReturn("CHRDK_Skills");
        org.mockito.Mockito.when(plugin.getDataFolder()).thenReturn(skill.toFile());
        org.mockito.Mockito.when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("visual-test"));
        VisualConfigMigration.apply(plugin);
        var yaml = YamlConfiguration.loadConfiguration(config.toFile());
        assertTrue(yaml.getBoolean("textures.crystals"));
        org.mockito.Mockito.verify(plugin, org.mockito.Mockito.times(1)).reloadConfig();
        yaml.set("textures.crystals", false); yaml.save(config.toFile());
        VisualConfigMigration.apply(plugin);
        assertFalse(YamlConfiguration.loadConfiguration(config.toFile()).getBoolean("textures.crystals"));
        org.mockito.Mockito.verify(plugin, org.mockito.Mockito.times(1)).reloadConfig();
    }
}
