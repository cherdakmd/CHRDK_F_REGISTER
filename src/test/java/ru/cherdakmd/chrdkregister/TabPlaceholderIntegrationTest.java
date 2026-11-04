package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Stock-строки TAB не должны оставаться сырым текстом ни при каком потребителе. */
class TabPlaceholderIntegrationTest {

    @Test
    void everyStockTabTokenIsRegisteredAndResolves() throws Exception {
        Messages messages;
        try (InputStream stream = Files.newInputStream(Path.of("src/main/resources/messages.yml"))) {
            messages = Messages.from(stream, null, Logger.getAnonymousLogger());
        }
        var expansion = new TabPlaceholderIntegration("1.4.284-rc.1", messages, null, null);
        assertEquals("tab", expansion.getIdentifier());
        assertEquals("cherdakmd", expansion.getAuthor());
        assertEquals("1.4.284-rc.1", expansion.getVersion());
        assertTrue(expansion.persist(), "/papi reload не должен сбрасывать expansion");
        for (String key : List.of("brand", "separator", "tip", "clock", "balance",
                "boost", "health", "ping", "world", "mspt")) {
            String value = expansion.onRequest(null, key);
            assertNotNull(value, key + " не зарегистрирован");
            assertFalse(value.contains("%tab_"), key + " оставил сырой токен: " + value);
        }
        assertNotNull(expansion.onRequest(null, ""));
        assertNull(expansion.onRequest(null, "unknown"), "чужой ключ не перехватывается");
        assertTrue(TabPlaceholderIntegration.isTabKey("brand"));
        assertFalse(TabPlaceholderIntegration.isTabKey("balance_raw"));
    }

    @Test
    void stockConfigUsesOnlyRegisteredTabTokens() throws Exception {
        var config = org.bukkit.configuration.file.YamlConfiguration
                .loadConfiguration(Path.of("src/main/resources/config.yml").toFile());
        List<String> lines = new java.util.ArrayList<>();
        lines.addAll(config.getStringList("tablist.header"));
        lines.addAll(config.getStringList("tablist.footer"));
        var found = new java.util.TreeSet<String>();
        java.util.regex.Pattern token = java.util.regex.Pattern.compile("%tab_([a-z_]+)%");
        for (String line : lines) {
            var matcher = token.matcher(line);
            while (matcher.find()) found.add(matcher.group(1));
        }
        assertEquals(10, found.size(), "набор токенов в конфиге изменился: " + found);
        for (String key : found) {
            assertTrue(TabPlaceholderIntegration.isTabKey(key), key + " нет в expansion");
        }
    }
}
