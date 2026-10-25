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
        var legacy=yaml("presentation-legacy-pre-1.4.138.yml");var fresh=yaml("config.yml");
        var changes=PresentationMigration.replacements(legacy,List.of(legacy),fresh);
        // Пункты меню тянут за собой новую структуру: категории, заголовки и «Назад».
        assertTrue(changes.contains("server-menu.items"),changes.toString());
        assertTrue(changes.contains("server-menu.categories"),changes.toString());
        assertTrue(changes.contains("auto-messages.messages"),changes.toString());
        for (String companion : PresentationMigration.MENU_COMPANIONS) {
            assertTrue(changes.contains(companion),companion);
        }
        var allowed=new HashSet<>(PresentationMigration.PATHS);
        allowed.addAll(PresentationMigration.MENU_COMPANIONS);
        assertTrue(allowed.containsAll(changes),"лишние ключи: "+changes);
        legacy.set("tablist.pinned-names",List.of("MyAdmin"));legacy.set("auto-messages.interval-seconds",999);
        for(String key:changes) legacy.set(key,fresh.get(key));
        assertTrue(PresentationMigration.replacements(legacy,List.of(legacy),fresh).isEmpty());
        assertEquals(List.of("MyAdmin"),legacy.getStringList("tablist.pinned-names"));assertEquals(999,legacy.getInt("auto-messages.interval-seconds"));
    }

    /**
     * 1.4.171 добавляет в стоковое меню одну кнопку — «Вики сервера».
     * Сервер, который не правил меню в 1.4.170, должен получить её и только её:
     * остальные пункты и настройки разделов остаются прежними.
     */
    @Test void stockMenuFrom170GainsOnlyTheWikiButton() {
        var live=yaml("presentation-legacy-1.4.170.yml");var fresh=yaml("config.yml");
        var changes=PresentationMigration.replacements(live,List.of(live),fresh);
        assertTrue(changes.contains("server-menu.items"),changes.toString());
        var oldItems=live.getMapList("server-menu.items");
        var newItems=fresh.getMapList("server-menu.items");
        assertEquals(oldItems.size()+1,newItems.size(),"в стоке ровно один новый пункт");
        assertTrue(newItems.containsAll(oldItems),"старые пункты не переписаны");
        var added=newItems.stream().filter(item -> !oldItems.contains(item)).toList();
        assertEquals(1,added.size(),added.toString());
        assertEquals("wiki",added.get(0).get("command"),"новый пункт ведёт в /wiki");
        assertEquals(20,((Number)added.get(0).get("slot")).intValue(),"кнопка на главной, слот 20");
        // Настройки разделов и кнопок не менялись — миграция их не трогает.
        for (String companion : PresentationMigration.MENU_COMPANIONS) {
            if (companion.equals("server-menu.items")) continue;
            assertEquals(fresh.get(companion),live.get(companion),companion);
        }
    }

    @Test void customListsAndDisabledFlagsSurvive() {
        var live=yaml("presentation-legacy-pre-1.4.138.yml");var legacy=yaml("presentation-legacy-pre-1.4.138.yml");var fresh=yaml("config.yml");
        live.set("server-menu.items",List.of(Map.of("slot",10,"icon","PAPER","name","Custom","command","custom")));
        live.set("auto-messages.messages",List.of("Custom message"));live.set("tablist.footer",List.of("My footer"));
        live.set("server-menu.enabled",false);
        var changes=PresentationMigration.replacements(live,List.of(legacy),fresh);
        assertFalse(changes.contains("server-menu.items"));assertFalse(changes.contains("auto-messages.messages"));
        assertFalse(changes.contains("tablist.footer"));assertFalse(live.getBoolean("server-menu.enabled"));
        assertFalse(changes.contains("server-menu.enabled"));
        // Чужой пункт меню не должен притащить за собой стоковые категории.
        assertFalse(changes.contains("server-menu.categories"));
    }

    @Test void missingValuesAndResourceDefaultsAreNotMistakenForOldStock() {
        var empty=new YamlConfiguration();empty.setDefaults(yaml("presentation-legacy.yml"));
        assertTrue(PresentationMigration.replacements(empty,yaml("presentation-legacy.yml"),yaml("config.yml")).isEmpty());
    }

    /** Описание аддона из plugin.yml (ядро лежит в корне и сюда не попадает). */
    private List<YamlConfiguration> addonDescriptors() throws Exception {
        List<YamlConfiguration> out=new ArrayList<>();
        try(var paths=Files.list(Path.of("."))) {
            for(Path folder:paths.toList()) {
                Path descriptor=folder.resolve("src/main/resources/plugin.yml");
                if(!Files.exists(descriptor)) continue;
                out.add(YamlConfiguration.loadConfiguration(descriptor.toFile()));
            }
        }
        return out;
    }

    private ServerMenuSettings.Entry menuEntry(Map<?,?> map) {
        return new ServerMenuSettings.Entry(
                map.containsKey("slot")?((Number)map.get("slot")).intValue():-1,
                org.bukkit.Material.valueOf(String.valueOf(map.get("icon"))),
                String.valueOf(map.get("name")),
                map.get("lore")==null?"":String.valueOf(map.get("lore")),
                map.get("command")==null?"":String.valueOf(map.get("command")),
                map.get("plugin")==null?"":String.valueOf(map.get("plugin")),
                map.get("permission")==null?"":String.valueOf(map.get("permission")),
                map.get("category")==null?"":String.valueOf(map.get("category")));
    }

    @Test void stockMenuCoversEveryAddonWithWorkingCommands() throws Exception {
        // Material.isItem() в рантайм-парсере требует реестр сервера, поэтому здесь
        // проверяются сырой конфиг и раскладка; создание иконок — смоук-тест на сервере.
        var config=yaml("config.yml");
        assertTrue(config.getBoolean("server-menu.enabled"),"меню включено в стоке");
        var categories=config.getMapList("server-menu.categories").stream()
                .map(map -> String.valueOf(map.get("id"))).toList();
        assertEquals(7,categories.size(),"разделы меню: "+categories);
        assertEquals(7,new HashSet<>(categories).size(),"ид разделов уникальны");
        var entries=config.getMapList("server-menu.items").stream().map(this::menuEntry).toList();
        assertEquals(51,entries.size(),"пункты меню 1.4.171: 50 из 1.4.170 + кнопка вики");
        for (var entry : entries) {
            if (!entry.category().isBlank()) assertTrue(categories.contains(entry.category()),entry.category());
        }
        for (String category : categories) {
            assertTrue(entries.stream().anyMatch(entry -> entry.category().equals(category)),
                    "раздел без пунктов не нужен на главной: "+category);
        }
        // Страница раздела = пункты + «Назад» + «Закрыть»; всё должно влезть без потерь.
        int backSlot=config.getInt("server-menu.back-slot",40);
        int closeSlot=config.getInt("server-menu.close-slot",44);
        assertNotEquals(backSlot,closeSlot,"«Назад» и «Закрыть» не делят слот");
        for (String category : categories) {
            List<Integer> requested=new ArrayList<>();
            for (var entry : entries) if (entry.category().equals(category)) requested.add(entry.slot());
            requested.add(backSlot);
            if (closeSlot>=0) requested.add(closeSlot);
            var layout=ServerMenuService.layoutSlots(requested);
            assertFalse(layout.slots().contains(-1),category+": "+layout.slots());
            assertEquals(layout.slots().size(),new HashSet<>(layout.slots()).size(),category+": "+layout.slots());
            assertTrue(layout.size()<=54,category+": "+layout.size());
        }
        // Главная страница: разделы и пункты без категории.
        var main=ServerMenuService.layoutSlots(entries.stream()
                .filter(e -> e.category().isBlank()).map(ServerMenuSettings.Entry::slot).toList());
        assertFalse(main.slots().contains(-1),main.slots().toString());
        assertEquals(main.slots().size(),new HashSet<>(main.slots()).size());

        // Каждый пункт ведёт в существующую команду и принадлежит реальному аддону.
        var core=yaml("plugin.yml");
        var addons=addonDescriptors();
        Set<String> advertised=new HashSet<>();
        Set<String> menuAddons=new HashSet<>();
        for (var descriptor : addons) menuAddons.add(descriptor.getString("name"));
        for (var entry : entries) {
            assertNotEquals("claim",entry.command(),"/claim без аргументов покупает чанк");
            assertFalse(entry.command().endsWith("admin"),entry.command());
            if (entry.plugin().isBlank()) {
                if (!entry.command().isBlank()) {
                    String command=entry.command().split(" ")[0];
                    assertTrue(core.contains("commands."+command),"ядро: "+entry.command());
                }
                continue;
            }
            advertised.add(entry.plugin());
            assertTrue(menuAddons.contains(entry.plugin()),"нет аддона "+entry.plugin());
            assertFalse(entry.permission().isBlank(),"у пункта аддона есть право: "+entry.name());
            var descriptor=addons.stream()
                    .filter(plugin -> entry.plugin().equals(plugin.getString("name")))
                    .findFirst().orElseThrow();
            assertTrue(descriptor.contains("permissions."+entry.permission()),entry.permission());
            if (!entry.command().isBlank()) {
                String command=entry.command().split(" ")[0];
                assertTrue(descriptor.contains("commands."+command),entry.command());
            }
        }
        // Меню — карта сервера: каждый установленный аддон должен быть в нём представлен.
        assertEquals(menuAddons,advertised,"меню покрывает все аддоны, включая новые волны");
    }

    @Test void stock169MenuBecomesNewStructureOnce() {
        // Сервер, обновившийся с 1.4.169, получает новые разделы и «Закрыть».
        var snapshot=yaml("presentation-legacy-1.4.169.yml");
        var live=yaml("presentation-legacy-1.4.169.yml");
        var fresh=yaml("config.yml");
        var changes=PresentationMigration.replacements(live,List.of(snapshot),fresh);
        assertTrue(changes.contains("server-menu.items"),changes.toString());
        assertTrue(changes.contains("server-menu.categories"),changes.toString());
        for (String companion : PresentationMigration.MENU_COMPANIONS) {
            assertTrue(changes.contains(companion),companion);
        }
        // Снимок 1.4.169 не содержит auto-messages/tablist — их миграция не трогает.
        assertFalse(changes.contains("auto-messages.messages"),changes.toString());
        assertFalse(changes.contains("tablist.header"),changes.toString());
        for(String key:changes) live.set(key,fresh.get(key));
        assertTrue(PresentationMigration.replacements(live,List.of(snapshot),fresh).isEmpty());
        // Правленые пункты не должны подменяться стоковыми.
        var custom=yaml("presentation-legacy-1.4.169.yml");
        custom.set("server-menu.items",List.of(Map.of("slot",10,"icon","PAPER","name","Custom","command","custom")));
        assertFalse(PresentationMigration.replacements(custom,List.of(snapshot),fresh)
                .contains("server-menu.items"),"пользовательские пункты сохраняются");
    }

    @Test void menuAvailabilityRequiresBothAddonAndPermission() {
        var entry=new ServerMenuSettings.Entry(10,org.bukkit.Material.PAPER,"a","","auction","CHRDK_Auction","chrdkauction.use");
        assertFalse(entry.available(p->false,p->true));assertFalse(entry.available(p->true,p->false));
        assertTrue(entry.available(p->true,p->true));
    }
    @Test void structuredAndLegacyMessagesSupportTargetingAndExplicitEmptyList() throws Exception {
        var c=ChatConfig.fromBukkitConfig(yaml("config.yml"));
        assertEquals(30,c.autoMessageEntries().size());
        var legacy=new YamlConfiguration();legacy.loadFromString("auto-messages:\n  messages: ['Hello %online%']\n");
        assertEquals("Hello %online%",ChatConfig.fromBukkitConfig(legacy).autoMessageEntries().getFirst().text());
        legacy.set("auto-messages.messages",List.of());assertTrue(ChatConfig.fromBukkitConfig(legacy).autoMessageEntries().isEmpty());
        var trial=AutoMessage.parse(Map.of("text","T","condition","trial"));
        assertFalse(trial.eligible(p->true,p->true,false,false));assertTrue(trial.eligible(p->true,p->true,true,false));
        var addon=AutoMessage.parse(Map.of("text","A","plugin","CHRDK_Auction","permission","chrdkauction.use"));
        assertFalse(addon.eligible(p->false,p->true,false,false));assertFalse(addon.eligible(p->true,p->false,false,false));
        assertNull(AutoMessage.parse(Map.of("text","a","condition","typo")));
        var noPass=AutoMessage.parse(Map.of("text","N","condition","no-pass"));
        assertFalse(noPass.eligible(p->true,p->true,false,false,true,false,0));
        assertTrue(noPass.eligible(p->true,p->true,false,false,false,false,0));
        var paid=AutoMessage.parse(Map.of("text","P","condition","paid"));
        assertTrue(paid.eligible(p->true,p->true,false,false,true,true,0));
        assertFalse(paid.eligible(p->true,p->true,true,false,true,false,0));
        var crowded=AutoMessage.parse(Map.of("text","C","min-online",5));
        assertFalse(crowded.eligible(p->true,p->true,false,false,true,false,4));
        assertTrue(crowded.eligible(p->true,p->true,false,false,true,false,5));
    }
    @Test void tabShowsTrialRegistrationLoginAndPaidExtensionAccurately() {
        long now=1800000000000L;var a=new RegistrationStore.PlayerAccount();
        a.passUntil=now+10000;a.trialUntil=a.passUntil;a.lastGrantSource="trial";
        var ctx=PresentationContext.of(a,AccessManager.PlayerStage.AUTHENTICATED,now,false,14,messages(),"/menu • /ah");
        assertEquals("Приветственный ранг",ctx.passType());assertEquals("/menu • /ah",ctx.navigation());
        a.lastGrantSource="manual";a.passUntil+=86400000;
        assertEquals("Ранг активен",PresentationContext.of(a,AccessManager.PlayerStage.AUTHENTICATED,now,false,14,messages(),"/menu").passType());
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
                new PresentationContext("Приветственный ранг","в игре","/menu","включена","/menu • /ah"));
        var line=TabListService.renderName(messages(),"<gold>%player% %pass_type% %auth_status% %security% %navigation%</gold>",ctx);
        String plain=PlainTextComponentSerializer.plainText().serialize(line);
        assertTrue(plain.contains("X<red>"));assertTrue(plain.contains("Приветственный ранг"));assertFalse(plain.contains("%navigation%"));
        var cfg=ChatConfig.fromBukkitConfig(yaml("config.yml"));
        for(var entry:cfg.autoMessageEntries()) {
            String text=PlainTextComponentSerializer.plainText().serialize(messages().renderTemplate(cfg.autoMessagesPrefix()+entry.text(),
                    "online",3,"player","X<red>","pass_left","14 дн.","trial_days",14));
            assertFalse(text.contains("<gray>"));assertFalse(text.contains("%pass_left%"));
        }
    }
}
