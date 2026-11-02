package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class Upgrade281CompatibilityTest {
    @TempDir Path folder;
    static final Logger LOG=Logger.getAnonymousLogger();
    static final UUID OWNER=UUID.fromString("b36f38cb-5e91-4e5a-a9b6-0fbbf4b2af07");
    YamlConfiguration yaml(String name) throws Exception {
        try(var input=getClass().getResourceAsStream("/"+name)) {
            assertNotNull(input,name);var cfg=new YamlConfiguration();cfg.load(new InputStreamReader(input,StandardCharsets.UTF_8));return cfg;
        }
    }
    List<YamlConfiguration> snapshots() throws Exception {
        var list=new ArrayList<YamlConfiguration>();for(String name:PresentationMigration.LEGACY_RESOURCES)list.add(yaml(name));return list;
    }
    @Test void realStable281TabUpgradesOnceWithoutChangingSwitchesPinsTimersOrBoostSettings() throws Exception {
        var live=yaml("upgrade-1.4.281/core-config.yml");var fresh=yaml("config.yml");
        live.set("tablist.enabled",false);live.set("tablist.pinned-names",List.of("Owner"));live.set("tablist.refresh-ticks",0);
        live.set("tablist.show-rank-name",false);Object oldBoost=live.getConfigurationSection("reward-boost").getValues(true);
        var replacements=PresentationMigration.replacements(live,snapshots(),fresh);
        assertTrue(replacements.containsAll(List.of("tablist.header","tablist.footer")),replacements.toString());
        for(String key:replacements)live.set(key,fresh.get(key));
        assertFalse(live.getBoolean("tablist.enabled"));assertEquals(List.of("Owner"),live.getStringList("tablist.pinned-names"));
        assertEquals(0,live.getInt("tablist.refresh-ticks"));assertFalse(live.getBoolean("tablist.show-rank-name"));
        assertEquals(oldBoost,live.getConfigurationSection("reward-boost").getValues(true));
        assertTrue(PresentationMigration.replacements(live,snapshots(),fresh).isEmpty());
    }
    @Test void customOrEmptyHeaderAndBlankOrCustomNameFormatsSurviveUpgrade() throws Exception {
        var fresh=yaml("config.yml");var snapshots=snapshots();
        for(List<String> header:List.of(List.<String>of(),List.of(""),List.of(" ",""),List.of("Owner layout %player%"))) {
            for(String format:List.of("","  ","<red>%player%</red>")) {
                var live=yaml("upgrade-1.4.281/core-config.yml");live.set("tablist.header",header);live.set("tablist.player-name-format",format);
                var replacements=PresentationMigration.replacements(live,snapshots,fresh);
                assertFalse(replacements.contains("tablist.header"));assertFalse(replacements.contains("tablist.player-name-format"));
                assertTrue(replacements.contains("tablist.footer"));
                for(String key:replacements)live.set(key,fresh.get(key));
                assertEquals(header,live.getStringList("tablist.header"));assertEquals(format,live.getString("tablist.player-name-format"));
            }
        }
    }
    Map<String,String> map(Messages messages) {
        var result=new LinkedHashMap<String,String>();for(String key:messages.defaultKeys())result.put(key,messages.template(key));return result;
    }
    Messages messages(Path file) throws Exception { return Messages.from(Files.newInputStream(file),null,LOG); }
    @Test void independentStableMessagesUpgradeThroughRuntimeSnapshotsWhileOwnerTextsRemain() throws Exception {
        for(String module:List.of("battlepass","guilds")) {
            String fixture=module.equals("guilds")?"guild-messages.yml":"battlepass-messages.yml";
            var old=map(Messages.from(getClass().getResourceAsStream("/upgrade-1.4.281/"+fixture),null,LOG));
            var defaults=map(messages(Path.of(module,"src/main/resources/messages.yml")));
            List<Path> legacy=module.equals("guilds")?List.of(Path.of("guilds/src/main/resources/guild-style-messages-legacy-1.4.282.yml"))
                    :List.of(Path.of("battlepass/src/main/resources/battlepass-messages-legacy-1.4.280.yml"),Path.of("battlepass/src/main/resources/battlepass-messages-legacy-1.4.282.yml"));
            var live=new LinkedHashMap<>(old);var history=new ArrayList<Map<String,String>>();
            for(Path path:legacy)history.add(map(messages(path)));
            for(var snapshot:history)Messages.migrateStockOverrides(defaults,live,snapshot);
            long changed=old.keySet().stream().filter(key->!Objects.equals(old.get(key),live.get(key))).count();
            assertTrue(changed>0,module);
            for(String key:old.keySet())for(var snapshot:history) {
                if(defaults.containsKey(key)&&Objects.equals(old.get(key),snapshot.get(key)))assertEquals(defaults.get(key),live.get(key),key);
            }
            var once=Map.copyOf(live);for(var snapshot:history)Messages.migrateStockOverrides(defaults,live,snapshot);assertEquals(once,live);
            String customKey=old.keySet().stream().filter(key->defaults.containsKey(key)&&!Objects.equals(old.get(key),defaults.get(key))).findFirst().orElseThrow();
            live.put(customKey,"Owner custom text");for(var snapshot:history)Messages.migrateStockOverrides(defaults,live,snapshot);
            assertEquals("Owner custom text",live.get(customKey));
        }
    }
    @Test void synthetic281AccountKeepsCredentialsPaidRightsReceiptsAndBoostAfterRepeatedLoad() throws Exception {
        try(var input=getClass().getResourceAsStream("/upgrade-1.4.281/data.json")){assertNotNull(input);Files.copy(input,folder.resolve("data.json"));}
        var first=new RegistrationStore(folder,LOG);var account=first.getAccount("Steve").orElseThrow();
        assertEquals("fixture-only-not-a-real-password-hash",account.passwordHash);assertEquals("JBSWY3DPEHPK3PXP",account.totpSecret);
        assertEquals(3,account.rankLevel);assertTrue(account.rankPermanent);assertEquals(2,account.paymentsCount);
        assertEquals(24L*RewardBoostMath.HOUR,first.rewardBoostRemaining("Steve"));
        String receipt="bp:"+"a".repeat(64);
        assertEquals(ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi.BoostGrant.ALREADY,first.grantRewardBoostOnce(OWNER,"Steve",24,24,receipt));
        first.grantRewardBoost("Steve",30,24);first.flushBlocking();
        var restarted=new RegistrationStore(folder,LOG);assertEquals(54L*RewardBoostMath.HOUR,restarted.rewardBoostRemaining("Steve"));
        assertEquals(ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi.BoostGrant.ALREADY,restarted.grantRewardBoostOnce(OWNER,"Steve",24,24,receipt));
        var after=restarted.getAccount("Steve").orElseThrow();
        assertEquals(account.passwordHash,after.passwordHash);assertEquals(account.totpSecret,after.totpSecret);assertEquals(account.lastTotpStep,after.lastTotpStep);
        assertEquals(account.rankLevel,after.rankLevel);assertEquals(account.rankPermanent,after.rankPermanent);assertEquals(account.passUntil,after.passUntil);
        assertEquals(account.entitlements,after.entitlements);assertEquals(account.paymentsCount,after.paymentsCount);assertEquals(account.totalPaidKopecks,after.totalPaidKopecks);
        assertEquals(account.rewardBoostReceipts,after.rewardBoostReceipts);assertEquals(account.rewardBoostFractions,after.rewardBoostFractions);
    }
}
