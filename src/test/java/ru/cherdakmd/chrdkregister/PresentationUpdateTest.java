package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import static org.junit.jupiter.api.Assertions.*;

class PresentationUpdateTest {
    private YamlConfiguration yaml(String resource) {
        InputStream in=getClass().getResourceAsStream("/"+resource);assertNotNull(in);
        return YamlConfiguration.loadConfiguration(new InputStreamReader(in,StandardCharsets.UTF_8));
    }
    private Messages messages() { return Messages.from(getClass().getResourceAsStream("/messages.yml"),null,Logger.getAnonymousLogger()); }
    @Test void allStockSectionsUpgradeOnceButNoOtherKeys() {
        var old=yaml("presentation-legacy.yml");var fresh=yaml("config.yml");
        var changes=PresentationMigration.replacements(old,yaml("presentation-legacy.yml"),fresh);
        assertEquals(new HashSet<>(PresentationMigration.PATHS),new HashSet<>(changes));
        old.set("tablist.pinned-names",List.of("MyAdmin"));old.set("auto-messages.interval-seconds",999);
        for(String key:changes) old.set(key,fresh.get(key));
        assertTrue(PresentationMigration.replacements(old,yaml("presentation-legacy.yml"),fresh).isEmpty());
        assertEquals(List.of("MyAdmin"),old.getStringList("tablist.pinned-names"));assertEquals(999,old.getInt("auto-messages.interval-seconds"));
    }
    @Test void customListsAndDisabledFlagsSurvive() {
        var live=yaml("presentation-legacy.yml");var legacy=yaml("presentation-legacy.yml");var fresh=yaml("config.yml");
        live.set("server-menu.items",List.of(Map.of("slot",10,"icon","PAPER","name","Custom","command","custom")));
        live.set("auto-messages.messages",List.of("Custom message"));live.set("tablist.footer",List.of("My footer"));
        live.set("server-menu.enabled",false);
        var changes=PresentationMigration.replacements(live,legacy,fresh);
        assertFalse(changes.contains("server-menu.items"));assertFalse(changes.contains("auto-messages.messages"));
        assertFalse(changes.contains("tablist.footer"));assertFalse(live.getBoolean("server-menu.enabled"));
        assertFalse(changes.contains("server-menu.enabled"));
    }
    @Test void missingValuesAndResourceDefaultsAreNotMistakenForOldStock() {
        var empty=new YamlConfiguration();empty.setDefaults(yaml("presentation-legacy.yml"));
        assertTrue(PresentationMigration.replacements(empty,yaml("presentation-legacy.yml"),yaml("config.yml")).isEmpty());
    }
    @Test void stockMenuCoversAllAddonsWithCorrectPermissionsAndNonPurchasingClaims() throws Exception {
        // Material.isItem() in the runtime parser needs the server registry in Purpur 26.3.
        // Verify raw config + layout here; icon creation remains a live-server smoke test.
        var entries=yaml("config.yml").getMapList("server-menu.items").stream().map(map ->
                new ServerMenuSettings.Entry(((Number)map.get("slot")).intValue(), org.bukkit.Material.valueOf(map.get("icon").toString()),
                        map.get("name").toString(), map.get("lore").toString(), map.get("command").toString(),
                        map.get("plugin")==null?"":map.get("plugin").toString(),
                        map.get("permission")==null?"":map.get("permission").toString())).toList();
        var settings=new ServerMenuSettings(true,entries);assertEquals(26,settings.entries().size());
        var layout=ServerMenuService.layout(settings.entries());assertFalse(layout.slots().contains(-1));
        assertEquals(layout.slots().size(),new HashSet<>(layout.slots()).size());
        Set<String> advertised=new HashSet<>();
        for(var entry:settings.entries()) {
            if (!entry.plugin().isBlank()) {
                advertised.add(entry.plugin());
                try(var paths=Files.list(Path.of("."))) {
                    boolean found=false;
                    for(Path folder:paths.toList()) {
                        Path descriptor=folder.resolve("src/main/resources/plugin.yml");
                        if(!Files.exists(descriptor)) continue;
                        var plugin=YamlConfiguration.loadConfiguration(descriptor.toFile());
                        if(!entry.plugin().equals(plugin.getString("name"))) continue;
                        assertTrue(plugin.contains("permissions."+entry.permission()),entry.permission());
                        String command=entry.command().split(" ")[0];
                        assertTrue(plugin.contains("commands."+command),entry.command());found=true;
                    }
                    assertTrue(found,entry.plugin());
                }
            }
            assertNotEquals("claim",entry.command(),"/claim without args buys a chunk");
            assertFalse(entry.command().endsWith("admin"));
        }
        assertEquals(15,advertised.size());
    }
    @Test void menuAvailabilityRequiresBothAddonAndPermission() {
        var entry=new ServerMenuSettings.Entry(10,org.bukkit.Material.PAPER,"a","","auction","CHRDK_Auction","chrdkauction.use");
        assertFalse(entry.available(p->false,p->true));assertFalse(entry.available(p->true,p->false));
        assertTrue(entry.available(p->true,p->true));
    }
    @Test void structuredAndLegacyMessagesSupportTargetingAndExplicitEmptyList() throws Exception {
        var c=ChatConfig.fromBukkitConfig(yaml("config.yml"));
        assertEquals(22,c.autoMessageEntries().size());
        var legacy=new YamlConfiguration();legacy.loadFromString("auto-messages:\n  messages: ['Hello %online%']\n");
        assertEquals("Hello %online%",ChatConfig.fromBukkitConfig(legacy).autoMessageEntries().getFirst().text());
        legacy.set("auto-messages.messages",List.of());assertTrue(ChatConfig.fromBukkitConfig(legacy).autoMessageEntries().isEmpty());
        var trial=AutoMessage.parse(Map.of("text","T","condition","trial"));
        assertFalse(trial.eligible(p->true,p->true,false,false));assertTrue(trial.eligible(p->true,p->true,true,false));
        var addon=AutoMessage.parse(Map.of("text","A","plugin","CHRDK_Auction","permission","chrdkauction.use"));
        assertFalse(addon.eligible(p->false,p->true,false,false));assertFalse(addon.eligible(p->true,p->false,false,false));
        assertNull(AutoMessage.parse(Map.of("text","a","condition","typo")));
    }
    @Test void tabShowsTrialRegistrationLoginAndPaidExtensionAccurately() {
        long now=1800000000000L;var a=new RegistrationStore.PlayerAccount();
        a.passUntil=now+10000;a.trialUntil=a.passUntil;a.lastGrantSource="trial";
        var ctx=PresentationContext.of(a,AccessManager.PlayerStage.AUTHENTICATED,now,false,14,messages(),"/menu • /ah");
        assertEquals("Пробная проходка",ctx.passType());assertEquals("/menu • /ah",ctx.navigation());
        a.lastGrantSource="manual";a.passUntil+=86400000;
        assertEquals("Проходка активна",PresentationContext.of(a,AccessManager.PlayerStage.AUTHENTICATED,now,false,14,messages(),"/menu").passType());
        var reg=PresentationContext.of(null,AccessManager.PlayerStage.NEEDS_REGISTER,now,true,14,messages(),"/ah");
        assertTrue(reg.authHint().contains("14"));assertTrue(reg.navigation().isEmpty());assertFalse(reg.navigation().contains("/ah"));
        var login=PresentationContext.of(a,AccessManager.PlayerStage.NEEDS_LOGIN,now,false,14,messages(),"/ah");
        assertEquals("Войдите в аккаунт: /login",login.authHint());assertTrue(login.navigation().isEmpty());
    }
    @Test void trialHistoryIsNotMistakenForPaidReferralAdmission() throws Exception {
        var a=new RegistrationStore.PlayerAccount();a.trialGrantedAt=100;a.lastGrantSource="trial";
        assertTrue(a.isTrialOnly());a.passUntil=0;assertTrue(a.isTrialOnly());
        a.lastGrantSource="manual";assertFalse(a.isTrialOnly());
        a.lastGrantSource="trial";a.paymentsCount=1;assertFalse(a.isTrialOnly());
        assertTrue(ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi.class.getMethod("isTrialOnly",String.class).isDefault());
    }
    @Test void newTabPlaceholdersAndMiniMessageAutoHintsAreEscaped() {
        var ctx=new TabListService.TabContext("X<red>",3,100,20,50,"world","активна","14 дн.",14,
                new PresentationContext("Пробная проходка","в игре","/menu","включена","/menu • /ah"));
        var line=TabListService.renderName(messages(),"<gold>%player% %pass_type% %auth_status% %security% %navigation%</gold>",ctx);
        String plain=PlainTextComponentSerializer.plainText().serialize(line);
        assertTrue(plain.contains("X<red>"));assertTrue(plain.contains("Пробная проходка"));assertFalse(plain.contains("%navigation%"));
        var cfg=ChatConfig.fromBukkitConfig(yaml("config.yml"));
        for(var entry:cfg.autoMessageEntries()) {
            String text=PlainTextComponentSerializer.plainText().serialize(messages().renderTemplate(cfg.autoMessagesPrefix()+entry.text(),
                    "online",3,"player","X<red>","pass_left","14 дн.","trial_days",14));
            assertFalse(text.contains("<gray>"));assertFalse(text.contains("%pass_left%"));
        }
    }
}
