package ru.cherdakmd.chrdkclasses;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Числа для переноса должны иметь подтверждённый источник, а не fallback 0. */
class LegacyClassSnapshotTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final long NOW = 1_792_000_000_000L;

    private static SkillSource skills(int total) {
        return new SkillSource() {
            public boolean available() { return true; }
            public int level(UUID id, String skill) { return 0; }
            public int totalLevel(UUID id) { return total; }
        };
    }

    private static RankSource rank(int value) {
        return new RankSource() {
            public boolean available() { return true; }
            public int rankLevel(UUID id) { return value; }
        };
    }

    private static AchievementSource achievements(int count) {
        return new AchievementSource() {
            public boolean available() { return true; }
            public int unlockedCount(UUID id) { return count; }
        };
    }

    private static ClassStore store(Path dir) {
        ClassStore store = new ClassStore(dir, null);
        store.setClass(PLAYER, "warrior", NOW - 1);
        store.learnTalent(PLAYER, "warrior-stoyka-1");
        store.flush();
        return store;
    }

    @Test
    void missingSourcesAreUnknownNotZero(@TempDir Path dir) {
        ClassStore store = store(dir);
        var result = new LegacyClassSnapshotService(store, LegacyClassRules.capture(ClassConfig.defaults()),
                null, null, null).read(PLAYER, NOW);
        assertFalse(result.ready());
        assertNull(result.basis());
        for (ClassDataRead value : result.sources().values()) {
            assertEquals(ClassDataRead.Status.UNAVAILABLE, value.status());
            assertFalse(value.usable());
        }
        assertFalse(store.isDirty());
        assertTrue(store.hasTalent(PLAYER, "warrior-stoyka-1"));
        assertNull(store.development(PLAYER));
    }

    @Test
    void realZeroIsDifferentFromMissingSource(@TempDir Path dir) {
        var result = new LegacyClassSnapshotService(store(dir), LegacyClassRules.capture(ClassConfig.defaults()),
                skills(0), rank(0), achievements(0)).read(PLAYER, NOW);
        assertTrue(result.ready());
        assertEquals(1, result.basis().level());
        assertEquals(1, result.basis().earnedPoints());
        assertEquals(0, result.basis().freePoints());
        assertEquals(PLAYER, result.basis().playerId());
    }

    @Test
    void sourceErrorsAndLinkageCannotBecomeConfirmedZeros() {
        assertEquals(ClassDataRead.Status.ERROR, ClassDataRead.read(() -> true,
                () -> { throw new IllegalStateException("read failed"); }).status());
        assertEquals(ClassDataRead.Status.ERROR, ClassDataRead.read(() -> true,
                () -> { throw new NoSuchMethodError("old API"); }).status());
        assertEquals(ClassDataRead.Status.ERROR, ClassDataRead.read(
                () -> { throw new IllegalStateException(); }, () -> 0).status());
        assertEquals(ClassDataRead.Status.INVALID, ClassDataRead.read(() -> true, () -> -1).status());
        assertEquals(ClassDataRead.Status.INVALID, rank(6).snapshotRank(PLAYER).status());
    }

    @Test
    void absentSourceDoesNotInvokeTheReader() {
        AtomicInteger calls = new AtomicInteger();
        var read = ClassDataRead.read(() -> false, () -> { calls.incrementAndGet(); return 1; });
        assertEquals(ClassDataRead.Status.UNAVAILABLE, read.status());
        assertEquals(0, calls.get());
        assertThrows(IllegalArgumentException.class, () -> new ClassDataRead(ClassDataRead.Status.ERROR, 1));
    }

    @Test
    void offlineOrBrokenSnapshotOverrideDefersMigration(@TempDir Path dir) {
        SkillSource offline = new SkillSource() {
            public boolean available() { return true; }
            public int level(UUID id, String skill) { return 0; }
            public int totalLevel(UUID id) { return 999; }
            public ClassDataRead snapshotTotal(UUID id) { return ClassDataRead.absent(ClassDataRead.Status.OFFLINE); }
        };
        var service = new LegacyClassSnapshotService(store(dir), LegacyClassRules.capture(ClassConfig.defaults()),
                offline, rank(0), achievements(0));
        var result = service.read(PLAYER, NOW);
        assertFalse(result.ready());
        assertEquals(ClassDataRead.Status.OFFLINE, result.sources().get(LegacyClassSnapshotService.Source.SKILLS).status());
        SkillSource throwing = new SkillSource() {
            public boolean available() { return true; }
            public int level(UUID id, String skill) { return 0; }
            public int totalLevel(UUID id) { return 999; }
            public ClassDataRead snapshotTotal(UUID id) { throw new NoSuchMethodError(); }
        };
        result = new LegacyClassSnapshotService(store(dir), LegacyClassRules.capture(ClassConfig.defaults()),
                throwing, rank(0), achievements(0)).read(PLAYER, NOW);
        assertFalse(result.ready());
        assertEquals(ClassDataRead.Status.ERROR, result.sources().get(LegacyClassSnapshotService.Source.SKILLS).status());
    }

    @Test
    void disabledBonusesDoNotRequireTheirApis(@TempDir Path dir) {
        LegacyClassRules rules = new LegacyClassRules(1, 30, 1, 1, 0, 0, false, true);
        var result = new LegacyClassSnapshotService(store(dir), rules, skills(300), null, null).read(PLAYER, NOW);
        assertTrue(result.ready());
        assertEquals(11, result.basis().earnedPoints());
        assertEquals(ClassDataRead.Status.NOT_REQUIRED, result.sources().get(LegacyClassSnapshotService.Source.RANKS).status());
        assertEquals(ClassDataRead.Status.NOT_REQUIRED, result.sources().get(LegacyClassSnapshotService.Source.ACHIEVEMENTS).status());
    }

    @Test
    void eachSourceIsReadOnlyOnce(@TempDir Path dir) {
        AtomicInteger skillReads = new AtomicInteger();
        AtomicInteger rankReads = new AtomicInteger();
        AtomicInteger achievementReads = new AtomicInteger();
        SkillSource skills = new SkillSource() {
            public boolean available() { return true; }
            public int level(UUID id, String skill) { return 0; }
            public int totalLevel(UUID id) { skillReads.incrementAndGet(); return 300; }
        };
        RankSource ranks = new RankSource() {
            public boolean available() { return true; }
            public int rankLevel(UUID id) { rankReads.incrementAndGet(); return 5; }
        };
        AchievementSource achievements = new AchievementSource() {
            public boolean available() { return true; }
            public int unlockedCount(UUID id) { achievementReads.incrementAndGet(); return 30; }
        };
        var result = new LegacyClassSnapshotService(store(dir), LegacyClassRules.capture(ClassConfig.defaults()),
                skills, ranks, achievements).read(PLAYER, NOW);
        assertEquals(1, skillReads.get());
        assertEquals(1, rankReads.get());
        assertEquals(1, achievementReads.get());
        assertEquals(13, result.basis().level());
        assertEquals(15, result.basis().earnedPoints());
        assertThrows(UnsupportedOperationException.class, () -> result.sources().clear());
    }

    @Test
    void frozenArithmeticMatchesOldServiceForAllRanksAndBoundaries(@TempDir Path dir) {
        ClassConfig config = ClassConfig.defaults();
        for (int rank = 0; rank <= 5; rank++) {
            for (int total : new int[]{0, 24, 25, 29, 30, 300, 989, 990, 1000, Integer.MAX_VALUE}) {
                TalentService service = new TalentService(config, store(dir), skills(total), rank(rank), achievements(45));
                var basis = service.progressSnapshot(PLAYER, NOW).basis();
                assertNotNull(basis);
                assertEquals(service.level(PLAYER), basis.level());
                assertEquals(service.earnedPoints(PLAYER), basis.earnedPoints());
                assertEquals(service.nextLevelIn(PLAYER), basis.nextLevelIn());
            }
        }
    }

    @Test
    void capturedRulesSurviveLaterConfigChanges(@TempDir Path dir) {
        ClassStore store = store(dir);
        LegacyClassRules old = LegacyClassRules.capture(ClassConfig.defaults());
        LegacyClassSnapshotService reader = new LegacyClassSnapshotService(store, old, skills(300), rank(5), achievements(30));
        var first = reader.read(PLAYER, NOW).basis();
        LegacyClassRules changed = new LegacyClassRules(1, 100, 0, 0, 0, 0, false, true);
        var different = new LegacyClassSnapshotService(store, changed, skills(300), null, null).read(PLAYER, NOW + 1).basis();
        assertNotEquals(first.earnedPoints(), different.earnedPoints());
        assertEquals(first.earnedPoints(), reader.read(PLAYER, NOW + 2).basis().earnedPoints());
        assertEquals(old, first.rules());
    }

    @Test
    void migrationPersistsSourcesAndRulesWithoutReReadingThem(@TempDir Path dir) {
        ClassStore store = store(dir);
        var basis = new LegacyClassSnapshotService(store, LegacyClassRules.capture(ClassConfig.defaults()),
                skills(300), rank(5), achievements(30)).read(PLAYER, NOW).basis();
        assertTrue(store.migrateDevelopment(PLAYER, basis));
        store.flush();
        ClassStore reloaded = new ClassStore(dir, null);
        reloaded.load();
        assertEquals(basis, reloaded.development(PLAYER).migration().basis());
        assertEquals(15, reloaded.development(PLAYER).earnedPoints());
        assertFalse(reloaded.migrateDevelopment(PLAYER, basis));
    }

    @Test
    void wrongPlayerOrChangedSelectionCannotConsumeSnapshot(@TempDir Path dir) {
        ClassStore store = store(dir);
        var basis = new LegacyClassSnapshotService(store, LegacyClassRules.capture(ClassConfig.defaults()),
                skills(300), rank(0), achievements(0)).read(PLAYER, NOW).basis();
        store.setClass(OTHER, "warrior", NOW - 1);
        store.learnTalent(OTHER, "warrior-stoyka-1");
        assertThrows(IllegalArgumentException.class, () -> store.migrateDevelopment(OTHER, basis));
        store.learnTalent(PLAYER, "warrior-stoyka-2");
        assertThrows(IllegalArgumentException.class, () -> store.migrateDevelopment(PLAYER, basis));
        assertNull(store.development(PLAYER));
        assertEquals(2, store.talentCount(PLAYER));
    }

    @Test
    void noClassDoesNotCreateAMigrationCandidate(@TempDir Path dir) {
        var result = new LegacyClassSnapshotService(new ClassStore(dir, null), LegacyClassRules.capture(ClassConfig.defaults()),
                skills(1000), rank(5), achievements(100)).read(PLAYER, NOW);
        assertFalse(result.ready());
    }

    @Test
    void deficitIsVisibleWithoutDeletingInvestedTalents(@TempDir Path dir) {
        ClassStore store = store(dir);
        store.learnTalent(PLAYER, "warrior-stoyka-2");
        var basis = new LegacyClassSnapshotService(store, LegacyClassRules.capture(ClassConfig.defaults()),
                skills(0), rank(0), achievements(0)).read(PLAYER, NOW).basis();
        assertEquals(3, basis.spentPoints());
        assertEquals(1, basis.earnedPoints());
        assertEquals(0, basis.freePoints());
        assertEquals(2, basis.deficit());
        assertEquals(Set.of("warrior-stoyka-1", "warrior-stoyka-2"), store.talents(PLAYER));
    }
    @Test
    void individualSkillReadDoesNotConcealAnApiFailure(@TempDir Path dir) {
        SkillSource source = new SkillSource() {
            public boolean available() { return true; }
            public int totalLevel(UUID id) { return 300; }
            public int level(UUID id, String skill) { throw new NoSuchMethodError(); }
        };
        TalentService service = new TalentService(ClassConfig.defaults(), store(dir), source);
        assertEquals(ClassDataRead.Status.ERROR, service.skillSnapshot(PLAYER, "defense").status());
        assertEquals(ClassDataRead.Status.INVALID, source.snapshotLevel(PLAYER, "").status());
        assertEquals(ClassDataRead.Status.INVALID, source.snapshotLevel(null, "defense").status());
    }
}
