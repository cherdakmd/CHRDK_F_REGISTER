package ru.cherdakmd.chrdkbp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BattlePassConfigTest {

    private static BattlePassConfig.Season season() {
        return new BattlePassConfig.Season(
                "Сезон",
                java.time.LocalDate.parse("2026-10-13").atStartOfDay(java.time.ZoneOffset.UTC)
                        .toInstant().toEpochMilli(),
                BattlePassConfig.parseWeeks(List.of(
                        Map.of("unlock-day", 0, "missions", List.of(
                                Map.of("id", "m1", "type", "MINE", "material", "DIAMOND_ORE",
                                        "target", 5, "free-days", 1, "vip-days", 2))),
                        Map.of("unlock-day", 7, "missions", List.of(
                                Map.of("id", "m2", "type", "KILL", "entity", "ZOMBIE",
                                        "target", 10, "free-days", 1, "vip-days", 0))))));
    }

    private static long start() {
        return season().startEpochMillis();
    }

    @Test
    void weekOpensOnScheduleDay() {
        BattlePassConfig.Season season = season();
        assertEquals(1, BattlePassConfig.currentWeek(season, start() + 1000));
        assertEquals(1, BattlePassConfig.currentWeek(season, start() + 6L * 86_400_000));
        assertEquals(2, BattlePassConfig.currentWeek(season, start() + 7L * 86_400_000 + 1000));
        assertTrue(BattlePassConfig.isUnlocked(season, 0, start() + 1));
        assertFalse(BattlePassConfig.isUnlocked(season, 1, start() + 1));
        assertEquals(7, BattlePassConfig.daysUntilUnlock(season, 1, start()));
        // до старта сезона волн нет
        assertEquals(0, BattlePassConfig.currentWeek(season, start() - 1000));
    }

    @Test
    void datesMustBeIso() {
        assertThrows(IllegalStateException.class,
                () -> BattlePassConfig.season("X", "13.10.2026", List.of()));
    }

    @Test
    void parseSkipsGarbageMissions() {
        List<BattlePassConfig.WeekDef> weeks = BattlePassConfig.parseWeeks(List.of(
                Map.of("unlock-day", 0, "missions", List.of(
                        Map.of("id", "ok", "type", "craft", "material", "stick", "target", 2),
                        Map.of("id", "", "type", "MINE", "material", "stone", "target", 2),
                        Map.of("id", "bad_type", "type", "WALK", "target", 2),
                        Map.of("id", "no_target", "type", "MINE", "material", "stone"),
                        Map.of("id", "ok", "type", "MINE", "material", "stone", "target", 1),
                        Map.of("id", "fishy", "type", "FISH", "target", 3,
                                "free-days", "2", "vip-days", "1")))));
        assertEquals(1, weeks.size());
        List<BattlePassConfig.MissionDef> missions = weeks.get(0).missions();
        assertEquals(2, missions.size(), "ok/craft + fishy; мусор и дубликат id отброшены");
        BattlePassConfig.MissionDef fish = missions.stream()
                .filter(m -> m.id().equals("fishy")).findFirst().orElseThrow();
        assertEquals("any", fish.matchKey(), "FISH без entity ловит любой улов");
        assertEquals(2, fish.freeDays());
        assertEquals(1, fish.vipDays());
        assertEquals(BattlePassConfig.MissionType.CRAFT, missions.get(0).type());
    }

    @Test
    void unlockDaysMustNotDecrease() {
        assertThrows(IllegalStateException.class, () -> BattlePassConfig.parseWeeks(List.of(
                Map.of("unlock-day", 7, "missions", List.of(
                        Map.of("id", "a", "type", "MINE", "material", "stone", "target", 1))),
                Map.of("unlock-day", 3, "missions", List.of(
                        Map.of("id", "b", "type", "MINE", "material", "stone", "target", 1))))));
    }

    @Test
    void findAndMatchKeys() {
        BattlePassConfig.Season season = season();
        BattlePassConfig.Found found = BattlePassConfig.find(season, " M2 ");
        assertNotNull(found);
        assertEquals(1, found.weekIndex());
        assertNull(BattlePassConfig.find(season, "nope"));

        BattlePassConfig.MissionDef zombie = found.mission();
        assertTrue(BattlePassConfig.matchesTypeKey(zombie, "zombie"));
        assertFalse(BattlePassConfig.matchesTypeKey(zombie, "skeleton"));
        BattlePassConfig.MissionDef any = new BattlePassConfig.MissionDef(
                "f", "d", BattlePassConfig.MissionType.FISH, "any", 1, 0, 0);
        assertTrue(BattlePassConfig.matchesTypeKey(any, "pufferfish"));
    }
}
