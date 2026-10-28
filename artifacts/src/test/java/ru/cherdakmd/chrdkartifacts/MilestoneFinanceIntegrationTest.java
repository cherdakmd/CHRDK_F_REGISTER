package ru.cherdakmd.chrdkartifacts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.api.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
class MilestoneFinanceIntegrationTest {
    @TempDir Path dir;
    final UUID owner=UUID.randomUUID();
    @Test void actualLegacyWriterSeedsClaimsWithoutReissuingPhysicalMoney() throws Exception {
        var store=new CollectionStore(dir,Logger.getAnonymousLogger()); store.markClaimed(owner.toString(),25); store.flush();
        var source=new MilestoneStore(dir); source.initialize(MilestoneMigration.json(dir.resolve("collection.json"),MilestoneMigration.Kind.ARTIFACTS));
        source.reserve(owner,"percent:25",999); assertTrue(source.totals().isEmpty());
        source.reserve(owner,"percent:50",17); source=new MilestoneStore(dir); assertEquals(17,source.totals().get(owner));
        source.reserve(owner,"percent:50",999); assertEquals(17,new MilestoneStore(dir).totals().get(owner));
    }
    @Test void oldProgressSnapshotCannotResetNewMonetaryClaimOnRestart() throws Exception {
        var store=new CollectionStore(dir,Logger.getAnonymousLogger()); store.markClaimed(owner.toString(),25); store.flush();
        var source=new MilestoneStore(dir); source.initialize(MilestoneMigration.json(dir.resolve("collection.json"),MilestoneMigration.Kind.ARTIFACTS));
        source.reserve(owner,"percent:50",17);
        source=new MilestoneStore(dir); source.initialize(MilestoneMigration.json(dir.resolve("collection.json"),MilestoneMigration.Kind.ARTIFACTS));
        assertTrue(source.claimed(owner).contains("percent:50")); assertEquals(17,source.totals().get(owner));
    }
}
