package ru.cherdakmd.chrdkgenerator;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Продукция тиров: веса, удача и превращение единиц бункера в предметы. */
class GeneratorDropsTest {

    private static final List<GeneratorDrops.Drop> TIER = List.of(
            new GeneratorDrops.Drop("COBBLESTONE", 8, false),
            new GeneratorDrops.Drop("CLAY_BALL", 2, false),
            new GeneratorDrops.Drop("NETHER_STAR", 1, true));

    @Test void luckBoostsOnlyRareDrops() {
        GeneratorDrops.Drop common = TIER.get(0);
        GeneratorDrops.Drop rare = TIER.get(2);
        assertEquals(8, GeneratorDrops.effectiveWeight(common, 5), "обычное — без изменений");
        assertEquals(1, GeneratorDrops.effectiveWeight(rare, 0), "без удачи — как есть");
        assertEquals(2, GeneratorDrops.effectiveWeight(rare, 5), "удача 5: +50% к редкому");
        assertEquals(1, GeneratorDrops.effectiveWeight(rare, 1), "удача 1: вес 1×1.1 округляется вниз");
    }

    @Test void rollFollowsWeightsAndLuck() {
        Random seeded = new Random(42);
        int rare = 0;
        for (int i = 0; i < 10_000; i++) {
            if ("NETHER_STAR".equals(GeneratorDrops.roll(TIER, 5, seeded))) {
                rare++;
            }
        }
        // Удача 5: вес редкого 2 из 8+2+2=12 ≈ 16.7%; без удачи — 1/11 ≈ 9.1%.
        assertTrue(rare > 1_200 && rare < 2_100, "редкость утяжелена, но не доминирует: " + rare);
    }

    @Test void materializeCountsUnits() {
        Map<String, Integer> items = GeneratorDrops.materialize(25, TIER, 0, new Random(7));
        int total = items.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(25, total, "ровно по одному предмету на единицу бункера");
        assertTrue(items.containsKey("COBBLESTONE"));
        assertTrue(items.keySet().stream().allMatch(TIER.stream()
                .map(GeneratorDrops.Drop::material).toList()::contains));
    }
}
