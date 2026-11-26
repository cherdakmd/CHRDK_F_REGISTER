package ru.cherdakmd.chrdkregister;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ServerMenuEntryVisualsTest {
    private static YamlConfiguration resource(String name) throws Exception {
        try (var reader = Files.newBufferedReader(Path.of("src/main/resources", name))) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }

    @Test void allStockEntriesIncludingHintsHaveDistinctSemanticModels() throws Exception {
        var config = resource("config.yml");
        var entries = ServerMenuSettings.from(config).entries();
        var examples = resource("server-menu-visuals.yml");
        var registry = ServerMenuEntryVisuals.from(examples);
        var raw = examples.getMapList("server-menu.items");
        assertEquals(45, entries.size());
        assertEquals(entries.size(), raw.size());
        var models = new HashSet<NamespacedKey>();
        for (int i = 0; i < entries.size(); i++) {
            var model = registry.model(entries.get(i));
            assertNotNull(model, "Stock item " + i);
            assertEquals("chrdk_menu", model.getNamespace());
            assertEquals("entry/" + raw.get(i).get("visual"), model.getKey());
            assertTrue(models.add(model));
        }
        assertEquals("entry/main/help", registry.model(entries.get(2)).getKey());
        assertTrue(entries.get(2).info());
        assertEquals("entry/world/home_hint", registry.model(entries.get(32)).getKey());
        assertTrue(entries.get(32).info());
    }

    @Test void changedOwnerFieldsRemainConfiguredButMovedStockEntryIsRecognized() throws Exception {
        var registry = ServerMenuEntryVisuals.from(resource("server-menu-visuals.yml"));
        var base = ServerMenuSettings.from(resource("config.yml")).entries().get(18);
        var moved = new ServerMenuSettings.Entry(37, base.icon(), base.name(), base.lore(), base.command(),
                base.plugin(), base.permission(), base.category());
        assertEquals(registry.model(base), registry.model(moved));
        var changed = List.of(
                new ServerMenuSettings.Entry(base.slot(), Material.STONE, base.name(), base.lore(), base.command(), base.plugin(), base.permission(), base.category()),
                new ServerMenuSettings.Entry(base.slot(), base.icon(), "owner", base.lore(), base.command(), base.plugin(), base.permission(), base.category()),
                new ServerMenuSettings.Entry(base.slot(), base.icon(), base.name(), "owner", base.command(), base.plugin(), base.permission(), base.category()),
                new ServerMenuSettings.Entry(base.slot(), base.icon(), base.name(), base.lore(), "owner", base.plugin(), base.permission(), base.category()),
                new ServerMenuSettings.Entry(base.slot(), base.icon(), base.name(), base.lore(), base.command(), "owner", base.permission(), base.category()),
                new ServerMenuSettings.Entry(base.slot(), base.icon(), base.name(), base.lore(), base.command(), base.plugin(), "owner", base.category()),
                new ServerMenuSettings.Entry(base.slot(), base.icon(), base.name(), base.lore(), base.command(), base.plugin(), base.permission(), "owner"));
        changed.forEach(e -> assertNull(registry.model(e)));
        assertNull(registry.model(null));
    }

    @Test void invalidOrAmbiguousExamplesFailClosed() throws Exception {
        var examples = resource("server-menu-visuals.yml");
        Map<Object, Object> first = new HashMap<>(examples.getMapList("server-menu.items").get(0));
        Map<Object, Object> second = new HashMap<>(first);
        second.put("visual", "main/another");
        examples.set("server-menu.items", List.of(first, second));
        assertThrows(IllegalArgumentException.class, () -> ServerMenuEntryVisuals.from(examples));
        second.put("visual", first.get("visual"));
        second.put("name", "different");
        examples.set("server-menu.items", List.of(first, second));
        assertThrows(IllegalArgumentException.class, () -> ServerMenuEntryVisuals.from(examples));
        first.put("visual", "../bad");
        examples.set("server-menu.items", List.of(first));
        assertThrows(IllegalArgumentException.class, () -> ServerMenuEntryVisuals.from(examples));
    }
}
