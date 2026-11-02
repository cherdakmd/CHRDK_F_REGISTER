package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class ClanStyleMessages282Test {
    @Test void oldStockRestrictionsMigrateIdempotentlyButCustomMessagesRemain() throws Exception {
        Path resources = Path.of("guilds/src/main/resources");
        var legacy = YamlConfiguration.loadConfiguration(resources.resolve("guild-style-messages-legacy-1.4.282.yml").toFile())
                .getConfigurationSection("guilds");
        assertNotNull(legacy);
        var fresh = Messages.from(Files.newInputStream(resources.resolve("messages.yml")), null, Logger.getAnonymousLogger());
        Map<String,String> old = new LinkedHashMap<>(), defaults = new LinkedHashMap<>();
        for (String key : legacy.getKeys(true)) if (legacy.isString(key)) {
            old.put(key, legacy.getString(key));
            assertTrue(fresh.has(key));
            defaults.put(key, fresh.template(key));
            assertNotEquals(old.get(key), defaults.get(key), key);
        }
        assertEquals(9, old.size());
        var live = new LinkedHashMap<>(old);
        Messages.migrateStockOverrides(defaults, live, old);
        assertEquals(defaults, live);
        Messages.migrateStockOverrides(defaults, live, old);
        assertEquals(defaults, live);
        live.replaceAll((key,value) -> "Свой текст: " + key);
        var custom = Map.copyOf(live);
        Messages.migrateStockOverrides(defaults, live, old);
        assertEquals(custom, live);
        assertTrue(Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkregister/Messages.java"))
                .contains("\"guild-style-messages-legacy-1.4.282.yml\", logger)"));
    }
    @Test void stockCatalogDescriptionsLoseTheOldWaitButKeepCustomTextAndOtherProducts() {
        String old = "Личная лицензия на 30 дней для одного своего клана; перенос не чаще раза в 24 часа, применение с согласия главы. Только внешний вид. /clan style";
        for (String id : List.of("clan-name-style", "clan-emblem", "clan-card-frame")) {
            String updated = ShopCatalog.clanStyleDescription(id, old);
            assertFalse(updated.contains("24 часа"));
            assertTrue(updated.contains("только глава"));
            assertEquals(updated, ShopCatalog.clanStyleDescription(id, updated));
            assertEquals("Свой текст", ShopCatalog.clanStyleDescription(id, "Свой текст"));
        }
        assertEquals(old, ShopCatalog.clanStyleDescription("other", old));
        var yaml = YamlConfiguration.loadConfiguration(Path.of("src/main/resources/config.yml").toFile());
        var catalog = ShopCatalog.from(yaml);
        assertEquals(189, catalog.find("clan-name-style").orElseThrow().priceRubles());
        assertEquals(179, catalog.find("clan-emblem").orElseThrow().priceRubles());
        assertEquals(219, catalog.find("clan-card-frame").orElseThrow().priceRubles());
        assertEquals(449, catalog.find("clan-style-pack").orElseThrow().priceRubles());
        for (String id : List.of("clan-name-style", "clan-emblem", "clan-card-frame", "clan-style-pack")) {
            assertEquals(30, catalog.find(id).orElseThrow().days());
        }
    }
}
