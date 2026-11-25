package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class FishingPresentationMigrationTest {
    private YamlConfiguration yaml(String name) throws Exception {
        try (var reader = Files.newBufferedReader(Path.of("src/main/resources", name))) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }
    @Test void defaultSectionsMigrateButOwnerChangesArePreserved() throws Exception {
        var old = yaml("presentation-legacy-fishing.yml"); var live = yaml("presentation-legacy-fishing.yml");
        var fresh = yaml("config.yml");
        assertTrue(PresentationMigration.LEGACY_RESOURCES.contains("presentation-legacy-fishing.yml"));
        var replacements = PresentationMigration.replacements(live, List.of(old), fresh);
        assertTrue(replacements.contains("server-menu.items"));
        assertTrue(replacements.contains("auto-messages.messages"));
        live.set("server-menu.items", List.of(Map.of("name", "custom")));
        live.set("auto-messages.messages", List.of(Map.of("text", "custom")));
        replacements = PresentationMigration.replacements(live, List.of(old), fresh);
        assertFalse(replacements.contains("server-menu.items"));
        assertFalse(replacements.contains("auto-messages.messages"));
    }
    @Test void fishingNavigationAndMessagesAreGuarded() throws Exception {
        var fresh = yaml("config.yml");
        var item = fresh.getMapList("server-menu.items").stream().filter(m -> "fish".equals(m.get("command"))).findFirst().orElseThrow();
        assertEquals("CHRDK_Fishing", item.get("plugin")); assertEquals("chrdkfishing.use", item.get("permission"));
        var msg = fresh.getMapList("auto-messages.messages").stream().filter(m -> "CHRDK_Fishing".equals(m.get("plugin"))).findFirst().orElseThrow();
        assertEquals("chrdkfishing.use", msg.get("permission"));
    }
}
