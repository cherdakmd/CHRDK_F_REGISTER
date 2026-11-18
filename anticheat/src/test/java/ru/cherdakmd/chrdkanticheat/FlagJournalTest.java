package ru.cherdakmd.chrdkanticheat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlagJournalTest {

    /** Ноябрь 2026, UTC — фиксированные сутки для роллов. */
    private static final long DAY_ONE = Instant.parse("2026-11-17T12:00:00Z").toEpochMilli();
    private static final long DAY_TWO = Instant.parse("2026-11-18T12:00:00Z").toEpochMilli();

    private final long[] clock = {DAY_ONE};

    private FlagRecord record(long time, String player, String check, int vl, String world) {
        return new FlagRecord(0, time, player, "uuid-" + player.toLowerCase(), check,
                vl, 42, 19.9, world, 10.5, 64, -3.2, "verbose here");
    }

    @Test
    void appendAssignsSequentialIdsAcrossRestarts(@TempDir Path dir) throws IOException {
        FlagJournal journal = new FlagJournal(dir, 30, () -> clock[0]);
        assertEquals(1, journal.append(record(DAY_ONE, "Nick", "Speed", 1, "world")).id());
        assertEquals(2, journal.append(record(DAY_ONE, "Nick", "Speed", 2, "world")).id());
        // «Рестарт»: новая инстанция продолжает нумерацию по сохранённым записям.
        FlagJournal restarted = new FlagJournal(dir, 30, () -> clock[0]);
        assertEquals(3, restarted.append(record(DAY_ONE, "Nick", "Fly", 1, "world")).id());
    }

    @Test
    void rotatesCurrentFileIntoDailyArchive(@TempDir Path dir) throws IOException {
        FlagJournal journal = new FlagJournal(dir, 30, () -> clock[0]);
        journal.append(record(DAY_ONE, "Nick", "Speed", 1, "world"));
        assertTrue(Files.exists(dir.resolve("flags.jsonl")));
        assertFalse(Files.exists(dir.resolve("flags-20261117.jsonl")));

        clock[0] = DAY_TWO; // сутки сменились — первый флаг нового дня ротирует файл
        journal.append(record(DAY_TWO, "Nick", "Speed", 2, "world"));

        assertTrue(Files.exists(dir.resolve("flags-20261117.jsonl")));
        List<FlagRecord> all = journal.all();
        assertEquals(2, all.size());
        assertEquals("Speed", all.get(0).check());
        assertEquals(1, all.get(0).vl());
        assertEquals(2, all.get(1).vl());
    }

    @Test
    void cleanupRemovesArchivesOlderThanKeepDays(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("flags-20200101.jsonl"),
                "{\"id\":1,\"time\":1577836800000,\"player\":\"Old\",\"check\":\"Speed\",\"vl\":1}\n",
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("flags-20261101.jsonl"),
                "{\"id\":2,\"time\":1793562000000,\"player\":\"Fresh\",\"check\":\"Fly\",\"vl\":1}\n",
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("flags-notadate.jsonl"), "мусор\n", StandardCharsets.UTF_8);

        FlagJournal journal = new FlagJournal(dir, 30, () -> DAY_TWO);
        assertEquals(1, journal.cleanup());
        assertFalse(Files.exists(dir.resolve("flags-20200101.jsonl")));
        assertTrue(Files.exists(dir.resolve("flags-20261101.jsonl")));
        assertTrue(Files.exists(dir.resolve("flags-notadate.jsonl")));
    }

    @Test
    void damagedLinesAreSkippedAndHistorySurvives(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("flags.jsonl"),
                "не json вообще\n"
                        + "{\"id\":1,\"time\":" + DAY_ONE + ",\"player\":\"Nick\",\"uuid\":\"uuid-nick\",\"check\":\"Speed\",\"vl\":1,\"ping\":40,\"tps\":19.9,\"world\":\"world\",\"x\":1.0,\"y\":2.0,\"z\":3.0,\"verbose\":\"\"}\n"
                        + "{обрезанный json\n",
                StandardCharsets.UTF_8);
        FlagJournal journal = new FlagJournal(dir, 30, () -> DAY_ONE);
        List<FlagRecord> history = journal.history("NICK");
        assertEquals(1, history.size());
        assertEquals("Speed", history.get(0).check());
        // id продолжается после валидных записей файла
        assertEquals(2, journal.append(record(DAY_ONE, "Nick", "Fly", 1, "world")).id());
    }

    @Test
    void historyFiltersByNameOrUuid(@TempDir Path dir) throws IOException {
        FlagJournal journal = new FlagJournal(dir, 30, () -> clock[0]);
        journal.append(record(DAY_ONE, "Nick", "Speed", 1, "world"));
        journal.append(record(DAY_ONE, "Other", "Fly", 1, "world"));
        journal.append(record(DAY_ONE, "Nick", "Fly", 2, "world"));
        assertEquals(2, journal.history("nick").size());
        assertEquals(2, journal.history("UUID-NICK").size());
        assertEquals(1, journal.history("Other").size());
    }

    @Test
    void summarizeBuildsOffenderCard(@TempDir Path dir) throws IOException {
        FlagJournal journal = new FlagJournal(dir, 30, () -> clock[0]);
        journal.append(record(DAY_ONE, "Nick", "Speed", 2, "world"));
        journal.append(record(DAY_ONE + 1000, "Nick", "Speed", 5, "world"));
        journal.append(record(DAY_ONE + 2000, "Nick", "Fly", 1, "world_nether"));
        List<FlagRecord> history = journal.history("Nick");
        FlagJournal.Summary summary = FlagJournal.summarize(history);

        assertEquals(3, summary.total());
        assertEquals(DAY_ONE, summary.first());
        assertEquals(DAY_ONE + 2000, summary.last());
        assertEquals(Map.of("Speed", 2L, "Fly", 1L), summary.checkCounts());
        assertEquals(Map.of("Speed", 5, "Fly", 1), summary.checkMaxVl());
        assertEquals(List.of("world 10 64 -3", "world_nether 10 64 -3"), summary.places());
    }

    @Test
    void topSinceCountsPlayersAfterMomentOnly(@TempDir Path dir) throws IOException {
        FlagJournal journal = new FlagJournal(dir, 30, () -> clock[0]);
        journal.append(record(DAY_ONE, "Nick", "Speed", 1, "world"));
        journal.append(record(DAY_ONE + 1000, "Nick", "Fly", 1, "world"));
        journal.append(record(DAY_ONE + 2000, "Other", "Speed", 1, "world"));

        List<FlagJournal.TopEntry> late = journal.topSince(DAY_ONE + 1500);
        assertEquals(1, late.size());
        assertEquals("Other", late.get(0).player());
        assertEquals(1, late.get(0).total());

        List<FlagJournal.TopEntry> full = journal.topSince(0);
        assertEquals(2, full.size());
        assertEquals("Nick", full.get(0).player());
        assertEquals(2, full.get(0).total());
        assertTrue(full.get(0).checks().contains("Speed"));
        assertTrue(full.get(0).checks().contains("Fly"));
    }

    @Test
    void emptySummaryIsEmpty(@TempDir Path dir) throws IOException {
        FlagJournal journal = new FlagJournal(dir, 30, () -> clock[0]);
        assertTrue(journal.history("Nobody").isEmpty());
        FlagJournal.Summary summary = FlagJournal.summarize(List.of());
        assertEquals(0, summary.total());
        assertTrue(summary.places().isEmpty());
    }
}
