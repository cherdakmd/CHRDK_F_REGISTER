package ru.cherdakmd.chrdkregister;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Полное снятие аддона питомцев не трогает остальное меню, рассылку и квитанции. */
class PetsRemovalTest {
    @TempDir Path directory;
    private YamlConfiguration yaml(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/" + name)) {
            assertNotNull(input, name);
            return YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
        }
    }

    @Test void removedModuleIsAbsentFromDistributionStockMenuAndAnnouncements() throws Exception {
        assertFalse(Files.exists(Path.of("pets")));
        assertFalse(PluginUpdateService.MODULES.containsKey("CHRDK_Pets"));
        assertEquals(19, PluginUpdateService.MODULES.size());
        var stock = yaml("config.yml"); var items = stock.getMapList("server-menu.items");
        assertEquals(45, items.size());
        assertFalse(items.stream().anyMatch(item -> RetiredAddons.plugin(String.valueOf(item.get("plugin")))
                || RetiredAddons.command(String.valueOf(item.get("command")))));
        assertFalse(items.stream().anyMatch(item -> String.valueOf(item.get("command")).startsWith("pet")));
        assertEquals(38, stock.getList("auto-messages.messages").size());
        assertEquals(stock.getList("auto-messages.messages").size(), ChatConfig.fromBukkitConfig(stock).autoMessageEntries().size());
        for (Object raw : stock.getList("auto-messages.messages"))
            assertFalse(RetiredAddons.announcement(raw instanceof Map<?, ?> map ? String.valueOf(map.get("text")) : String.valueOf(raw)));
    }

    @Test void petCommandsPluginsAndPermissionsStayRetiredForCustomContent() {
        for (String command : List.of("pet", "/pet list", "pets", "petadmin", "petsadmin",
                "/CHRDK_PETS:PET name", "/other:petsadmin reload")) {
            assertTrue(RetiredAddons.command(command), command);
            var entry = new ServerMenuSettings.Entry(-1, Material.PAPER, "Custom", "", command);
            assertFalse(entry.available(plugin -> true, permission -> true));
        }
        assertTrue(RetiredAddons.plugin("CHRDK_Pets"));
        assertTrue(RetiredAddons.permission("chrdkpets.use"));
        assertTrue(RetiredAddons.announcement("Приручённых питомцев смотрите через /pet list"));
        for (String keep : List.of("guilds", "clan", "minecraft:time query daytime", "menu"))
            assertFalse(RetiredAddons.command(keep), keep);
        var entry = new ServerMenuSettings.Entry(-1, Material.PAPER, "Custom", "", "custom", "CHRDK_Pets", "");
        assertFalse(entry.available(plugin -> true, permission -> true));
    }

    @Test void exactStockWithPetsMigratesAnnouncementsAndMenuToTheNewSet() throws Exception {
        var legacy = yaml("presentation-legacy-2.0.13.yml");
        var live = yaml("presentation-legacy-2.0.13.yml");
        var fresh = yaml("config.yml");
        assertTrue(PresentationMigration.LEGACY_RESOURCES.contains("presentation-legacy-2.0.13.yml"));
        assertTrue(legacy.getMapList("server-menu.items").stream()
                .anyMatch(item -> "pet list".equals(item.get("command"))));
        var changes = PresentationMigration.replacements(live, List.of(legacy), fresh);
        assertTrue(changes.contains("server-menu.items"), changes::toString);
        assertTrue(changes.contains("auto-messages.messages"), changes::toString);
        for (String key : changes) live.set(key, fresh.get(key));
        assertFalse(live.getMapList("server-menu.items").stream()
                .anyMatch(item -> String.valueOf(item.get("command")).startsWith("pet")));
        assertFalse(live.getList("auto-messages.messages").stream()
                .anyMatch(raw -> String.valueOf(raw).contains("/pet list")));
        assertEquals(45, live.getMapList("server-menu.items").size());
        assertEquals(38, live.getList("auto-messages.messages").size());
    }
}
