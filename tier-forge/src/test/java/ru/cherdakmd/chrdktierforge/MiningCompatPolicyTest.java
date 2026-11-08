package ru.cherdakmd.chrdktierforge;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.5.2: тир-аддон не выдаёт «Карьер» инструменту с чарами {@code megamine}.
 * Проверяем чистое правило и то, что бросок свойств действительно его уважает.
 */
final class MiningCompatPolicyTest {

    @Test
    void onlyMegaMiningIdsAreRecognised() {
        assertTrue(MiningCompatPolicy.isMegaMining("veinminer"));
        assertTrue(MiningCompatPolicy.isMegaMining("excavator"));
        assertTrue(MiningCompatPolicy.isMegaMining(" Excavator "));
        assertFalse(MiningCompatPolicy.isMegaMining("midas"));
        assertFalse(MiningCompatPolicy.isMegaMining("autosmelt"));
        assertFalse(MiningCompatPolicy.isMegaMining(null));

        assertTrue(MiningCompatPolicy.hasMegaMining(Map.of("excavator", 2, "autosmelt", 1)));
        assertFalse(MiningCompatPolicy.hasMegaMining(Map.of("autosmelt", 1)));
        assertFalse(MiningCompatPolicy.hasMegaMining(Map.of()));
        assertFalse(MiningCompatPolicy.hasMegaMining(null));
    }

    @Test
    void forbiddenIsExactlyQuarryAndOnlyWithMegaMining() {
        List<TierProperty> banned = MiningCompatPolicy.forbidden(true);
        assertEquals(1, banned.size());
        assertEquals("quarry", banned.getFirst().id());
        assertEquals("Карьер", banned.getFirst().name());
        assertEquals(ForgeUpgrade.Effect.ZONE_BREAK, banned.getFirst().effect());
        assertEquals(ForgeUpgrade.Target.TOOL, banned.getFirst().target());
        assertTrue(MiningCompatPolicy.forbidden(false).isEmpty());
    }

    @Test
    void withoutDropsQuarryAndKeepsOtherProperties() {
        TierProperty quarry = TierProperties.byId("quarry");
        TierProperty motherlode = TierProperties.byId("motherlode");
        assertNotNull(quarry);
        assertNotNull(motherlode);
        List<TierProperty> stored = List.of(motherlode, quarry);
        List<TierProperty> banned = MiningCompatPolicy.forbidden(true);

        assertEquals(List.of(motherlode), MiningCompatPolicy.without(stored, banned));
        assertEquals(stored, MiningCompatPolicy.without(stored, List.of()));
        assertTrue(MiningCompatPolicy.without(List.of(), banned).isEmpty());
        assertTrue(MiningCompatPolicy.without(null, banned).isEmpty());
    }

    @Test
    void rollNeverPicksQuarryWhenItIsExcluded() {
        TierProperty quarry = TierProperties.byId("quarry");
        List<TierProperty> banned = MiningCompatPolicy.forbidden(true);
        Random random = new Random(20261103L);
        for (int i = 0; i < 300; i++) {
            List<TierProperty> picked = TierProperties.roll(
                    "legendary", ItemKind.PICKAXE, 3, random, banned);
            assertFalse(picked.contains(quarry), "«Карьер» не должен выпадать при чаре megamine");
        }

        // Без запрета «Карьер» иногда выпадает — иначе проверка выше была бы пустой.
        boolean seen = false;
        for (int i = 0; i < 300 && !seen; i++) {
            seen = TierProperties.roll("legendary", ItemKind.PICKAXE, 3, random).contains(quarry);
        }
        assertTrue(seen, "легендарная кирка должна уметь получить «Карьер»");
    }

    @Test
    void savedPropertySetIsCleanedWithoutLosingInsertionOrder() {
        TierProperty quarry = TierProperties.byId("quarry");
        TierProperty motherlode = TierProperties.byId("motherlode");
        TierProperty breaker = TierProperties.byId("world_breaker");
        List<TierProperty> stored = new ArrayList<>(List.of(breaker, quarry, motherlode));

        List<TierProperty> cleaned = MiningCompatPolicy.without(stored,
                MiningCompatPolicy.forbidden(true));
        assertEquals(List.of(breaker, motherlode), cleaned);
        assertEquals("world_breaker,quarry,motherlode", TierProperties.serialize(stored));
        assertEquals("world_breaker,motherlode", TierProperties.serialize(cleaned));
    }

    @Test
    void orderOfCustomEnchantsDoesNotChangeTheDecision() {
        Map<String, Integer> reversed = new LinkedHashMap<>();
        reversed.put("midas", 1);
        reversed.put("excavator", 2);
        assertTrue(MiningCompatPolicy.hasMegaMining(reversed));
        assertEquals(1, MiningCompatPolicy.forbidden(MiningCompatPolicy.hasMegaMining(reversed)).size());
    }
}
