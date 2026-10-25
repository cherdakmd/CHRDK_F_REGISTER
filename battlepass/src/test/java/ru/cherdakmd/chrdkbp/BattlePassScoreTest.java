package ru.cherdakmd.chrdkbp;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Очки сезона и рейтинг: считаются по выполненным заданиям, а не по забранным наградам. */
class BattlePassScoreTest {

    private static BattlePassConfig.MissionDef mission(String id, int target, int points) {
        return new BattlePassConfig.MissionDef(id, "<gray>" + id + "</gray>",
                BattlePassConfig.MissionType.MINE, "stone", target, 1, 1,
                points, List.of(), "", "");
    }

    private static BattlePassConfig.Season season() {
        return new BattlePassConfig.Season("Сезон", 0L, List.of(
                new BattlePassConfig.WeekDef(0, List.of(
                        mission("a", 10, 20),
                        mission("b", 5, 10))),
                new BattlePassConfig.WeekDef(7, List.of(
                        mission("c", 1, 30)))));
    }

    @Test
    void pointsAndCompletedCountReachedGoals() {
        BattlePassConfig.Season season = season();
        Map<String, Integer> progress = Map.of("a", 10, "b", 4);
        assertEquals(20, BattlePassScore.points(season, progress), "b ещё не выполнено");
        assertEquals(1, BattlePassScore.completed(season, progress));
        assertEquals(60, BattlePassScore.points(season, Map.of("a", 99, "b", 5, "c", 1)),
                "перевыполнение не удваивает очки");
        assertEquals(3, BattlePassScore.completed(season, Map.of("a", 10, "b", 5, "c", 1)));
    }

    @Test
    void claimedTracksAreCountedPerBranch() {
        BattlePassConfig.Season season = season();
        assertEquals(0, BattlePassScore.claimed(season, Set.of()));
        assertEquals(1, BattlePassScore.claimed(season, Set.of("a:FREE")));
        assertEquals(2, BattlePassScore.claimed(season, Set.of("a:FREE", "a:VIP")));
        assertEquals(2, BattlePassScore.claimed(season, Set.of("a:FREE", "a:VIP", "ghost:FREE")),
                "чужие отметки не считаются");
    }

    @Test
    void leaderboardSortsByPointsThenCompletedThenName() {
        BattlePassConfig.Season season = season();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        Map<UUID, Map<String, Integer>> progress = new LinkedHashMap<>();
        progress.put(first, Map.of("a", 10));          // 20 очков
        progress.put(second, Map.of("a", 10, "b", 5)); // 30 очков
        progress.put(third, Map.of());                 // пусто — не в рейтинге
        Map<UUID, String> names = Map.of(first, "Alex", second, "Steve");
        List<BattlePassScore.Entry> top = BattlePassScore.leaderboard(
                season, progress, names, Map.of(), 0);
        assertEquals(2, top.size());
        assertEquals("Steve", top.get(0).name());
        assertEquals(30, top.get(0).points());
        assertEquals(2, top.get(0).completed());
        assertEquals("Alex", top.get(1).name());
        assertEquals(1, BattlePassScore.position(top, second));
        assertEquals(0, BattlePassScore.position(top, third));
    }

    @Test
    void leaderboardRespectsLimitAndMissingNames() {
        BattlePassConfig.Season season = season();
        UUID id = UUID.randomUUID();
        List<BattlePassScore.Entry> top = BattlePassScore.leaderboard(
                season, Map.of(id, Map.of("a", 10)), Map.of(), Map.of(), 5);
        assertEquals(1, top.size());
        assertEquals(id.toString().substring(0, 8), top.get(0).name(),
                "без ника показываем короткий uuid");
        assertEquals(1, BattlePassScore.leaderboard(
                season, Map.of(id, Map.of("a", 10)), Map.of(), Map.of(), 1).size());
        assertTrue(BattlePassScore.leaderboard(season, Map.of(), Map.of(), Map.of(), 0).isEmpty());
    }
}
