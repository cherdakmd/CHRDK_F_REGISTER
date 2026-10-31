package ru.cherdakmd.chrdktierforge;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TierShotBonusTest {
    @Test
    void launchSnapshotDoesNotChangeAfterWeaponOrConfigChanges() {
        Map<ForgeUpgrade.Def, Integer> effects = new LinkedHashMap<>();
        effects.put(ForgeUpgrade.byId("sniper"), 4);
        TierShotBonus shot = TierShotBonus.capture(30, effects);
        effects.clear();
        TierShotBonus newWeapon = TierShotBonus.capture(-12, effects);
        assertEquals(21.12, shot.apply(10), 1e-10);
        assertEquals(7.6, newWeapon.apply(10), 1e-10);
        assertEquals(shot, TierShotBonus.decode(shot.encode()));
        assertEquals(21.12, TierShotBonus.decode(shot.encode()).apply(10), 1e-10);
    }

    @Test
    void forgingPropertyAndAffixFractionsAreSummedOnlyOnce() {
        Map<ForgeUpgrade.Def, Integer> effects = new LinkedHashMap<>();
        effects.put(ForgeUpgrade.byId("sniper"), 2);
        effects.put(new ForgeUpgrade.Def("prop_test", "Штраф", ForgeUpgrade.Target.WEAPON,
                ForgeUpgrade.Effect.SNIPE, -8, 1, ""), 1);
        effects.put(new ForgeUpgrade.Def("aff_test", "Бонус", ForgeUpgrade.Target.WEAPON,
                ForgeUpgrade.Effect.SNIPE, 3, 1, ""), 1);
        var shot = TierShotBonus.capture(0, effects);
        assertEquals(0.11, shot.snipeScalar(), 1e-12);
        assertEquals(11.1, shot.apply(10), 1e-12);
    }

    @Test
    void neutralAndPiercingShotsDoNotAcquireOrAccumulateOtherBonuses() {
        var neutral = TierShotBonus.capture(0, Map.of());
        assertEquals(TierShotBonus.NONE, neutral);
        var shot = TierShotBonus.capture(30, Map.of());
        for (int target = 0; target < 20; target++) assertEquals(16, shot.apply(10), 1e-12);
        assertEquals(10, TierShotBonus.decode(neutral.encode()).apply(10), 1e-12);
    }

    @Test
    void corruptOrUnknownPersistentSnapshotFailsClosed() {
        for (String value : new String[]{"", "2;0.6;0", "1;NaN;0", "1;Infinity;0", "1;0;NaN",
                "1;8;0", "1;-4;0", "1;0;8", "1;0;0;extra", "not-a-snapshot", "1;0"}) {
            assertEquals(TierShotBonus.NONE, TierShotBonus.decode(value), value);
        }
        assertEquals(TierShotBonus.NONE, TierShotBonus.decode(null));
        assertEquals(TierShotBonus.NONE, TierShotBonus.decode("x".repeat(101)));
    }

    @Test
    void decoderBoundsCoverAllLegitimateCatalogCombinations() {
        double forge = ForgeUpgrade.ALL.stream().filter(d -> d.effect() == ForgeUpgrade.Effect.SNIPE)
                .mapToDouble(d -> d.perLevel() * d.maxLevel() / 100).sum();
        double property = TierProperties.all().stream().filter(p -> p.effect() == ForgeUpgrade.Effect.SNIPE)
                .mapToDouble(p -> Math.abs(p.value()) / 100).max().orElse(0) * TierProperties.MAX_PER_ITEM;
        double affix = TierAffixes.all().stream().filter(a -> a.effect() == ForgeUpgrade.Effect.SNIPE)
                .mapToDouble(a -> Math.abs(a.value()) / 100).sum();
        assertTrue(forge + property + affix <= 1);
        for (int percent = -50; percent <= 60; percent++) {
            var shot = new TierShotBonus(TierMath.projectileScalar(percent), forge + property + affix);
            assertEquals(shot, TierShotBonus.decode(shot.encode()));
        }
    }
}
