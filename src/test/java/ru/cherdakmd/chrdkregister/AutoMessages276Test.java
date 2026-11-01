package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Редакторская правка не должна менять аудиторию, расписание или собственные тексты. */
class AutoMessages276Test {
    private YamlConfiguration yaml(String resource) {
        var in = getClass().getResourceAsStream("/" + resource);
        assertNotNull(in, resource);
        return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    @Test void everyTextIsUpdatedWithoutChangingAudienceOrSchedule() {
        var old = yaml("presentation-legacy-1.4.275.yml");
        var fresh = yaml("config.yml");
        var before = old.getMapList("auto-messages.messages");
        var after = fresh.getMapList("auto-messages.messages");
        assertEquals(41, after.size());
        for (int i = 0; i < after.size(); i++) {
            var a = new LinkedHashMap<>(before.get(i));
            var b = new LinkedHashMap<>(after.get(i));
            assertNotEquals(a.remove("text"), b.remove("text"), "Текст №" + (i + 1));
            assertEquals(a, b, "Фильтры аудитории №" + (i + 1));
        }
        old.set("auto-messages.enabled", false);
        old.set("auto-messages.interval-seconds", 987);
        old.set("auto-messages.prefix", "Мой префикс");
        var changes = PresentationMigration.replacements(old, List.of(yaml("presentation-legacy-1.4.275.yml")), fresh);
        assertEquals(List.of("auto-messages.messages"), changes);
        assertFalse(old.getBoolean("auto-messages.enabled"));
        assertEquals(987, old.getInt("auto-messages.interval-seconds"));
        assertEquals("Мой префикс", old.getString("auto-messages.prefix"));
        old.set("auto-messages.messages", List.of("Мой текст"));
        assertTrue(PresentationMigration.replacements(old, List.of(yaml("presentation-legacy-1.4.275.yml")), fresh).isEmpty());
    }
}
