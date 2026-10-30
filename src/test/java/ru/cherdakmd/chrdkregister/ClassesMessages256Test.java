package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class ClassesMessages256Test {
    @Test void exactLegacyDescriptionsUpgradeWithoutOverwritingCustomTranslations() throws Exception {
        Path folder=Path.of("classes/src/main/resources");
        var oldYaml=YamlConfiguration.loadConfiguration(folder.resolve("classes-messages-legacy-1.4.255.yml").toFile());
        var oldSection=oldYaml.getConfigurationSection("messages");assertNotNull(oldSection);
        var fresh=Messages.from(Files.newInputStream(folder.resolve("messages.yml")),null,Logger.getAnonymousLogger());
        Map<String,String> old=new LinkedHashMap<>(),defaults=new LinkedHashMap<>();
        for(String key:oldSection.getKeys(true))if(oldSection.isString(key)) {
            old.put(key,oldSection.getString(key));assertTrue(fresh.has(key));defaults.put(key,fresh.template(key));assertNotEquals(old.get(key),defaults.get(key));
        }
        assertEquals(5,old.size());var live=new LinkedHashMap<>(old);Messages.migrateStockOverrides(defaults,live,old);assertEquals(defaults,live);
        Messages.migrateStockOverrides(defaults,live,old);assertEquals(defaults,live);
        live.replaceAll((key,value)->"Свой перевод: "+key);var custom=Map.copyOf(live);Messages.migrateStockOverrides(defaults,live,old);assertEquals(custom,live);
        assertTrue(Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkregister/Messages.java")).contains("\"classes-messages-legacy-1.4.255.yml\", logger)"));
    }
}
