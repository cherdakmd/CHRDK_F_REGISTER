package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.api.EconomyTariffMigration;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class EconomyTariffMigrationTest {
    @TempDir Path dir;
    @Test void everyKnownOldTariffChangesExactlyOnce() {
        for(String plugin:List.of("CHRDK_Core","CHRDK_Teleports","CHRDK_Claims","CHRDK_Auction","CHRDK_Lottery","CHRDK_Guilds")) {
            var c=new YamlConfiguration();var changes=EconomyTariffMigration.changes(plugin);
            changes.forEach((key,v)->c.set(key,v.previous()));
            assertEquals(changes.size(),EconomyTariffMigration.upgrade(c,changes));
            changes.forEach((key,v)->assertEquals(v.current().doubleValue(),c.getDouble(key)));
            assertEquals(0,EconomyTariffMigration.upgrade(c,changes));
        }
    }
    @Test void customDisabledAndMissingSettingsArePreservedAndMarkerPreventsReapplication() {
        var c=new YamlConfiguration();c.set("costs.rtp-diamonds",123);c.set("costs.sethome-diamonds",999);
        c.set("unrelated",false);var changes=EconomyTariffMigration.changes("CHRDK_Teleports");
        assertEquals(0,EconomyTariffMigration.upgrade(c,changes));
        assertEquals(123,c.getInt("costs.rtp-diamonds"));assertEquals(999,c.getInt("costs.sethome-diamonds"));
        assertFalse(c.contains("costs.home-diamonds"));assertFalse(c.getBoolean("unrelated"));
        c.set("costs.rtp-diamonds",5);assertEquals(0,EconomyTariffMigration.upgrade(c,changes));assertEquals(5,c.getInt("costs.rtp-diamonds"));
        var d=new YamlConfiguration();d.set("pricing.upkeep-per-day",0);
        EconomyTariffMigration.upgrade(d,EconomyTariffMigration.changes("CHRDK_Claims"));assertEquals(0,d.getInt("pricing.upkeep-per-day"));
    }
    @Test void backupIsByteExactAndSubsequentReloadDoesNotWriteAgain() throws Exception {
        var file=dir.resolve("config.yml");String old="# важный комментарий\ncosts: {home-diamonds: 1}\n";Files.writeString(file,old);
        var changes=EconomyTariffMigration.changes("CHRDK_Teleports");
        assertTrue(EconomyTariffMigration.migrateFile(file,changes,Logger.getAnonymousLogger()));
        try(var paths=Files.list(dir)) {
            var copies=paths.filter(p->p.getFileName().toString().startsWith("config-before-economy")).toList();
            assertEquals(1,copies.size());assertEquals(old,Files.readString(copies.getFirst()));
        }
        String now=Files.readString(file);assertTrue(now.contains("home-diamonds: 30"));
        assertFalse(EconomyTariffMigration.migrateFile(file,changes,Logger.getAnonymousLogger()));assertEquals(now,Files.readString(file));
    }
    @Test void failedTemporaryWriteKeepsTheOriginalAndItsBackup() throws Exception {
        var file=dir.resolve("config.yml");String old="costs: {home-diamonds: 1}";Files.writeString(file,old);
        Files.createDirectory(dir.resolve("config-economy.tmp"));
        assertThrows(IllegalStateException.class,()->EconomyTariffMigration.migrateFile(file,EconomyTariffMigration.changes("CHRDK_Teleports"),Logger.getAnonymousLogger()));
        assertEquals(old,Files.readString(file));
        try(var files=Files.list(dir)) {assertTrue(files.anyMatch(p->p.getFileName().toString().startsWith("config-before-economy")));}
    }
    @Test void invalidYamlAndFailedBackupCannotOverwriteOriginal() throws Exception {
        var file=dir.resolve("config.yml");String bad="costs: [unterminated";Files.writeString(file,bad);
        assertThrows(IllegalStateException.class,()->EconomyTariffMigration.migrateFile(file,EconomyTariffMigration.changes("CHRDK_Teleports"),Logger.getAnonymousLogger()));
        assertEquals(bad,Files.readString(file));
    }
}
