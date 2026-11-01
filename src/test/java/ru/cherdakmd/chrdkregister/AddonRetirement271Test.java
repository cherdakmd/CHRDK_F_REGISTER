package ru.cherdakmd.chrdkregister;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.api.BankCharge;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

/** Снятие систем не удаляет общие контракты и исторические банковские квитанции. */
class AddonRetirement271Test {
    @TempDir Path dir;
    YamlConfiguration yaml(String resource) throws Exception {
        try(var stream=getClass().getResourceAsStream("/"+resource)) {
            assertNotNull(stream,resource);
            return YamlConfiguration.loadConfiguration(new InputStreamReader(stream,StandardCharsets.UTF_8));
        }
    }
    @Test void noRetiredModulesPagesOrNewChargePermissionRemain() throws Exception {
        String settings=Files.readString(Path.of("settings.gradle"));
        for(String module:List.of("events","lottery","party","referrals")) {
            assertFalse(Files.exists(Path.of(module)));
            assertFalse(Files.exists(Path.of("wiki",module+".md")));
            assertFalse(settings.contains("include '"+module+"'"));
        }
        for(String plugin:List.of("CHRDK_Events","CHRDK_Lottery","CHRDK_Party","CHRDK_Referrals"))
            assertFalse(PluginUpdateService.MODULES.containsKey(plugin));
        assertEquals(22,PluginUpdateService.MODULES.size());assertNull(BankCharge.permission("lottery"));
    }
    @Test void everyOldCommandAliasIsFilteredIncludingNamespacesAndExtraSlashes() {
        for(String alias:List.of("lottery","lot","loto","lotteryadmin","lotadmin","party","pt","group","gruppa",
                "pc","pchat","partychat","partyadmin","ref","referral","invite","refadmin","referraladmin")) {
            for(String prefix:List.of("","/","//","/CHRDK_OLD:")) {
                String command=prefix+alias+" info";
                assertTrue(RetiredAddons.command(command),command);
                var entry=new ServerMenuSettings.Entry(-1,Material.PAPER,"Custom","",command);
                assertFalse(entry.available(plugin->true,permission->true),command);
            }
        }
        for(String command:List.of("clan invite Steve","guild info","msg Steve hi","minecraft:time query daytime","party_other"))
            assertFalse(RetiredAddons.command(command),command);
    }
    @Test void oldPluginAndPermissionGatesDoNotResurrectSocialFeatures() {
        for(String plugin:List.of("CHRDK_Lottery","CHRDK_Party","CHRDK_Referrals")) {
            assertTrue(RetiredAddons.plugin(plugin));
            var entry=new ServerMenuSettings.Entry(-1,Material.PAPER,"Custom","","custom",plugin,"");
            assertFalse(entry.available(p->true,permission->true));
            assertFalse(AutoMessage.parse(Map.of("text","Custom","plugin",plugin)).eligible(p->true,permission->true,false,false));
        }
        for(String permission:List.of("chrdklottery.use","chrdklottery.admin","chrdkparty.use","chrdkparty.admin","chrdkref.use","chrdkref.admin"))
            assertTrue(RetiredAddons.permission(permission));
        var permissionOnly=new ServerMenuSettings.Entry(-1,Material.PAPER,"Custom","","custom","","chrdkref.use");
        assertFalse(permissionOnly.available(p->true,permission->true));
        assertFalse(AutoMessage.parse(Map.of("text","Custom","permission","chrdkparty.use"))
                .eligible(p->true,permission->true,false,false));
        assertFalse(RetiredAddons.permission("chrdkguilds.use"));assertFalse(RetiredAddons.plugin("CHRDK_VK"));
    }
    @Test void savedAnnouncementsAreSuppressedWithoutSuppressingOrdinaryGameplay() {
        for(String text:List.of("/lottery info","/pc текст","/ref use Steve","Реферальный бонус","Еженедельная лотерея","Чат отряда"))
            assertFalse(AutoMessage.parse(text).eligible(p->true,permission->true,false,false),text);
        for(String text:List.of("/clan invite Steve","Лотерея дропа — случайные свойства находки","Отряд гильдии идёт на охоту",
                "https://jd.papermc.io/paper/org/bukkit/event/player/PlayerJoinEvent.html","Ванильные события и общие цели"))
            assertTrue(AutoMessage.parse(text).eligible(p->true,permission->true,false,false),text);
    }
    @Test void retiredProfilesNamesAndWikiSlugsAreRefusedButGuildsStay() {
        var profiles=new ProfileRegistry(Logger.getAnonymousLogger());var tags=new NameTags(Logger.getAnonymousLogger());
        for(String id:List.of("lottery","party","referrals")) {
            assertFalse(profiles.register(id,context->null));tags.register(id,(name,base)->"removed");
        }
        assertEquals(0,profiles.size());assertEquals("Steve",tags.decorate("Steve","Steve"));
        assertTrue(profiles.register("guilds",context->null));
        var wiki=WikiSettings.of(true,WikiSettings.DEFAULT_URL,List.of("party","guilds","referrals","lottery","profile"));
        assertEquals(List.of("guilds","profile"),wiki.pages());
    }
    @Test void stock270MigratesOnceWithoutOverwritingSchedulesPricesOrUserEdits() throws Exception {
        var old=yaml("presentation-legacy-1.4.270.yml");var fresh=yaml("config.yml");
        assertTrue(PresentationMigration.LEGACY_RESOURCES.contains("presentation-legacy-1.4.270.yml"));
        old.set("auto-messages.interval-seconds",777);old.set("tablist.pinned-names",List.of("Admin"));
        var changes=PresentationMigration.replacements(old,List.of(yaml("presentation-legacy-1.4.270.yml")),fresh);
        assertTrue(changes.containsAll(List.of("server-menu.items","server-menu.categories","auto-messages.messages")));
        assertTrue(changes.contains("shop.products"), "278 добавляет три косметических SKU");
        var retained = fresh.getMapList("shop.products").stream()
                .filter(product -> !PremiumPresentationMigration.PRODUCTS.contains(product.get("id")))
                .map(product -> { var terms = new LinkedHashMap<>(product); terms.remove("description"); return terms; }).toList();
        var previous = old.getMapList("shop.products").stream()
                .map(product -> { var terms = new LinkedHashMap<>(product); terms.remove("description"); return terms; }).toList();
        assertEquals(previous, retained, "условия прежних товаров не меняются");
        assertEquals(48,fresh.getMapList("server-menu.items").size());
        assertEquals(41,fresh.getMapList("auto-messages.messages").size());
        for(String key:changes)old.set(key,fresh.get(key));
        assertTrue(PresentationMigration.replacements(old,List.of(yaml("presentation-legacy-1.4.270.yml")),fresh).isEmpty());
        assertEquals(777,old.getInt("auto-messages.interval-seconds"));assertEquals(List.of("Admin"),old.getStringList("tablist.pinned-names"));
        old.set("server-menu.items",List.of(Map.of("command","custom","name","Custom")));
        old.set("auto-messages.messages",List.of("Свой текст"));
        changes=PresentationMigration.replacements(old,List.of(yaml("presentation-legacy-1.4.270.yml")),fresh);
        assertFalse(changes.contains("server-menu.items"));assertFalse(changes.contains("auto-messages.messages"));
    }
    @Test void exactOldMessageOverrideIsReplacedButCustomTextIsNot() throws Exception {
        Map<String,String> fresh=new LinkedHashMap<>(),legacy=new LinkedHashMap<>();
        // Production loader performs exact key-level replacement, not deleting the owner's messages file.
        var current=Messages.from(getClass().getResourceAsStream("/messages.yml"),null,Logger.getAnonymousLogger());
        var previous=Messages.from(getClass().getResourceAsStream("/removal-messages-legacy-1.4.270.yml"),null,Logger.getAnonymousLogger());
        for(String key:current.defaultKeys())fresh.put(key,current.template(key));
        for(String key:previous.defaultKeys())legacy.put(key,previous.template(key));
        var overrides=new LinkedHashMap<>(legacy);
        Messages.migrateStockOverrides(fresh,overrides,legacy);
        assertFalse(overrides.values().stream().anyMatch(v->v.contains("/lottery")));
        // Новые ключи (например, ВК-2FA) берутся из defaults, а не обязаны попасть в overrides.
        var migratedExisting = new LinkedHashMap<>(fresh);
        migratedExisting.keySet().retainAll(legacy.keySet());
        assertEquals(migratedExisting,overrides);
        assertTrue(fresh.containsKey("vk-factor.prompt"));
        overrides.put("guide.pages","Собственный путеводитель администратора");
        Messages.migrateStockOverrides(fresh,overrides,legacy);
        assertEquals("Собственный путеводитель администратора",overrides.get("guide.pages"));
    }
    @Test void legacyLotteryChargeAndEntitlementReceiptsRemainReadableWithoutNewPermission() {
        var bank=new BankStore(dir,Logger.getAnonymousLogger());bank.load();UUID owner=UUID.randomUUID(),lineage=UUID.randomUUID();
        assertTrue(bank.registerIncomeSource("lottery",lineage));bank.deposit(owner,"Steve",100);
        var charge=new BankCharge("lottery",lineage,1,UUID.randomUUID(),owner,20,20,0,0,"a".repeat(64),"b".repeat(64));
        assertTrue(charge.valid());assertTrue(bank.settleCharge(charge));assertTrue(bank.creditEntitlement("lottery",lineage,"prize",owner,7));bank.flush();
        var restored=new BankStore(dir,Logger.getAnonymousLogger());restored.load();
        assertEquals(BankCharge.Status.COMMITTED,restored.bankChargeStatus(charge));assertEquals(87,restored.balance(owner));
        assertTrue(restored.creditEntitlement("lottery",lineage,"prize",owner,7));assertEquals(87,restored.balance(owner));
        assertNull(BankCharge.permission("lottery"));
    }
    @Test void sharedCoreBukkitAndBankContractsStillExist() {
        assertEquals(34,ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi.API_VERSION);
        assertTrue(org.bukkit.event.Event.class.isAssignableFrom(ru.cherdakmd.chrdkregister.api.events.PassGrantedEvent.class));
        assertNotNull(ru.cherdakmd.chrdkregister.api.ChargeRecovery.class);
        assertNotNull(ru.cherdakmd.chrdkregister.api.MilestoneAwards.class);
        assertFalse(RetiredAddons.source("guilds"));assertFalse(RetiredAddons.source("common-goals"));
    }
}
