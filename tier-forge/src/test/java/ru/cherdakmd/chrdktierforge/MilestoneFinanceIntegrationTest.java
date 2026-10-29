package ru.cherdakmd.chrdktierforge;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.api.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class MilestoneFinanceIntegrationTest {
    @TempDir Path dir;
    @Test void actualKeeperWriterSeedsOldSectionsAndCountsOnlyOnce() throws Exception {
        UUID owner=UUID.randomUUID(); var keeper=new TierCollectionKeeper(); keeper.record(owner,"common",List.of(),List.of());
        keeper.claim(owner,"properties:10"); keeper.claim(owner,"section:tiers");
        var yaml=new YamlConfiguration(); keeper.writeTo(yaml.createSection("collection")); yaml.save(dir.resolve("stats.yml").toFile());
        var source=new MilestoneStore(dir,"tierforge-awards"); source.initialize(MilestoneMigration.tierForge(dir.resolve("stats.yml")));
        source.reserve(owner,"section:tiers",100); source.reserve(owner,"properties:10",100); assertTrue(source.totals().isEmpty());
        source.reserve(owner,"properties:25",17); source=new MilestoneStore(dir,"tierforge-awards"); assertEquals(17,source.totals().get(owner));
        source.initialize(MilestoneMigration.tierForge(dir.resolve("stats.yml"))); source.reserve(owner,"properties:25",100);
        assertEquals(17,new MilestoneStore(dir,"tierforge-awards").totals().get(owner));
    }
}
