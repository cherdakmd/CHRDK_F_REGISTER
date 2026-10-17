package ru.cherdakmd.chrdkartifacts;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ArtifactLooterTest {

    @Test
    void sideWeightIsRespected() {
        Random seedA = new Random(7);
        for (int i = 0; i < 500; i++) {
            ArtifactDef def = ArtifactLooter.roll(100, seedA);
            assertNotNull(def);
            assertEquals(ArtifactDef.Side.GOOD, def.side(), "вес 100 — только светлые");
        }
        Random seedB = new Random(7);
        for (int i = 0; i < 500; i++) {
            ArtifactDef def = ArtifactLooter.roll(0, seedB);
            assertNotNull(def);
            assertEquals(ArtifactDef.Side.CURSED, def.side(), "вес 0 — только проклятые");
        }
    }

    @Test
    void weightZeroSidesClampSafely() {
        ArtifactDef def = ArtifactLooter.roll(150, new Random(1));
        assertNotNull(def, "кламп >100 работает (рассматриваем как 100)");
    }

    @Test
    void wholeSideIsReachable() {
        Random random = new Random(42);
        Set<String> good = new HashSet<>();
        Set<String> cursed = new HashSet<>();
        for (int i = 0; i < 20_000; i++) {
            ArtifactDef def = ArtifactLooter.roll(50, random);
            (def.side() == ArtifactDef.Side.GOOD ? good : cursed).add(def.id());
        }
        assertEquals(ArtifactRegistry.EXPECTED_GOOD, good.size(),
                "со временем находятся все светлые артефакты");
        assertEquals(ArtifactRegistry.EXPECTED_CURSED, cursed.size(),
                "со временем находятся все проклятые артефакты");
    }
}
