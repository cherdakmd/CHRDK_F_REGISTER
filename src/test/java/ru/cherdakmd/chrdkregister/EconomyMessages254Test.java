package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class EconomyMessages254Test {
    @Test void exactOldHintsUpgradeInMemoryWithoutReplacingAnyCustomTranslation() throws Exception {
        for(String module:List.of("","claims/","teleports/","diamond-shop/")) {
            Path folder=Path.of(module+"src/main/resources");
            var oldYaml=YamlConfiguration.loadConfiguration(folder.resolve("economy-messages-legacy-1.4.253.yml").toFile());
            var oldSection=oldYaml.getConfigurationSection("messages");assertNotNull(oldSection);
            Map<String,String> old=new LinkedHashMap<>(),defaults=new LinkedHashMap<>();
            var current=Messages.from(Files.newInputStream(folder.resolve("messages.yml")),null,Logger.getAnonymousLogger());
            for(String key:oldSection.getKeys(true)) if(oldSection.isString(key)) {
                old.put(key,oldSection.getString(key));assertTrue(current.has(key),module+key);
                defaults.put(key,current.template(key));assertNotEquals(old.get(key),defaults.get(key),module+key);
            }
            assertFalse(old.isEmpty());
            var live=new LinkedHashMap<>(old);Messages.migrateStockOverrides(defaults,live,old);assertEquals(defaults,live);
            Messages.migrateStockOverrides(defaults,live,old);assertEquals(defaults,live);
            live.replaceAll((key,value)->"Свой перевод: "+key);
            var custom=Map.copyOf(live);Messages.migrateStockOverrides(defaults,live,old);assertEquals(custom,live);
        }
        String loader=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkregister/Messages.java"));
        assertTrue(loader.contains("\"economy-messages-legacy-1.4.253.yml\", logger)"));
    }
}
