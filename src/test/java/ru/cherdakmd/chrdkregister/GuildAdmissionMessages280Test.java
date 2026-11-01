package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class GuildAdmissionMessages280Test {
    @Test void oldStockTwoCommandHintsUpgradeIdempotentlyWithoutReplacingCustomText() throws Exception {
        Path folder=Path.of("guilds/src/main/resources");
        var yaml=YamlConfiguration.loadConfiguration(folder.resolve("guild-admission-messages-legacy-1.4.279.yml").toFile());
        var section=yaml.getConfigurationSection("guilds");assertNotNull(section);
        var fresh=Messages.from(Files.newInputStream(folder.resolve("messages.yml")),null,Logger.getAnonymousLogger());
        Map<String,String> old=new LinkedHashMap<>(),defaults=new LinkedHashMap<>();
        for(String key:section.getKeys(true))if(section.isString(key)) {
            old.put(key,section.getString(key));assertTrue(fresh.has(key));defaults.put(key,fresh.template(key));
            assertNotEquals(old.get(key),defaults.get(key),key);
        }
        assertEquals(10,old.size());var live=new LinkedHashMap<>(old);
        Messages.migrateStockOverrides(defaults,live,old);assertEquals(defaults,live);
        Messages.migrateStockOverrides(defaults,live,old);assertEquals(defaults,live);
        live.replaceAll((key,value)->"Свой текст: "+key);var custom=Map.copyOf(live);
        Messages.migrateStockOverrides(defaults,live,old);assertEquals(custom,live);
        String source=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkregister/Messages.java"));
        assertTrue(source.contains("\"guild-admission-messages-legacy-1.4.279.yml\", logger)"));
        assertFalse(Files.readString(folder.resolve("messages.yml")).contains("/clan accept confirm"));
    }
}
