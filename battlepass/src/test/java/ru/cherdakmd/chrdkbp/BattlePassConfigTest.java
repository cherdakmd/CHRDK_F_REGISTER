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
                                        "target", 5, "free-hours", 1, "vip-hours", 2))),
                        Map.of("unlock-day", 7, "missions", List.of(
                                Map.of("id", "m2", "type", "KILL", "entity", "ZOMBIE",
                                        "target", 10, "free-hours", 1, "vip-hours", 0))))));
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
                                "free-hours", "2", "vip-hours", "1")))));
        assertEquals(1, weeks.size());
        List<BattlePassConfig.MissionDef> missions = weeks.get(0).missions();
        assertEquals(2, missions.size(), "ok/craft + fishy; мусор и дубликат id отброшены");
        BattlePassConfig.MissionDef fish = missions.stream()
                .filter(m -> m.id().equals("fishy")).findFirst().orElseThrow();
        assertEquals("any", fish.matchKey(), "FISH без entity ловит любой улов");
        assertEquals(2, fish.freeHours());
        assertEquals(1, fish.vipHours());
        assertEquals(BattlePassConfig.MissionType.CRAFT, missions.get(0).type());
    }

    @Test
    void legacyDayKeysAreReadAsHours() {
        BattlePassConfig.MissionDef mission = BattlePassConfig.parseWeeks(List.of(
                Map.of("unlock-day", 0, "missions", List.of(
                        Map.of("id", "old", "type", "MINE", "material", "stone", "target", 1,
                                "free-days", 2, "vip-days", 3, "premium-days", 4)))))
                .get(0).missions().get(0);
        assertEquals(2, mission.freeHours(), "free-days читается как часы");
        assertEquals(3, mission.vipHours(), "vip-days читается как часы");
        assertEquals(4, mission.premiumHours(), "premium-days читается как часы");
    }

    @Test
    void rewardHoursAreCappedAtOneDay() {
        BattlePassConfig.MissionDef mission = BattlePassConfig.parseWeeks(List.of(
                Map.of("unlock-day", 0, "missions", List.of(
                        Map.of("id", "huge", "type", "MINE", "material", "stone", "target", 1,
                                "free-hours", 400, "vip-hours", -3, "premium-hours", 99)))))
                .get(0).missions().get(0);
        assertEquals(24, mission.freeHours(), "потолок награды — сутки");
        assertEquals(0, mission.vipHours(), "отрицательные часы — без награды");
        assertEquals(0, mission.premiumHours(), "премиум больше суток не выдаётся");
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
    void newMissionTypesAreParsed() {
        List<BattlePassConfig.WeekDef> weeks = BattlePassConfig.parseWeeks(List.of(
                Map.of("unlock-day", 0, "missions", List.of(
                        java.util.Map.ofEntries(
                                Map.entry("id", "build"), Map.entry("type", "PLACE"),
                                Map.entry("material", "STONE"), Map.entry("target", 10)),
                        java.util.Map.ofEntries(
                                Map.entry("id", "smelt"), Map.entry("type", "smelt"),
                                Map.entry("material", "IRON_INGOT"), Map.entry("target", 5)),
                        java.util.Map.ofEntries(
                                Map.entry("id", "enchant"), Map.entry("type", "ENCHANT"),
                                Map.entry("material", "DIAMOND_SWORD"), Map.entry("target", 1)),
                        java.util.Map.ofEntries(
                                Map.entry("id", "breed"), Map.entry("type", "BREED"),
                                Map.entry("entity", "COW"), Map.entry("target", 2))))));
        List<BattlePassConfig.MissionDef> missions = weeks.get(0).missions();
        assertEquals(4, missions.size());
        assertEquals(BattlePassConfig.MissionType.PLACE, missions.get(0).type());
        assertEquals(BattlePassConfig.MissionType.SMELT, missions.get(1).type());
        assertEquals(BattlePassConfig.MissionType.ENCHANT, missions.get(2).type());
        assertEquals(BattlePassConfig.MissionType.BREED, missions.get(3).type());
        assertEquals("cow", missions.get(3).matchKey());
    }

    @Test
    void multiTargetMatchesAnyPart() {
        BattlePassConfig.MissionDef undead = new BattlePassConfig.MissionDef(
                "undead", "d", BattlePassConfig.MissionType.KILL, "zombie|husk|drowned",
                10, 0, 0);
        assertTrue(BattlePassConfig.matchesTypeKey(undead, "HUSK"));
        assertTrue(BattlePassConfig.matchesTypeKey(undead, "drowned"));
        assertFalse(BattlePassConfig.matchesTypeKey(undead, "skeleton"));
        BattlePassConfig.MissionDef spaced = new BattlePassConfig.MissionDef(
                "spaced", "d", BattlePassConfig.MissionType.MINE, "stone, deepslate",
                1, 0, 0);
        assertTrue(BattlePassConfig.matchesTypeKey(spaced, "deepslate"));
    }

    @Test
    void variantsAreOptIn() {
        BattlePassConfig.MissionDef ore = new BattlePassConfig.MissionDef(
                "ore", "d", BattlePassConfig.MissionType.MINE, "DIAMOND_ORE", 1, 0, 0);
        assertFalse(BattlePassConfig.matchesTypeKey(ore, "DEEPSLATE_DIAMOND_ORE"));
        assertTrue(BattlePassConfig.matchesTypeKey(ore, "DEEPSLATE_DIAMOND_ORE", true));
        assertFalse(BattlePassConfig.matchesTypeKey(ore, "DEEPSLATE_BRICKS", true));
    }

    @Test
    void pointsWorldsAndPermissionAreParsed() {
        BattlePassConfig.WeekDef week = BattlePassConfig.parseWeeks(List.of(
                Map.of("unlock-day", 0, "missions", List.of(java.util.Map.ofEntries(
                        Map.entry("id", "nether"),
                        Map.entry("type", "MINE"),
                        Map.entry("material", "NETHERRACK"),
                        Map.entry("target", 32),
                        Map.entry("points", 25),
                        Map.entry("worlds", List.of("world_nether", " World_Nether ")),
                        Map.entry("permission", " chrdkbp.nether "),
                        Map.entry("icon", "netherrack")))))).get(0);
        BattlePassConfig.MissionDef mission = week.missions().get(0);
        assertEquals(25, mission.points());
        assertEquals(List.of("world_nether"), mission.worlds(), "миры без дублей, в нижнем регистре");
        assertEquals("chrdkbp.nether", mission.permission(), "право без пробелов по краям");
        assertEquals("NETHERRACK", mission.icon());
        assertTrue(mission.restrictedWorld());
        assertTrue(mission.restrictedPermission());
    }

    @Test
    void defaultPointsAndFilters() {
        BattlePassConfig.MissionDef mission = BattlePassConfig.parseWeeks(List.of(
                Map.of("unlock-day", 0, "missions", List.of(
                        Map.of("id", "plain", "type", "MINE", "material", "STONE",
                                "target", 1))))).get(0).missions().get(0);
        assertEquals(BattlePassConfig.DEFAULT_POINTS, mission.points());
        assertTrue(mission.worlds().isEmpty());
        assertFalse(mission.restrictedPermission());
    }

    @Test
    void detailedParseExplainsProblems() {
        BattlePassConfig.ParseResult result = BattlePassConfig.parseWeeksDetailed(List.of(
                Map.of("unlock-day", 0, "missions", List.of(
                        Map.of("id", "good", "type", "MINE", "material", "STONE", "target", 1),
                        Map.of("id", "bad_type", "type", "WALK", "target", 1),
                        Map.of("id", "no_target", "type", "MINE", "material", "STONE"),
                        Map.of("id", "good", "type", "MINE", "material", "STONE", "target", 2),
                        Map.of("type", "MINE", "material", "STONE", "target", 2)))));
        assertEquals(1, result.weeks().size());
        assertEquals(1, result.weeks().get(0).missions().size(), "осталось только корректное");
        assertTrue(result.problems().size() >= 4, "каждая проблема названа: " + result.problems());
        assertTrue(result.problems().stream().anyMatch(text -> text.contains("WALK")));
        assertTrue(result.problems().stream().anyMatch(text -> text.contains("уже занят")));
        assertTrue(result.problems().stream().anyMatch(text -> text.contains("без id")));
    }

    @Test
    void seasonEndIsOptionalAndFreezesProgress() {
        long start = season().startEpochMillis();
        BattlePassConfig.Season open = BattlePassConfig.season("S", "2026-10-13", List.of());
        assertFalse(open.hasEnd());
        assertFalse(open.ended(start + 999L * 86_400_000L));
        assertEquals(0, BattlePassConfig.daysUntilEnd(open, start));

        BattlePassConfig.Season ended = BattlePassConfig.season(
                "S", "2026-10-13", "2026-11-13", List.of());
        assertTrue(ended.hasEnd());
        assertFalse(ended.ended(start));
        assertTrue(ended.ended(start + 31L * 86_400_000L));
        assertEquals(31, BattlePassConfig.daysUntilEnd(ended, start));
    }

    @Test
    void seasonEndBeforeStartIsRejected() {
        assertThrows(IllegalStateException.class, () -> BattlePassConfig.season(
                "S", "2026-10-13", "2026-10-01", List.of()));
        assertThrows(IllegalStateException.class, () -> BattlePassConfig.season(
                "S", "2026-10-13", "13.11.2026", List.of()));
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

    /** Задание с премиум-полями: карта собирается изменяемой, чтобы не путать скобки. */
    private static BattlePassConfig.ParseResult parsePremium(Object premiumHours,
                                                             Object premiumProducts) {
        java.util.Map<String, Object> mission = new java.util.LinkedHashMap<>();
        mission.put("id", "prem");
        mission.put("type", "MINE");
        mission.put("material", "STONE");
        mission.put("target", 10);
        mission.put("premium-hours", premiumHours);
        mission.put("premium-products", premiumProducts);
        return BattlePassConfig.parseWeeksDetailed(List.of(
                Map.of("unlock-day", 0, "missions", List.of(mission))));
    }

    @Test
    void premiumRewardsAreParsed() {
        BattlePassConfig.ParseResult result = parsePremium(3,
                List.of("trail-flame", " Trail-Flame ", "NICK-COLOR"));
        BattlePassConfig.MissionDef mission = result.weeks().get(0).missions().get(0);
        assertTrue(mission.hasPremium());
        assertEquals(3, mission.premiumHours());
        assertEquals(List.of("trail-flame", "nick-color"), mission.premiumProducts(),
                "id нормализуются, дубли убраны");
        assertTrue(result.problems().isEmpty(), () -> result.problems().toString());
    }

    @Test
    void premiumRewardsTolerateTypos() {
        BattlePassConfig.ParseResult result = parsePremium(99_999, List.of("Плохой Id", "ok-id"));
        BattlePassConfig.MissionDef mission = result.weeks().get(0).missions().get(0);
        assertEquals(0, mission.premiumHours(), "слишком большой срок отброшен");
        assertEquals(List.of("ok-id"), mission.premiumProducts(), "кривой id пропущен");
        assertTrue(result.problems().stream().anyMatch(text -> text.contains("premium-hours")));
        assertTrue(result.problems().stream().anyMatch(text -> text.contains("premium-products")));
    }

    @Test
    void premiumProductListIsCapped() {
        BattlePassConfig.ParseResult result = parsePremium(0,
                List.of("a1", "b2", "c3", "d4", "e5", "f6", "g7"));
        assertEquals(BattlePassConfig.MAX_PREMIUM_PRODUCTS,
                result.weeks().get(0).missions().get(0).premiumProducts().size());
        assertTrue(result.problems().stream().anyMatch(text -> text.contains("больше")));
    }

    @Test
    void nothingToSellWhenPremiumMissing() {
        BattlePassConfig.MissionDef plain = BattlePassConfig.parseWeeks(List.of(
                Map.of("unlock-day", 0, "missions", List.of(
                        Map.of("id", "plain", "type", "MINE", "material", "STONE", "target", 1)))))
                .get(0).missions().get(0);
        assertFalse(plain.hasPremium());
        assertTrue(plain.premiumProducts().isEmpty());
    }
}
