package ru.cherdakmd.chrdkbp;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

/** Настоящая файловая миграция независимого stock 1.4.281, не проверка только equals(). */
class BattlePassUpgrade281Test {
    @TempDir Path folder;
    static final Logger LOG=Logger.getAnonymousLogger();
    static final UUID OWNER=UUID.fromString("b36f38cb-5e91-4e5a-a9b6-0fbbf4b2af07");
    InputStream resource(String name) { return getClass().getResourceAsStream("/"+name); }
    Path config() throws Exception {
        Path file=folder.resolve("config.yml");
        try(var in=resource("upgrade-1.4.281/config.yml")) { assertNotNull(in);Files.copy(in,file); }
        return file;
    }
    YamlConfiguration yaml(Path file) throws Exception { var c=new YamlConfiguration();c.load(file.toFile());return c; }
    YamlConfiguration current() throws Exception { var c=new YamlConfiguration();try(var r=new InputStreamReader(resource("config.yml"),java.nio.charset.StandardCharsets.UTF_8)){c.load(r);}return c; }
    List<Path> backups() throws Exception { try(var files=Files.list(folder)){return files.filter(p->p.getFileName().toString().startsWith("config-before-battlepass-")).toList();} }
    void noTemps() throws Exception {try(var files=Files.list(folder)){assertTrue(files.noneMatch(p->p.toString().endsWith(".tmp")));}}
    @Test void directStable281UpgradeHasExactBackupPreservesOtherKeysAndIsIdempotent() throws Exception {
        Path file=config();var before=yaml(file);
        before.set("battlepass.season-start","2026-09-01");before.set("owner-custom-note","do not change");before.save(file.toFile());
        String original=Files.readString(file);
        var result=BattlePassStockMigration.apply(file,this::resource);assertTrue(result.updated());
        assertEquals(original,Files.readString(result.backup()));assertEquals(1,backups().size());
        var after=yaml(file);assertEquals(current().getList("battlepass.weeks"),after.getList("battlepass.weeks"));
        before.set("battlepass.weeks",after.getList("battlepass.weeks"));assertEquals(before.getKeys(true),after.getKeys(true));
        for(String key:before.getKeys(true))if(!before.isConfigurationSection(key))assertEquals(before.get(key),after.get(key),key);
        String upgraded=Files.readString(file);
        assertFalse(BattlePassStockMigration.apply(file,this::resource).updated());
        assertEquals(upgraded,Files.readString(file));assertEquals(1,backups().size());noTemps();
    }
    @Test void allSupportedPreviousStocksReachTheSameCurrentRewards() throws Exception {
        for(String legacy:BattlePassStockMigration.LEGACIES) {
            Path file=folder.resolve("config.yml");
            try(var in=resource(legacy)){Files.copy(in,file,StandardCopyOption.REPLACE_EXISTING);}
            // 280 — снимок только weeks; у настоящего server config дата присутствует отдельно.
            var owner=yaml(file);if(!owner.contains("battlepass.season-start",true)){owner.set("battlepass.season-start","2026-10-13");owner.save(file.toFile());}
            assertTrue(BattlePassStockMigration.apply(file,this::resource).updated(),legacy);
            assertEquals(current().getList("battlepass.weeks"),yaml(file).getList("battlepass.weeks"));
            assertFalse(BattlePassStockMigration.apply(file,this::resource).updated());
        }
        noTemps();
    }
    @Test void ownerMoneyGoalsIdsDescriptionsAndExplicitEmptyWeeksAreNeverReplaced() throws Exception {
        for(String field:List.of("free-diamonds","target","id","desc")) {
            Path file=folder.resolve(field+".yml");
            try(var in=resource("upgrade-1.4.281/config.yml")){Files.copy(in,file);}
            var cfg=yaml(file);var weeks=cfg.getList("battlepass.weeks");
            @SuppressWarnings("unchecked") var mission=(Map<Object,Object>)((List<?>)((Map<?,?>)weeks.getFirst()).get("missions")).getFirst();
            mission.put(field,switch(field){case "free-diamonds"->7;case "target"->123;default->"Owner custom";});
            cfg.set("battlepass.weeks",weeks);cfg.save(file.toFile());String original=Files.readString(file);
            assertFalse(BattlePassStockMigration.apply(file,this::resource).updated());assertEquals(original,Files.readString(file));
        }
        Path empty=folder.resolve("empty.yml");Files.writeString(empty,"battlepass:\n  season-start: '2026-10-13'\n  weeks: []\n");
        String original=Files.readString(empty);assertFalse(BattlePassStockMigration.apply(empty,this::resource).updated());
        assertEquals(original,Files.readString(empty));assertTrue(backups().isEmpty());
    }
    @Test void invalidYamlMissingOrWrongWeeksAndInvalidDatesDoNotBecomeJarDefaultsOrEpochZero() throws Exception {
        for(String text:List.of("battlepass: [broken", "battlepass:\n  season-start: '2026-10-13'\n",
                "battlepass:\n  season-start: '2026-10-13'\n  weeks: wrong\n", "battlepass:\n  weeks: []\n",
                "battlepass:\n  season-start: bad-date\n  weeks: []\n",
                "battlepass:\n  season-start: '2026-10-13'\n  season-end: '2026-10-12'\n  weeks: []\n")) {
            Path file=folder.resolve("config.yml");Files.writeString(file,text);
            assertThrows(InvalidConfigurationException.class,()->BattlePassStockMigration.apply(file,this::resource),text);
            assertEquals(text,Files.readString(file));assertTrue(backups().isEmpty());
        }
        noTemps();
    }
    @Test void failuresBeforeAtomicReplaceKeepOriginalAndAfterReplaceRetryIsANoop() throws Exception {
        for(var phase:BattlePassStockMigration.Phase.values()) {
            Path dir=folder.resolve(phase.name());Files.createDirectory(dir);Path file=dir.resolve("config.yml");
            try(var in=resource("upgrade-1.4.281/config.yml")){Files.copy(in,file);}
            String original=Files.readString(file);
            assertThrows(IOException.class,()->BattlePassStockMigration.apply(file,this::resource,p->{if(p==phase)throw new IOException("injected "+phase);}));
            if(phase!=BattlePassStockMigration.Phase.AFTER_REPLACE) assertEquals(original,Files.readString(file));
            else {
                assertEquals(current().getList("battlepass.weeks"),yaml(file).getList("battlepass.weeks"));
                assertFalse(BattlePassStockMigration.apply(file,this::resource).updated());
            }
            try(var paths=Files.list(dir)){assertTrue(paths.noneMatch(p->p.toString().endsWith(".tmp")));}
        }
    }
    @Test void ownerEditDuringPreparationAndSymbolicLinkAreNotOverwritten() throws Exception {
        Path file=config();String original=Files.readString(file),edited=original+"\n# owner edit\n";
        assertThrows(IOException.class,()->BattlePassStockMigration.apply(file,this::resource,p->{if(p==BattlePassStockMigration.Phase.BEFORE_REPLACE)Files.writeString(file,edited);}));
        assertEquals(edited,Files.readString(file));assertEquals(original,Files.readString(backups().getFirst()));noTemps();
        Path link=folder.resolve("linked.yml");Files.createSymbolicLink(link,file);
        assertThrows(IOException.class,()->BattlePassStockMigration.apply(link,this::resource));
        assertTrue(Files.isSymbolicLink(link));assertEquals(edited,Files.readString(file));
    }
    @Test void oldClaimsProgressWaveAndOtherDomainFilesAreUntouchedByMigrationAndSurviveRestart() throws Exception {
        Path file=config();
        String data="{\""+OWNER+"\":{\"progress\":{\"mine_diamonds\":16,\"owner_task\":9},\"claimed\":[\"mine_diamonds:FREE\"],\"name\":\"Steve\",\"wave\":3}}";
        Files.writeString(folder.resolve("battlepass-data.json"),data);
        for(String name:List.of("battlepass-money.json","exploration.json","bank.json","data.json","clan-styles.json"))Files.writeString(folder.resolve(name),"do not touch: "+name);
        assertTrue(BattlePassStockMigration.apply(file,this::resource).updated());
        assertEquals(data,Files.readString(folder.resolve("battlepass-data.json")));
        for(String name:List.of("battlepass-money.json","exploration.json","bank.json","data.json","clan-styles.json"))assertEquals("do not touch: "+name,Files.readString(folder.resolve(name)));
        var store=new BattlePassStore(folder,LOG);store.load();assertTrue(store.healthy());
        assertEquals(16,store.progressOf(OWNER,"mine_diamonds"));assertEquals(9,store.progressOf(OWNER,"owner_task"));
        assertTrue(store.isClaimed(OWNER,"mine_diamonds","FREE"));assertEquals("Steve",store.nameOf(OWNER));assertEquals(3,store.lastWave(OWNER));
        var weeks=new ArrayList<Map<?,?>>();for(Object row:yaml(file).getList("battlepass.weeks"))weeks.add((Map<?,?>)row);
        var mission=BattlePassConfig.parseWeeks(weeks).getFirst().missions().getFirst();
        assertEquals(400,mission.freeDiamonds());assertFalse(BattlePassClaims.claimed(store,OWNER,mission,"FREE"));
        store.markClaimed(OWNER,mission.id(),"FREE_MONEY");store.save();
        var restarted=new BattlePassStore(folder,LOG);restarted.load();
        assertTrue(BattlePassClaims.claimed(restarted,OWNER,mission,"FREE"));
        assertEquals(16,restarted.progressOf(OWNER,"mine_diamonds"));assertEquals(9,restarted.progressOf(OWNER,"owner_task"));assertEquals(3,restarted.lastWave(OWNER));
        assertFalse(BattlePassStockMigration.apply(file,this::resource).updated());
    }
}
