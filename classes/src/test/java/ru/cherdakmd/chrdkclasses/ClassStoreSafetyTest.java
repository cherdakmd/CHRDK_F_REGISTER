package ru.cherdakmd.chrdkclasses;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Сохранность данных важнее запуска Classes с пустым выбором вместо повреждённого. */
class ClassStoreSafetyTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final long NOW = 1_792_000_000_000L;

    private static ClassStore load(Path dir, String json) throws Exception {
        Files.writeString(dir.resolve("classes.json"), json);
        ClassStore store = new ClassStore(dir, null);
        store.load();
        return store;
    }

    private static void assertRejectedUnchanged(Path dir, String json) throws Exception {
        Files.writeString(dir.resolve("classes.json"), json);
        ClassStore store = new ClassStore(dir, null);
        assertThrows(IllegalStateException.class, store::load, json);
        assertEquals(0, store.size(), "частично прочитанные строки не публикуются");
        assertThrows(IllegalStateException.class, () -> store.setClass(PLAYER, "warrior", NOW));
        assertThrows(IllegalStateException.class, store::snapshotJson);
        store.saveNow();
        store.flush();
        assertEquals(json, Files.readString(store.file()), "даже onDisable не затирает исходник");
    }

    @Test
    void invalidRootsAndUnsupportedVersionsStayIntact(@TempDir Path dir) throws Exception {
        for (String json : List.of("", "null", "[]", "{}", "{\"version\":3}",
                "{\"version\":99,\"players\":{}}", "{\"version\":0,\"players\":{}}",
                "{\"version\":2.5,\"players\":{}}", "{\"version\":\"2\",\"players\":{}}",
                "{\"version\":3,\"players\":null}", "{\"version\":3,\"players\":{}} {}",
                "{\"version\":3,\"players\":{},\"new-important-field\":7}")) {
            assertRejectedUnchanged(dir, json);
        }
    }

    @Test
    void duplicatesAndLenientJsonCannotSilentlyDropData(@TempDir Path dir) throws Exception {
        for (String json : List.of(
                "{\"version\":1,\"version\":3,\"players\":{}}",
                "{\"version\":3,\"players\":{},\"players\":{}}",
                "{version:3,players:{}}",
                "{\"version\":3,/* комментарий */\"players\":{}}",
                "{\"version\":3,\"players\":{},}",
                "{\"version\":3,\"players\":{\"%s\":{\"name\":\"A\",\"name\":\"B\"}}}".formatted(PLAYER))) {
            assertRejectedUnchanged(dir, json);
        }
    }

    @Test
    void invalidRecordsRejectEntireFileInsteadOfDroppingPlayer(@TempDir Path dir) throws Exception {
        for (String row : List.of(
                "null", "[]", "{\"class\":\"paladin\"}",
                "{\"class\":\"warrior\",\"talents\":[\"lost-id\"]}",
                "{\"class\":\"warrior\",\"talents\":[\"mage-chary-1\"]}",
                "{\"class\":\"warrior\",\"talents\":[\"warrior-stoyka-1\",\"warrior-stoyka-1\"]}",
                "{\"class\":\"warrior\",\"talents\":null}",
                "{\"class\":\"warrior\",\"ability-at\":-1}",
                "{\"class\":\"warrior\",\"ability-at\":1.5}",
                "{\"class\":\"warrior\",\"ability-at\":9223372036854775808}",
                "{\"class\":\"warrior\",\"changed-at\":9223372036854775807}",
                "{\"name\":false}", "{\"future-progress\":100}")) {
            assertRejectedUnchanged(dir, "{\"version\":3,\"players\":{\"%s\":%s}}".formatted(PLAYER, row));
        }
    }

    @Test
    void badLaterRowDoesNotPublishEarlierValidPlayer(@TempDir Path dir) throws Exception {
        assertRejectedUnchanged(dir, """
                {"version":3,"players":{"%s":{"class":"warrior"},"not-a-uuid":{}}}
                """.formatted(PLAYER));
        assertRejectedUnchanged(dir, "{\"version\":3,\"players\":{\"1-1-1-1-1\":{}}}");
    }

    @Test
    void nonUtf8FileIsNotRewrittenWithReplacementCharacters(@TempDir Path dir) throws Exception {
        byte[] bytes = {(byte) 0xff, (byte) 0xfe, 0};
        Files.write(dir.resolve("classes.json"), bytes);
        ClassStore store = new ClassStore(dir, null);
        assertThrows(IllegalStateException.class, store::load);
        store.flush();
        assertArrayEquals(bytes, Files.readAllBytes(store.file()));
    }

    @Test
    void directoryIsNotMistakenForMissingSave(@TempDir Path dir) throws Exception {
        Files.createDirectory(dir.resolve("classes.json"));
        ClassStore store = new ClassStore(dir, null);
        assertThrows(IllegalStateException.class, store::load);
        store.flush();
        assertTrue(Files.isDirectory(store.file()));
    }

    @Test
    void oldFormatIsBackedUpByteForByteBeforeUpgrade(@TempDir Path dir) throws Exception {
        String original = "{\"version\":2,\"players\":{\"%s\":{\"class\":\"mage\"}}}".formatted(PLAYER);
        ClassStore store = load(dir, original);
        store.rename(PLAYER, "Чердак");
        store.flush();
        assertTrue(Files.readString(store.file()).contains("\"version\": 3"));
        try (var paths = Files.list(dir)) {
            List<Path> backups = paths.filter(p -> p.toString().endsWith(".bak")).toList();
            assertEquals(1, backups.size());
            assertEquals(original, Files.readString(backups.getFirst()));
        }
        ClassStore reloaded = new ClassStore(dir, null);
        reloaded.load();
        assertEquals("mage", reloaded.classOf(PLAYER));
        assertEquals("Чердак", reloaded.name(PLAYER));
        assertNull(reloaded.development(PLAYER), "загрузка сама не включает новую прокачку");
    }

    @Test
    void changedLegacyFileCannotBeOverwrittenUsingStaleMigrationBasis(@TempDir Path dir) throws Exception {
        ClassStore store = load(dir, "{\"version\":2,\"players\":{}}");
        String external = "{\"version\":2,\"players\":{\"%s\":{\"class\":\"mage\"}}}".formatted(PLAYER);
        Files.writeString(store.file(), external);
        store.setClass(PLAYER, "warrior", NOW);
        store.saveNow();
        assertTrue(store.isDirty(), "неуспешную запись нужно повторить, не считать сохранённой");
        assertEquals(external, Files.readString(store.file()));
    }

    @Test
    void existingExactBackupCanBeReusedButDamagedBackupBlocksUpgrade(@TempDir Path dir) throws Exception {
        String original = "{\"version\":2,\"players\":{}}";
        ClassStore first = load(dir, original);
        first.flush();
        ClassStore again = load(dir, original);
        again.flush();
        assertTrue(Files.readString(again.file()).contains("\"version\": 3"));
        Path backup;
        try (var paths = Files.list(dir)) {
            backup = paths.filter(p -> p.toString().endsWith(".bak")).findFirst().orElseThrow();
        }
        Files.writeString(backup, "повреждено");
        ClassStore blocked = load(dir, original);
        blocked.flush();
        assertEquals(original, Files.readString(blocked.file()));
        assertTrue(blocked.isDirty());
        assertEquals("повреждено", Files.readString(backup));
    }

    @Test
    void snapshotAloneDoesNotMarkProgressSaved(@TempDir Path dir) {
        ClassStore store = new ClassStore(dir, null);
        store.setClass(PLAYER, "warrior", NOW);
        store.snapshotJson();
        assertTrue(store.isDirty());
        assertFalse(Files.exists(store.file()));
    }

    @Test
    void repeatedLoadCannotMergeDiskIntoLiveState(@TempDir Path dir) {
        ClassStore store = new ClassStore(dir, null);
        store.load();
        store.setClass(PLAYER, "warrior", NOW);
        assertThrows(IllegalStateException.class, store::load);
        assertEquals("warrior", store.classOf(PLAYER));
    }

    @Test
    void migrationIsOneShotAndKeepsClassAndAllCooldowns(@TempDir Path dir) {
        ClassStore store = new ClassStore(dir, null);
        store.setClass(PLAYER, "warrior", NOW);
        store.clearTalents(PLAYER, NOW + 10);
        store.learnTalent(PLAYER, "warrior-stoyka-1");
        store.learnTalent(PLAYER, "warrior-stoyka-2");
        store.setAbilityAt(PLAYER, NOW + 20);
        store.setAbility2At(PLAYER, NOW + 30);
        assertTrue(store.migrateDevelopment(PLAYER, 10, 12, NOW + 40));
        ClassDevelopment migrated = store.development(PLAYER);
        assertEquals(3, migrated.migration().spentPoints());
        assertEquals(12, migrated.earnedPoints());
        assertEquals(0, store.talentCount(PLAYER));
        assertEquals("warrior", store.classOf(PLAYER));
        assertEquals(NOW, store.changedAt(PLAYER));
        assertEquals(NOW + 10, store.respecAt(PLAYER));
        assertEquals(NOW + 20, store.abilityAt(PLAYER));
        assertEquals(NOW + 30, store.ability2At(PLAYER));
        store.flush();
        ClassStore reloaded = new ClassStore(dir, null);
        reloaded.load();
        assertEquals(migrated, reloaded.development(PLAYER));
        assertFalse(reloaded.migrateDevelopment(PLAYER, 34, 177, NOW + 50));
        assertEquals(migrated, reloaded.development(PLAYER));
        assertTrue(reloaded.consumeFirstBuild(PLAYER));
        reloaded.flush();
        ClassStore third = new ClassStore(dir, null);
        third.load();
        assertFalse(third.consumeFirstBuild(PLAYER));
        assertFalse(third.migrateDevelopment(PLAYER, 34, 177, NOW + 60));
        assertEquals(12, third.development(PLAYER).earnedPoints());
        assertFalse(third.development(PLAYER).firstBuildAvailable());
    }

    @Test
    void trialReceiptAndXpSurviveRestartTogether(@TempDir Path dir) {
        ClassStore store = new ClassStore(dir, null);
        store.startDevelopment(PLAYER);
        assertTrue(store.awardClassTrial(PLAYER, "first_trial", 100));
        store.flush();
        ClassStore reloaded = new ClassStore(dir, null);
        reloaded.load();
        ClassDevelopment before = reloaded.development(PLAYER);
        assertFalse(reloaded.awardClassTrial(PLAYER, "first_trial", Long.MAX_VALUE));
        assertEquals(before, reloaded.development(PLAYER));
        assertFalse(reloaded.isDirty());
        assertEquals(3, before.earnedPoints());
        assertEquals(100, before.xp());
        assertEquals(1, before.trialAwards().size());
    }

    @Test
    void missingDevelopmentCannotSilentlyCreateXpAndSkipMigration(@TempDir Path dir) {
        ClassStore store = new ClassStore(dir, null);
        assertThrows(IllegalStateException.class,
                () -> store.awardClassXp(PLAYER, ClassDevelopment.Source.COMBAT, 10));
        store.setClass(PLAYER, "warrior", NOW);
        assertThrows(IllegalStateException.class, () -> store.startDevelopment(PLAYER));
        assertNull(store.development(PLAYER));
        assertEquals("warrior", store.classOf(PLAYER));
    }

    @Test
    void classResetsAndSwitchesDoNotReissuePointsOrMigration(@TempDir Path dir) {
        ClassStore store = new ClassStore(dir, null);
        store.startDevelopment(PLAYER);
        store.awardClassXp(PLAYER, ClassDevelopment.Source.COMBAT, 100);
        store.awardClassTrial(PLAYER, "first_trial", 10);
        ClassDevelopment before = store.development(PLAYER);
        store.setClass(PLAYER, "mage", NOW);
        store.setClass(PLAYER, "warrior", NOW + 1);
        store.clearTalents(PLAYER, NOW + 2);
        store.clearClass(PLAYER);
        store.reset(PLAYER);
        assertEquals(before, store.development(PLAYER));
        store.startDevelopment(PLAYER);
        assertEquals(before, store.development(PLAYER));
    }

    @Test
    void malformedDevelopmentCannotBeLoadedPartially(@TempDir Path dir) throws Exception {
        ClassStore store = new ClassStore(dir, null);
        store.startDevelopment(PLAYER);
        String valid = store.snapshotJson();
        for (String changed : List.of(
                valid.replace("\"xp\": 0", "\"xp\": 100"),
                valid.replace("\"revision\": 0", "\"revision\": -1"),
                valid.replace("\"firstBuildAvailable\": false", "\"firstBuildAvailable\": true"),
                valid.replace("\"trialAwards\": []", "\"trialAwards\": [\"trial\",\"trial\"]"),
                valid.replace("\"xpSources\": {}", "\"xpSources\": {\"UNKNOWN\": 1}"),
                valid.replace("\"xp\": 0", "\"xp\": \"0\""),
                valid.replace("\"revision\": 0", "\"future-field\": 7, \"revision\": 0"))) {
            assertNotEquals(valid, changed, "тест должен действительно повредить снимок");
            assertRejectedUnchanged(dir, changed);
        }
    }

    @Test
    void learnedForeignTalentIsRejectedEvenBelowCapacity(@TempDir Path dir) {
        ClassStore store = new ClassStore(dir, null);
        store.setClass(PLAYER, "warrior", NOW);
        assertFalse(store.learnTalent(PLAYER, "mage-chary-1"));
        assertEquals(0, store.talentCount(PLAYER));
    }

    @Test
    @Timeout(20)
    void concurrentSaveAndFlushEndWithLatestSnapshot(@TempDir Path dir) throws Exception {
        ClassStore store = new ClassStore(dir, null);
        store.setClass(PLAYER, "warrior", NOW);
        try (var executor = Executors.newFixedThreadPool(3)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                store.setAbilityAt(PLAYER, NOW + i);
                futures.add(executor.submit(store::saveNow));
                futures.add(executor.submit(store::flush));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        }
        store.flush();
        ClassStore reloaded = new ClassStore(dir, null);
        reloaded.load();
        assertEquals(NOW + 99, reloaded.abilityAt(PLAYER));
        assertFalse(store.isDirty());
    }
}
