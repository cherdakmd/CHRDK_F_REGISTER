package ru.cherdakmd.chrdkskills;
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
        var store=new SkillStore(dir,Logger.getAnonymousLogger()); store.markMilestone(owner.toString(),10); store.flush();
        var source=new MilestoneStore(dir); source.initialize(MilestoneMigration.json(dir.resolve("skills.json"),MilestoneMigration.Kind.SKILLS));
        source.reserve(owner,"level:10",999); assertTrue(source.totals().isEmpty());
        source.reserve(owner,"level:25",17); source=new MilestoneStore(dir); assertEquals(17,source.totals().get(owner));
        source.reserve(owner,"level:25",999); assertEquals(17,new MilestoneStore(dir).totals().get(owner));
    }
    @Test void oldProgressSnapshotCannotResetNewMonetaryClaimOnRestart() throws Exception {
        var store=new SkillStore(dir,Logger.getAnonymousLogger()); store.markMilestone(owner.toString(),10); store.flush();
        var source=new MilestoneStore(dir); source.initialize(MilestoneMigration.json(dir.resolve("skills.json"),MilestoneMigration.Kind.SKILLS));
        source.reserve(owner,"level:25",17);
        source=new MilestoneStore(dir); source.initialize(MilestoneMigration.json(dir.resolve("skills.json"),MilestoneMigration.Kind.SKILLS));
        assertTrue(source.claimed(owner).contains("level:25")); assertEquals(17,source.totals().get(owner));
    }
}
