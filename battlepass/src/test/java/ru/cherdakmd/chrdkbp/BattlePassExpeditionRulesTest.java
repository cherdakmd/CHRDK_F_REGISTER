package ru.cherdakmd.chrdkbp;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BattlePassExpeditionRulesTest {
    BattlePassConfig.Season season() {
        var a = new BattlePassConfig.MissionDef("first", "First", BattlePassConfig.MissionType.MINE, "stone", 32, 6, 8);
        var b = new BattlePassConfig.MissionDef("last", "Last", BattlePassConfig.MissionType.KILL, "zombie", 5, 12, 16);
        return new BattlePassConfig.Season("Test", 1000, List.of(new BattlePassConfig.WeekDef(0, List.of(a)),
                new BattlePassConfig.WeekDef(7, List.of(b))));
    }
    @Test void generatedJournalNamesNeverOverwriteExistingData() {
        assertEquals("точка2", BattlePassExpeditions.freeName("точка", Set.of("точка1", "точка3"), 3));
        assertNull(BattlePassExpeditions.freeName("путь", Set.of("путь1"), 1));
    }
    @Test void everyMainMissionMustBeCompletedAndWaveUnlocked() {
        long now = 1000 + 8 * 86_400_000L;
        assertFalse(BattlePassExpeditionRules.complete(null, Map.of(), now));
        assertFalse(BattlePassExpeditionRules.complete(new BattlePassConfig.Season("Empty", 0, List.of()), Map.of(), now));
        assertFalse(BattlePassExpeditionRules.complete(season(), Map.of("first", 32), now));
        assertFalse(BattlePassExpeditionRules.complete(season(), Map.of("first", 32, "last", 4), now));
        assertFalse(BattlePassExpeditionRules.complete(season(), Map.of("first", 32, "last", 5), 1000));
        assertTrue(BattlePassExpeditionRules.complete(season(), Map.of("first", 32, "last", 5), now));
    }
    @Test void claimedBoostsAndAtlasProgressDoNotGateMainLaneRank() {
        // No claimed flags, boost-bank state or biome counters are required by this rule.
        assertTrue(BattlePassExpeditionRules.complete(season(), Map.of("first", 32, "last", 5), Long.MAX_VALUE));
        assertFalse(BattlePassExpeditionRules.complete(season(), Map.of("atlas-all", Integer.MAX_VALUE), Long.MAX_VALUE));
    }
}
