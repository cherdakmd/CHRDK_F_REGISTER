package ru.cherdakmd.chrdkachievements;
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
        var store=new AchievementStore(dir,Logger.getAnonymousLogger()); store.unlock(owner.toString(),"first_diamond"); store.markClaimed(owner.toString(),"first_diamond"); store.flush();
        var source=new MilestoneStore(dir); source.initialize(MilestoneMigration.json(dir.resolve("achievements.json"),MilestoneMigration.Kind.ACHIEVEMENTS));
        source.reserve(owner,"first_diamond",999); assertTrue(source.totals().isEmpty());
        source.reserve(owner,"next_achievement",17); source=new MilestoneStore(dir); assertEquals(17,source.totals().get(owner));
        source.reserve(owner,"next_achievement",999); assertEquals(17,new MilestoneStore(dir).totals().get(owner));
    }
    @Test void oldProgressSnapshotCannotResetNewMonetaryClaimOnRestart() throws Exception {
        var store=new AchievementStore(dir,Logger.getAnonymousLogger()); store.unlock(owner.toString(),"first_diamond"); store.markClaimed(owner.toString(),"first_diamond"); store.flush();
        var source=new MilestoneStore(dir); source.initialize(MilestoneMigration.json(dir.resolve("achievements.json"),MilestoneMigration.Kind.ACHIEVEMENTS));
        source.reserve(owner,"next_achievement",17);
        source=new MilestoneStore(dir); source.initialize(MilestoneMigration.json(dir.resolve("achievements.json"),MilestoneMigration.Kind.ACHIEVEMENTS));
        assertTrue(source.claimed(owner).contains("next_achievement")); assertEquals(17,source.totals().get(owner));
    }
}
