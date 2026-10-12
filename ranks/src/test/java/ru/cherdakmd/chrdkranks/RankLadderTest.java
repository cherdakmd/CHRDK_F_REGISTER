package ru.cherdakmd.chrdkranks;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RankLadderTest {

    private static List<RankLadder.Rank> ladder() {
        return RankLadder.parse(List.of(
                Map.of("hours", 500, "id", "veteran", "name", "Ветеран"),
                Map.of("hours", 200, "id", "oldtimer", "name", "Старожил"),
                Map.of("hours", 50, "id", "regular", "name", "Завсегдатай")));
    }

    @Test
    void tierBoundariesAreInclusive() {
        List<RankLadder.Rank> ladder = ladder();
        assertEquals(-1, RankLadder.tierIndex(ladder, 0));
        assertEquals(-1, RankLadder.tierIndex(ladder, 49L * 3600 + 3599));
        assertEquals(2, RankLadder.tierIndex(ladder, 50L * 3600));
        assertEquals(1, RankLadder.tierIndex(ladder, 250L * 3600));
        assertEquals(0, RankLadder.tierIndex(ladder, 10_000L * 3600));
    }

    @Test
    void higherTierSortsFirstInTab() {
        // клиент сортирует по имени команды: высшее звание (индекс 0) — первая строка
        assertEquals("!rk-a", RankLadder.teamNameFor(0));
        assertEquals("!rk-b", RankLadder.teamNameFor(1));
        assertEquals("!rk-c", RankLadder.teamNameFor(2));
        assertTrue(RankLadder.teamNameFor(0).compareTo(RankLadder.teamNameFor(1)) < 0);
        // закреп ника ядра (!chrdk-pin) остаётся выше любых званий
        assertTrue("!chrdk-pin".compareTo(RankLadder.teamNameFor(0)) < 0);
        assertNull(RankLadder.teamNameFor(-1));
        assertNull(RankLadder.teamNameFor(100));
    }

    @Test
    void nextUpPointsToNearestUnreached() {
        List<RankLadder.Rank> ladder = ladder();
        RankLadder.NextRank next = RankLadder.nextUp(ladder, 60L * 3600);
        assertNotNull(next);
        assertEquals("oldtimer", next.rank().id());
        assertEquals(140, next.hoursLeft());
        assertNull(RankLadder.nextUp(ladder, 999L * 3600));
        RankLadder.NextRank first = RankLadder.nextUp(ladder, 0);
        assertNotNull(first);
        assertEquals("regular", first.rank().id());
        assertEquals(50, first.hoursLeft());
    }

    @Test
    void parseRejectsNonDescendingHours() {
        assertThrows(IllegalStateException.class, () -> RankLadder.parse(List.of(
                Map.of("hours", 50, "id", "a", "name", "A"),
                Map.of("hours", 500, "id", "b", "name", "B"))));
        assertThrows(IllegalStateException.class, () -> RankLadder.parse(List.of(
                Map.of("hours", 100, "id", "a", "name", "A"),
                Map.of("hours", 100, "id", "b", "name", "B"))));
    }

    @Test
    void parseRejectsDuplicatesAndGaps() {
        assertThrows(IllegalStateException.class, () -> RankLadder.parse(List.of(
                Map.of("hours", 500, "id", "a", "name", "A"),
                Map.of("hours", 400, "id", "a", "name", "A"))));
        assertThrows(IllegalStateException.class, () -> RankLadder.parse(List.of(
                Map.of("hours", 100, "id", "", "name", "A"))));
    }

    @Test
    void playtimeFormattingIsHuman() {
        assertEquals("5 ч. 30 мин.", RankLadder.formatPlaytime(5 * 3600 + 30 * 60));
        assertEquals("12 мин.", RankLadder.formatPlaytime(12 * 60));
        assertEquals("0 мин.", RankLadder.formatPlaytime(0));
        assertEquals("7 ч.", RankLadder.formatHoursLeft(7));
        assertEquals("0 ч.", RankLadder.formatHoursLeft(0));
    }
}
