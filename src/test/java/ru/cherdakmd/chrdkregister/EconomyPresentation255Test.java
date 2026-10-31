package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Смысловые регрессии рассылки и безопасное обновление точного стока. */
class EconomyPresentation255Test {
    private YamlConfiguration yaml(String resource) {
        try(var in=getClass().getResourceAsStream("/"+resource)) {
            assertNotNull(in,resource);
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in,StandardCharsets.UTF_8));
        } catch(java.io.IOException e) {throw new java.io.UncheckedIOException(e);}
    }
    @Test void stock254ChangesOnlyTheExactMessageListAndKeepsEveryAudienceGate() {
        var old=yaml("presentation-legacy-1.4.254.yml");var live=yaml("presentation-legacy-1.4.254.yml");var fresh=yaml("config.yml");
        assertTrue(PresentationMigration.LEGACY_RESOURCES.contains("presentation-legacy-1.4.254.yml"));
        live.set("auto-messages.enabled",false);live.set("auto-messages.interval-seconds",777);
        live.set("auto-messages.random-order",false);live.set("auto-messages.minimum-players",9);
        live.set("auto-messages.prefix","Свой префикс");live.set("tablist.pinned-names",List.of("Admin"));
        String before=live.saveToString();
        var changes=PresentationMigration.replacements(live,List.of(old),fresh);
        assertTrue(changes.contains("auto-messages.messages"),changes.toString());assertEquals(before,live.saveToString());
        var previous=old.getMapList("auto-messages.messages");var current=fresh.getMapList("auto-messages.messages");
        assertEquals(43,current.size());assertEquals(previous.size(),current.size());
        for(int i=0;i<previous.size();i++) {
            var a=new LinkedHashMap<>(previous.get(i));var b=new LinkedHashMap<>(current.get(i));a.remove("text");b.remove("text");
            assertEquals(a,b,"Аудитория сообщения №"+(i+1));
        }
        for(String key:changes)live.set(key,fresh.get(key));
        assertTrue(PresentationMigration.replacements(live,List.of(old),fresh).isEmpty());
        assertFalse(live.getBoolean("auto-messages.enabled"));assertEquals(777,live.getInt("auto-messages.interval-seconds"));
        assertFalse(live.getBoolean("auto-messages.random-order"));assertEquals(9,live.getInt("auto-messages.minimum-players"));
        assertEquals("Свой префикс",live.getString("auto-messages.prefix"));assertEquals(List.of("Admin"),live.getStringList("tablist.pinned-names"));
    }
    @Test void evenOneCustomMessagePreventsReplacingTheWholeList() {
        var old=yaml("presentation-legacy-1.4.254.yml");var fresh=yaml("config.yml");
        var live=yaml("presentation-legacy-1.4.254.yml");
        var custom=new java.util.ArrayList<>(live.getMapList("auto-messages.messages"));
        custom.set(0,Map.of("text","Своё сообщение","permission","custom.receive"));live.set("auto-messages.messages",custom);
        assertFalse(PresentationMigration.replacements(live,List.of(old),fresh).contains("auto-messages.messages"));
        assertEquals(custom,live.getMapList("auto-messages.messages"));
        live.set("auto-messages.messages",List.of());
        assertFalse(PresentationMigration.replacements(live,List.of(old),fresh).contains("auto-messages.messages"));
    }
    @Test void economicHintsDescribeActualConsentBankAndCarryWithoutAnnouncingAdminCommands() {
        var rows=yaml("config.yml").getMapList("auto-messages.messages");
        String all=rows.stream().map(m->String.valueOf(m.get("text"))).collect(java.util.stream.Collectors.joining("\n"));
        for(String required:List.of("/bal stats","от 64","/pay ник сумма confirm","30 секунд","повторный confirm",
                "/homes prices","/sethome имя confirm","/claim budget 3","предоплата не вычитается",
                "/shop quote buy","/shop quote sell","неоплаченную долю","/bal</white> арендатора")) assertTrue(all.contains(required),required);
        for(String stale:List.of("казной территории","казна территории арендатора","/shopadmin","/bal health")) assertFalse(all.contains(stale),stale);
        rows.forEach(row->{assertNotNull(AutoMessage.parse(row));assertFalse(RetiredAddons.announcement(String.valueOf(row.get("text"))));});
    }
    @Test void shopHelpUpgradePreservesCustomTranslationsAndUsesUnitsNotLots() throws Exception {
        Path resources=Path.of("diamond-shop/src/main/resources");
        var legacy=YamlConfiguration.loadConfiguration(resources.resolve("economy-messages-legacy-1.4.254.yml").toFile());
        var fresh=YamlConfiguration.loadConfiguration(resources.resolve("messages.yml").toFile());
        Map<String,String> defaults=new LinkedHashMap<>(),old=new LinkedHashMap<>();
        for(String key:List.of("shop.usage","trading.usage","admin.usage")) {
            old.put(key,legacy.getString("messages."+key));defaults.put(key,fresh.getString("messages."+key));
            assertNotNull(old.get(key));assertNotNull(defaults.get(key));assertNotEquals(old.get(key),defaults.get(key));
        }
        assertFalse(defaults.get("trading.usage").contains("партий"));assertTrue(defaults.get("admin.usage").contains("audit"));
        var live=new LinkedHashMap<>(old);Messages.migrateStockOverrides(defaults,live,old);assertEquals(defaults,live);
        Messages.migrateStockOverrides(defaults,live,old);assertEquals(defaults,live);
        live.replaceAll((key,value)->"Свой перевод");var custom=Map.copyOf(live);
        Messages.migrateStockOverrides(defaults,live,old);assertEquals(custom,live);
        assertTrue(Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkregister/Messages.java")).contains("\"economy-messages-legacy-1.4.254.yml\", logger)"));
    }
}
