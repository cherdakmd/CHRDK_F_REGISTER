package ru.cherdakmd.chrdkskills;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class ProgressRatingTest {
    @TempDir Path folder;
    private static final SkillCurve.Params CURVE = SkillCurve.Params.DEFAULT;

    @Test void formulaUsesAllSkillsRebirthsAndBonusOnce() {
        var xp = Map.of(SkillType.COMBAT, SkillCurve.totalForLevel(21, CURVE),
                SkillType.FARMING, SkillCurve.totalForLevel(11, CURVE));
        var result = ProgressRating.calculate(new SkillStore.ProgressSnapshot(xp, 2, 7), CURVE);
        assertEquals(40, result.totalLevel()); // восемь остальных навыков на начальном уровне
        assertEquals(55, result.score()); // 40/5 + 2*20 + 7
        assertEquals(2, result.rebirths());
        assertEquals(7, result.bonusPoints());
    }

    @Test void noDataIsUnknownAndStoredZeroXpIsNotMissing() {
        var store = new SkillStore(folder, Logger.getAnonymousLogger());
        assertTrue(store.progressSnapshot("missing").isEmpty());
        store.setXp("known", SkillType.MINING, 0);
        assertEquals(2, ProgressRating.calculate(store.progressSnapshot("known").orElseThrow(), CURVE).score());
    }

    @Test void allocationAndRespecDoNotChangeRatingAndSnapshotSurvivesRestart() {
        var store = new SkillStore(folder, Logger.getAnonymousLogger());
        store.setXp("player", SkillType.COMBAT, SkillCurve.totalForLevel(21, CURVE));
        store.addRebirth("player");
        store.addBonusPoints("player", 3);
        var snapshot = store.progressSnapshot("player").orElseThrow();
        long initial = ProgressRating.calculate(snapshot, CURVE).score();
        store.addAttribute("player", PlayerAttribute.STAMINA, 4);
        store.setBranch("player", BuildBranch.PRIEST, 6);
        assertEquals(initial, ProgressRating.calculate(store.progressSnapshot("player").orElseThrow(), CURVE).score());
        store.resetAttributes("player");
        store.resetBranches("player");
        store.flush();
        var restored = new SkillStore(folder, Logger.getAnonymousLogger());
        restored.load();
        assertEquals(initial, ProgressRating.calculate(restored.progressSnapshot("player").orElseThrow(), CURVE).score());
        store.setXp("player", SkillType.COMBAT, 0);
        assertEquals(initial, ProgressRating.calculate(snapshot, CURVE).score(), "снимок не меняется после записи");
        assertThrows(UnsupportedOperationException.class, () -> snapshot.xp().put(SkillType.MINING, 5L));
    }

    @Test void ratingUsesWideArithmeticAndClampsNegativeInputs() {
        var result = ProgressRating.calculate(new SkillStore.ProgressSnapshot(Map.of(), Integer.MAX_VALUE, Integer.MAX_VALUE), CURVE);
        assertEquals(2L + 20L * Integer.MAX_VALUE + Integer.MAX_VALUE, result.score());
        assertEquals(2, ProgressRating.calculate(new SkillStore.ProgressSnapshot(Map.of(), -5, -1), CURVE).score());
    }
}
