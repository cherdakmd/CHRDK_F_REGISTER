package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class ScribeNavigationTest {
    private YamlConfiguration yaml(String name) throws Exception {
        try(var in=Files.newBufferedReader(Path.of("src/main/resources",name))) {return YamlConfiguration.loadConfiguration(in);}
    }
    private Messages messages(String name)throws Exception {
        return Messages.from(Files.newInputStream(Path.of("src/main/resources",name)),null,Logger.getAnonymousLogger());
    }
    @Test void menuEntryUsesNormalPlayerCommandAndAddonGuard() throws Exception {
        var entries=yaml("config.yml").getMapList("server-menu.items");
        var found=entries.stream().filter(e->"enchants scribe".equals(e.get("command"))).toList();
        assertEquals(1,found.size());var entry=found.getFirst();
        assertEquals("gear",entry.get("category"));assertEquals("CHRDK_Enchants",entry.get("plugin"));
        assertEquals("chrdkenchants.use",entry.get("permission"));
        String menu=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkregister/ServerMenuService.java"));
        assertTrue(menu.indexOf("player.closeInventory();\n                        player.performCommand(button.command());")>=0);
    }
    @Test void stockMenuUpgradesButOwnerMenuIsNotOverwritten() throws Exception {
        var old=yaml("presentation-legacy-scribe.yml");var fresh=yaml("config.yml");
        assertTrue(PresentationMigration.LEGACY_RESOURCES.contains("presentation-legacy-scribe.yml"));
        assertTrue(PresentationMigration.replacements(old,List.of(old),fresh).contains("server-menu.items"));
        var custom=yaml("presentation-legacy-scribe.yml");
        custom.set("server-menu.items",List.of(Map.of("name","custom","command","custom")));
        assertFalse(PresentationMigration.replacements(custom,List.of(old),fresh).contains("server-menu.items"));
    }
    @Test void bookHasClearStepsAndOnlyStockGuideIsMigrated() throws Exception {
        var old=messages("scribe-messages-legacy.yml");var fresh=messages("messages.yml");
        String before=old.template("guide.pages"),after=fresh.template("guide.pages");
        assertTrue(after.contains("/enchants scribe"));assertTrue(after.contains("/menu"));
        assertTrue(after.contains("8 лазурита"));assertTrue(after.contains("в руку"));
        var defaults=Map.of("guide.pages",after);var legacy=Map.of("guide.pages",before);
        var live=new HashMap<>(legacy);Messages.migrateStockOverrides(defaults,live,legacy);
        assertEquals(after,live.get("guide.pages"));
        live.put("guide.pages","Owner custom guide");Messages.migrateStockOverrides(defaults,live,legacy);
        assertEquals("Owner custom guide",live.get("guide.pages"));
        String loader=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkregister/Messages.java"));
        assertTrue(loader.contains("scribe-messages-legacy.yml"));
    }
}
