package ru.cherdakmd.chrdkregister;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class TabDashboard282Test {
    final MiniMessage mini = MiniMessage.miniMessage();
    final PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();
    Messages messages() throws Exception {
        return Messages.from(Files.newInputStream(Path.of("src/main/resources/messages.yml")), null, Logger.getAnonymousLogger());
    }
    @Test void stockHeaderAndFooterReallyMoveAndAllTokensResolveAfterFullRender() throws Exception {
        var messages = messages();
        var config = YamlConfiguration.loadConfiguration(Path.of("src/main/resources/config.yml").toFile());
        Set<Component> headers = new HashSet<>(), footers = new HashSet<>();
        for (long time=0;time<12000;time+=250) {
            var frame=TabDashboard.frame(messages,time,true,52200,Long.MAX_VALUE,20,12,40,"Незер");
            var context=new TabListService.TabContext("Alex",10,100,20,40,"world","активен","30 дней",30,
                    new PresentationContext("Ранг I","В игре","Приятной игры","включена","/menu"),"Alex","",frame);
            Component header=TabListService.joinLines(TabListService.renderLines(messages,config.getStringList("tablist.header"),context));
            Component footer=TabListService.joinLines(TabListService.renderLines(messages,config.getStringList("tablist.footer"),context));
            assertFalse(plain.serialize(header).contains("%"));
            assertFalse(plain.serialize(footer).contains("%tab_"));
            assertTrue(plain.serialize(footer).contains(BankService.format(52200)));
            assertTrue(plain.serialize(header).contains("Незер"));
            assertTrue(plain.serialize(footer).contains("03:00 МСК"));
            headers.add(header);footers.add(footer);
        }
        assertTrue(headers.size()>=24);assertTrue(footers.size()>=12);
        // Даже односимвольная брендовая строка и supplementary glyph сохраняют цвет и текст.
        assertNotEquals(mini.deserialize(TabDashboard.wave("Я",0)),mini.deserialize(TabDashboard.wave("Я",2000)));
        assertEquals("Я🚀<red>&c",plain.serialize(mini.deserialize(TabDashboard.wave("Я🚀<red>&c",123))));
    }
    @Test void shortContextStillResolvesEveryTabToken() throws Exception {
        var messages = messages();
        var config = YamlConfiguration.loadConfiguration(Path.of("src/main/resources/config.yml").toFile());
        var context = new TabListService.TabContext("Alex", 10, 100, 20, 40, "world", "активен", "30 дней", 30,
                new PresentationContext("Ранг I", "В игре", "Приятной игры", "включена", "/menu"));
        Component header = TabListService.joinLines(TabListService.renderLines(messages, config.getStringList("tablist.header"), context));
        Component footer = TabListService.joinLines(TabListService.renderLines(messages, config.getStringList("tablist.footer"), context));
        assertFalse(plain.serialize(header).contains("%tab_"), "шапка оставила сырой кадр");
        assertFalse(plain.serialize(footer).contains("%tab_"), "подвал оставил сырой кадр");
    }
    @Test void privateFieldsAreGatedBeforeLoginAndTipsRotateOnlyAfterLogin() throws Exception {
        var messages=messages();Set<String> tips=new HashSet<>();
        for(long time=0;time<72000;time+=12000) {
            var hidden=TabDashboard.frame(messages,time,false,987654321,Long.MAX_VALUE,20,4,20,"SECRET_WORLD");
            assertEquals("после входа",hidden.balance());assertEquals("после входа",hidden.boost());
            assertEquals("после входа",hidden.world());assertEquals("после входа",hidden.tip());
            var ready=TabDashboard.frame(messages,time,true,987654321,Long.MAX_VALUE,20,4,20,"Незер");
            tips.add(ready.tip());assertTrue(ready.boost().startsWith("+10%"));assertFalse(ready.boost().contains("-"));
        }
        assertEquals(6,tips.size());
        assertEquals("недоступен",TabDashboard.frame(messages,0,true,-1,0,20,4,20,"Мир").balance());
        assertEquals("нет",TabDashboard.frame(messages,0,true,0,0,20,4,20,"Мир").boost());
    }
    @Test void allIndirectDashboardAndProbeKeysExistAndRender() throws Exception {
        var messages=messages();
        List<String> keys=new ArrayList<>();
        for(int i=0;i<6;i++)keys.add("tab.tip-"+i);
        for(String state:List.of("good","medium","high","unknown"))keys.add("tab.health-"+state);
        for(String state:List.of("disabled","inactive","pending","overwritten","frozen","repaired","ok"))keys.add("tab.audit-"+state);
        for(String key:keys) {
            assertTrue(messages.defaultKeys().contains(key),key);assertFalse(messages.plain(key).isBlank(),key);
        }
        assertEquals(48,plain.serialize(mini.deserialize(TabDashboard.wave("А".repeat(1000),0))).length());
    }
    @Test void telemetryHandlesUnavailableAndThresholdValues() {
        assertEquals("tab.health-unknown",TabDashboard.healthKey(Double.NaN,1));
        assertEquals("tab.health-unknown",TabDashboard.healthKey(20,Double.POSITIVE_INFINITY));
        assertEquals("tab.health-unknown",TabDashboard.healthKey(-1,20));
        assertEquals("tab.health-good",TabDashboard.healthKey(19,40));
        assertEquals("tab.health-medium",TabDashboard.healthKey(18,55));
        assertEquals("tab.health-high",TabDashboard.healthKey(20,65));
    }
    @Test void animationCannotBeHiddenByBlankOrHostileNameFormatButStaticFormatStillRespected() {
        for(String format:Arrays.asList(null,""," ","%player%","<gradient:red:blue>%player_tagged%</gradient>"))
            assertEquals("%player_tagged%",TabListService.effectiveNameFormat(format,true));
        assertNull(TabListService.effectiveNameFormat(null,false));
        assertEquals("",TabListService.effectiveNameFormat("",false));
        assertEquals("%player%",TabListService.effectiveNameFormat("%player%",false));
    }
    @Test void frameStateRepairsForeignOverwriteWithoutSendingIdenticalPacketsAndAuditIsHonest() {
        Component a=mini.deserialize("Alex <#67e8f9>[TAG]</#67e8f9>"),b=mini.deserialize("Alex <#c084fc>[TAG]</#c084fc>");
        var initial=TabFrameState.next(null,a,Component.text("Alex"));
        assertTrue(initial.needsDelivery(Component.text("Alex")));
        var same=TabFrameState.next(initial,a,a);assertEquals(initial,same);assertFalse(same.needsDelivery(a));
        var moved=TabFrameState.next(same,b,a);assertTrue(moved.needsDelivery(a));assertEquals(0,moved.repairs());
        assertEquals("ok",TabFrameState.audit(true,true,initial,moved,b));
        assertEquals("overwritten",TabFrameState.audit(true,true,initial,moved,Component.text("Foreign")));
        var repair=TabFrameState.next(moved,a,Component.text("Foreign"));
        assertEquals(1,repair.repairs());assertEquals("repaired",TabFrameState.audit(true,true,initial,repair,a));
        assertEquals("frozen",TabFrameState.audit(true,true,initial,same,a));
        assertEquals("disabled",TabFrameState.audit(false,true,initial,moved,b));
        assertEquals("inactive",TabFrameState.audit(true,false,initial,moved,b));
        assertEquals("pending",TabFrameState.audit(true,true,null,moved,b));
    }
    @Test void tabStockMigrationPreservesCustomLayoutAndPlayerAssignments() {
        var old=YamlConfiguration.loadConfiguration(Path.of("src/main/resources/presentation-legacy-1.4.282-rc3.yml").toFile());
        var live=YamlConfiguration.loadConfiguration(Path.of("src/main/resources/presentation-legacy-1.4.282-rc3.yml").toFile());
        var fresh=YamlConfiguration.loadConfiguration(Path.of("src/main/resources/config.yml").toFile());
        var changes=PresentationMigration.replacements(live,old,fresh);
        assertTrue(changes.containsAll(List.of("tablist.header","tablist.footer")));
        live.set("tablist.header",List.of("Custom %player%"));live.set("tablist.footer",List.of("Custom footer"));
        assertFalse(PresentationMigration.replacements(live,old,fresh).contains("tablist.header"));
        assertFalse(PresentationMigration.replacements(live,old,fresh).contains("tablist.footer"));
        assertFalse(changes.contains("tablist.pinned-names"));
    }
}
