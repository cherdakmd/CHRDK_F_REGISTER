package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Счётчик варнов в TAB виден всем и не остаётся сырым %tab_ токеном. */
class ModerationTabTest {

    @Test
    void shortContextCarriesWarnCounterForMessagesFill() {
        var context = new TabListService.TabContext("Alex", 1, 20, 20.0, 5, "world", "", "", 0);
        Object[] values = context.placeholders();
        String found = null;
        for (int i = 0; i + 1 < values.length; i++) {
            if ("tab_warns".equals(values[i])) found = String.valueOf(values[i + 1]);
        }
        assertEquals("0/5", found, "даже без ModerationService подвал не показывает сырой токен");
    }

    @Test
    void warnsAreMaskedForPapiAndRegisteredInExpansion() {
        assertTrue(TabPlaceholderIntegration.isTabKey("warns"));
        assertFalse(TabPlaceholderIntegration.isTabKey("warns_foreign"));
        String masked = TabListService.maskTabTokens("%tab_warns%");
        assertFalse(masked.contains("%"));
        assertEquals("%tab_warns%", TabListService.unmaskTabTokens(masked));
    }

    @Test
    void stockFooterShowsWarnsToEveryone() throws Exception {
        var config = YamlConfiguration.loadConfiguration(Path.of("src/main/resources/config.yml").toFile());
        String footer = String.join("\n", config.getStringList("tablist.footer"));
        assertTrue(footer.contains("%tab_warns%"), "в подвале TAB нет счётчика варнов");
        assertTrue(config.getBoolean("moderation.enabled"));
        assertEquals(5, config.getInt("moderation.warn-limit"));
        assertTrue(config.getBoolean("moderation.warns-in-tab"));
    }
}
