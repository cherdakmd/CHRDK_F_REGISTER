package ru.cherdakmd.chrdktierforge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Ротация резервных копий stats.yml (1.4.95). */
class StatsBackupTest {

    @Test
    void namesFollowTheNumbering(@TempDir Path dir) {
        StatsBackup backup = new StatsBackup(dir.resolve("stats.yml"), 3);
        assertEquals(List.of(
                        dir.resolve("stats.yml.bak1"),
                        dir.resolve("stats.yml.bak2"),
                        dir.resolve("stats.yml.bak3")),
                backup.backups());
    }

    @Test
    void keepIsClampedToSaneRange() {
        assertEquals(0, StatsBackup.clampKeep(-4));
        assertEquals(0, StatsBackup.clampKeep(0));
        assertEquals(3, StatsBackup.clampKeep(3));
        assertEquals(StatsBackup.MAX_COPIES, StatsBackup.clampKeep(99));
    }

    @Test
    void rotationShiftsOldCopiesAndKeepsLatest(@TempDir Path dir) throws IOException {
        Path stats = dir.resolve("stats.yml");
        Files.writeString(stats, "v1");
        StatsBackup backup = new StatsBackup(stats, 2);

        backup.rotate();
        assertEquals("v1", Files.readString(dir.resolve("stats.yml.bak1")));

        Files.writeString(stats, "v2");
        backup.rotate();
        assertEquals("v2", Files.readString(dir.resolve("stats.yml.bak1")), "свежая копия — на первом слоте");
        assertEquals("v1", Files.readString(dir.resolve("stats.yml.bak2")), "прежняя копия сдвинулась");

        Files.writeString(stats, "v3");
        backup.rotate();
        assertFalse(Files.exists(dir.resolve("stats.yml.bak3")), "копий ровно keep, старые не копятся");
        assertEquals("v2", Files.readString(dir.resolve("stats.yml.bak2")));
        assertEquals("v3", Files.readString(stats), "сам файл ротацией не трогается");
    }

    @Test
    void zeroKeepOrMissingFileDoesNothing(@TempDir Path dir) throws IOException {
        Path stats = dir.resolve("stats.yml");
        new StatsBackup(stats, 0).rotate();
        assertTrue(Files.notExists(dir.resolve("stats.yml.bak1")), "выключенные копии не создаются");

        new StatsBackup(stats, 3).rotate();
        assertTrue(Files.notExists(dir.resolve("stats.yml.bak1")), "файла ещё нет — копировать нечего");
    }
}
