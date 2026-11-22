package ru.cherdakmd.chrdkskills;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RebirthCycleJournalTest {
    private static final String A = "11111111-2222-3333-4444-555555555555";
    private static final String B = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static SkillStore.PendingRebirth receipt(int count, long amount) {
        return new SkillStore.PendingRebirth(count, amount);
    }
    private static RebirthCycleJournal.Cycle cycle(String id, SkillStore.PendingRebirth last) {
        return new RebirthCycleJournal.Cycle(id, last);
    }

    @Test void acceptsLegacyAndNewCycleWithSameNumberAndPreservesOldReceipt() {
        var cycles = List.of(cycle("legacy", receipt(1, 30)), cycle(A, receipt(1, 40)));
        var journal = Map.of("rebirth:1", 30L, "reset:" + A, 0L, "rebirth:" + A + ":1", 40L);
        assertTrue(RebirthCycleJournal.matches(cycles, null, journal));
        assertFalse(RebirthCycleJournal.matches(List.of(cycles.get(0)), null, journal));
        assertFalse(RebirthCycleJournal.matches(cycles, null, Map.of("reset:" + A, 0L, "rebirth:" + A + ":1", 40L)));
    }

    @Test void pendingRecoversBeforeAndAfterReservationButRejectsWrongAmount() {
        var cycles = List.of(cycle("legacy", null), cycle(A, null));
        var pending = receipt(1, 40);
        assertTrue(RebirthCycleJournal.matches(cycles, pending, Map.of("reset:" + A, 0L)));
        assertTrue(RebirthCycleJournal.matches(cycles, pending, Map.of("reset:" + A, 0L, "rebirth:" + A + ":1", 40L)));
        assertFalse(RebirthCycleJournal.matches(cycles, pending, Map.of("reset:" + A, 0L, "rebirth:" + A + ":1", 41L)));
        assertFalse(RebirthCycleJournal.matches(cycles, null, Map.of("reset:" + A, 0L, "rebirth:" + A + ":1", 40L)));
    }

    @Test void requiresMarkersAndRejectsUnknownCyclesAndMalformedEvents() {
        var cycles = List.of(cycle("legacy", null), cycle(A, null));
        assertFalse(RebirthCycleJournal.matches(cycles, null, Map.of()));
        assertFalse(RebirthCycleJournal.matches(cycles, null, Map.of("reset:" + A, 1L)));
        assertFalse(RebirthCycleJournal.matches(cycles, null, Map.of("reset:" + A, 0L, "reset:" + B, 0L)));
        assertFalse(RebirthCycleJournal.matches(cycles, null, Map.of("reset:" + A, 0L, "rebirth:" + A + ":01", 10L)));
        assertFalse(RebirthCycleJournal.matches(List.of(cycle("legacy", null), cycle(A, null), cycle(A, null)), null, Map.of("reset:" + A, 0L)));
        assertFalse(RebirthCycleJournal.matches(List.of(cycle("legacy", null), cycle("invalid", null)), null, Map.of("reset:invalid", 0L)));
    }

    @Test void oldCycleCannotHaveUnaccountedNewEvents() {
        var cycles = List.of(cycle("legacy", receipt(1, 10)), cycle(A, null));
        assertFalse(RebirthCycleJournal.matches(cycles, null, Map.of("rebirth:1", 10L, "rebirth:2", 20L, "reset:" + A, 0L)));
        assertFalse(RebirthCycleJournal.matches(cycles, receipt(1, 20), Map.of("rebirth:1", 11L, "reset:" + A, 0L)));
    }
}
