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
        var expansion = new TabPlaceholderIntegration("1.4.285", messages, null, null);
        assertEquals("tab", expansion.getIdentifier());
        assertEquals("cherdakmd", expansion.getAuthor());
        assertEquals("1.4.285", expansion.getVersion());
        assertTrue(expansion.persist(), "/papi reload не должен сбрасывать expansion");
        for (String key : List.of("brand", "separator", "tip", "clock", "balance",
                "boost", "health", "ping", "world", "mspt", "warns")) {
            String value = expansion.onRequest(null, key);
            assertNotNull(value, key + " не зарегистрирован");
            assertFalse(value.contains("%tab_"), key + " оставил сырой токен: " + value);
        }
        assertNotNull(expansion.onRequest(null, ""));
        assertNull(expansion.onRequest(null, "unknown"), "чужой ключ не перехватывается");
        // Голограмма/запрос без игрока: личных полей нет, «после входа» было бы помехой.
        assertEquals("", expansion.onRequest(null, "balance"));
        assertEquals("", expansion.onRequest(null, "boost"));
        assertEquals("", expansion.onRequest(null, "world"));
        assertEquals("", expansion.onRequest(null, "tip"));
        assertNotNull(expansion.onRequest(null, "brand"), "глобальный кадр работает и без игрока");
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
        assertEquals(11, found.size(), "набор токенов в конфиге изменился: " + found);
        assertTrue(found.contains("warns"), "%tab_warns% должен быть в stock-подвале TAB");
        for (String key : found) {
            assertTrue(TabPlaceholderIntegration.isTabKey(key), key + " нет в expansion");
        }
    }
}
