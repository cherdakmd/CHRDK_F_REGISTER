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

/** Полное снятие аддона доски заказов не трогает остальное меню, рассылку и квитанции. */
class OrdersRemovalTest {
    @TempDir Path directory;
    private YamlConfiguration yaml(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/" + name)) {
            assertNotNull(input, name);
            return YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
        }
    }

    @Test void removedModuleIsAbsentFromDistributionStockMenuAndAnnouncements() throws Exception {
        assertFalse(Files.exists(Path.of("orders")));
        assertFalse(PluginUpdateService.MODULES.containsKey("CHRDK_Orders"));
        assertEquals(19, PluginUpdateService.MODULES.size());
        var stock = yaml("config.yml"); var items = stock.getMapList("server-menu.items");
        assertEquals(44, items.size());
        assertFalse(items.stream().anyMatch(item -> RetiredAddons.plugin(String.valueOf(item.get("plugin")))
                || RetiredAddons.command(String.valueOf(item.get("command")))));
        assertFalse(items.stream().anyMatch(item -> String.valueOf(item.get("command")).startsWith("order")));
        assertEquals(36, stock.getList("auto-messages.messages").size());
        assertEquals(stock.getList("auto-messages.messages").size(), ChatConfig.fromBukkitConfig(stock).autoMessageEntries().size());
        for (Object raw : stock.getList("auto-messages.messages"))
            assertFalse(RetiredAddons.announcement(raw instanceof Map<?, ?> map ? String.valueOf(map.get("text")) : String.valueOf(raw)));
    }

    @Test void orderCommandsPluginsAndPermissionsStayRetiredForCustomContent() {
        for (String command : List.of("order", "/orders list", "orders", "orderadmin", "ordersadmin",
                "/CHRDK_ORDERS:ORDERS list", "/other:ordersadmin reload")) {
            assertTrue(RetiredAddons.command(command), command);
            var entry = new ServerMenuSettings.Entry(-1, Material.PAPER, "Custom", "", command);
            assertFalse(entry.available(plugin -> true, permission -> true));
        }
        assertTrue(RetiredAddons.plugin("CHRDK_Orders"));
        assertTrue(RetiredAddons.permission("chrdkorders.use"));
        assertTrue(RetiredAddons.announcement("Заказы ботов смотрите через /orders list"));
        for (String keep : List.of("guilds", "clan", "minecraft:time query daytime", "menu"))
            assertFalse(RetiredAddons.command(keep), keep);
        var entry = new ServerMenuSettings.Entry(-1, Material.PAPER, "Custom", "", "custom", "CHRDK_Orders", "");
        assertFalse(entry.available(plugin -> true, permission -> true));
    }

    @Test void exactStockWithOrdersMigratesAnnouncementsAndMenuToTheNewSet() throws Exception {
        var legacy = yaml("presentation-legacy-2.0.13.yml");
        var live = yaml("presentation-legacy-2.0.13.yml");
        var fresh = yaml("config.yml");
        assertTrue(PresentationMigration.LEGACY_RESOURCES.contains("presentation-legacy-2.0.13.yml"));
        assertTrue(legacy.getMapList("server-menu.items").stream()
                .anyMatch(item -> "orders list".equals(item.get("command"))));
        var changes = PresentationMigration.replacements(live, List.of(legacy), fresh);
        assertTrue(changes.contains("server-menu.items"), changes::toString);
        assertTrue(changes.contains("auto-messages.messages"), changes::toString);
        for (String key : changes) live.set(key, fresh.get(key));
        assertFalse(live.getMapList("server-menu.items").stream()
                .anyMatch(item -> String.valueOf(item.get("command")).startsWith("order")));
        assertFalse(live.getList("auto-messages.messages").stream()
                .anyMatch(raw -> String.valueOf(raw).contains("/orders list")));
        assertEquals(44, live.getMapList("server-menu.items").size());
        assertEquals(36, live.getList("auto-messages.messages").size());
    }
}
