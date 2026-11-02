package ru.cherdakmd.chrdkhunt;

import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FullMoonLedgerTest {
    @TempDir Path dir;
    private FullMoonLedger.Night selected(long day) { return new FullMoonLedger.Night(day, true, UUID.randomUUID(), 0, false); }
    @Test void warningStartAndFinishSurviveRestartWithoutChangingTokenOrDeadline() {
        Path file = dir.resolve("full-moon.json"); UUID world = UUID.randomUUID();
        var ledger = new FullMoonLedger(file); var chosen = selected(8);
        assertTrue(ledger.put(world, chosen));
        assertEquals(chosen, new FullMoonLedger(file).get(world));
        var started = chosen.start(2000);
        assertTrue(ledger.put(world, started));
        var resumed = new FullMoonLedger(file); assertEquals(started, resumed.get(world));
        assertTrue(resumed.put(world, started.finish()));
        var finished = new FullMoonLedger(file).get(world);
        assertTrue(finished.finished()); assertEquals(2000, finished.started()); assertEquals(chosen.token(), finished.token());
    }
    @Test void sameNightCannotBeRerolledOrReopened() {
        var ledger = new FullMoonLedger(dir.resolve("moon.json")); UUID world = UUID.randomUUID(); var n = selected(8);
        assertTrue(ledger.put(world, n)); assertFalse(ledger.put(world, selected(8)));
        assertTrue(ledger.put(world, n.start(1000))); assertFalse(ledger.put(world, n.start(2000)));
        assertTrue(ledger.put(world, n.start(1000).finish())); assertFalse(ledger.put(world, n.start(1000)));
        assertFalse(ledger.put(world, selected(0))); assertTrue(ledger.put(world, selected(16)));
    }
    @Test void skippedNightStaysSkipped() {
        var ledger = new FullMoonLedger(dir.resolve("moon.json")); UUID world = UUID.randomUUID();
        var declined = new FullMoonLedger.Night(8, false, UUID.randomUUID(), 0, true);
        assertTrue(ledger.put(world, declined)); assertFalse(ledger.put(world, selected(8)));
        assertFalse(new FullMoonLedger(dir.resolve("moon.json")).get(world).selected());
    }
    @Test void ambiguousFileIsNotOverwrittenOrReset() throws Exception {
        Path file = dir.resolve("moon.json"); String bad = "{broken"; Files.writeString(file, bad);
        var ledger = new FullMoonLedger(file); assertFalse(ledger.healthy());
        assertFalse(ledger.put(UUID.randomUUID(), selected(8))); assertEquals(bad, Files.readString(file));
    }
    @Test void unavailableDiskFailsClosedAndDoesNotRetryAsEmptyCalendar() throws Exception {
        Path parent = dir.resolve("not-a-directory"); Files.writeString(parent, "keep");
        var ledger = new FullMoonLedger(parent.resolve("moon.json"));
        assertFalse(ledger.put(UUID.randomUUID(), selected(8))); assertFalse(ledger.healthy());
        assertFalse(ledger.put(UUID.randomUUID(), selected(16))); assertEquals("keep", Files.readString(parent));
    }
    @Test void invalidSchemaAndFractionalDayAreRejected() throws Exception {
        Path file = dir.resolve("moon.json"); UUID world = UUID.randomUUID();
        Files.writeString(file, "{\"version\":1,\"worlds\":{\"" + world + "\":{\"day\":8.5,\"selected\":true,\"finished\":false,\"started\":0,\"token\":\"" + UUID.randomUUID() + "\"}}}");
        assertFalse(new FullMoonLedger(file).healthy());
        Files.writeString(file, "{\"version\":2,\"worlds\":{}}"); assertFalse(new FullMoonLedger(file).healthy());
        Files.writeString(file, "{\"version\":\"1\",\"worlds\":{}}"); assertFalse(new FullMoonLedger(file).healthy());
    }
}
