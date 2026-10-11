package ru.cherdakmd.chrdkenchants;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Фильтры применимости, взвешенный выбор, уровни. Толерантность к мусору кодека. */
class EnchantRollerTest {

    private static final List<EnchantDef> ALL = EnchantRegistry.all();

    @Test
    void applicabilityRespectsItemLoreOfA() {
        Map<String, Integer> none = Map.of();
        List<EnchantDef> sword = EnchantRoller.applicable(ALL, "DIAMOND_SWORD", none, 3, 1);
        assertTrue(sword.stream().allMatch(def -> def.supports().contains(ItemGroup.SWORD)
                || def.supports().contains(ItemGroup.AXE)));
        assertTrue(sword.stream().noneMatch(def -> def.id().equals("autosmelt")));
        assertEquals(0, EnchantRoller.applicable(ALL, "APPLE", none, 3, 1).size());
    }

    @Test
    void limitsAndConflicts() {
        List<EnchantDef> all = EnchantRegistry.all();
        // конфликт пары phoenix/laststand (обе "savior")
        List<EnchantDef> withPhoenix = EnchantRoller.applicable(all, "DIAMOND_CHESTPLATE",
                Map.of("phoenix", 1), 3, 1);
        assertTrue(withPhoenix.stream().noneMatch(def -> def.id().equals("laststand")),
                "второй спаситель на один нагрудник нельзя");
        assertTrue(withPhoenix.stream().noneMatch(def -> def.id().equals("execution")),
                "ближняя чара не ляжет на нагрудник");
        // легендарный лимит: вторая легенда не падает
        assertTrue(withPhoenix.stream().noneMatch(def ->
                def.rarity() == EnchantDef.Rarity.LEGENDARY), "уже есть легенда — помолчка");
        // лимит чар на предмете
        List<EnchantDef> full = EnchantRoller.applicable(all, "IRON_HELMET",
                Map.of("fortified", 1, "seer", 1, "truesight", 1), 3, 1);
        assertEquals(0, full.size());
    }

    @Test
    void weightedPickFavorsCommon() {
        List<EnchantDef> pool = List.of(
                EnchantRegistry.byId("tracer"),   // COMMON 45
                EnchantRegistry.byId("midas"));   // LEGENDARY 8
        Random random = new Random(5);
        int midasPicked = 0;
        for (int i = 0; i < 5300; i++) {
            assertNotNull(EnchantRoller.pickWeighted(pool, random));
            if (EnchantRoller.pickWeighted(pool, random).id().equals("midas")) midasPicked++;
        }
        double midasShare = midasPicked / 5300.0;
        assertTrue(midasShare < 0.30, "midas(8/53) не должно выпадать чаще 30%: " + midasShare);
        assertTrue(midasShare > 0.02, "но и не нулём: " + midasShare);
    }

    @Test
    void levelsBehavior() {
        Random random = new Random(11);
        EnchantDef execution = EnchantRegistry.byId("execution"); // max 2
        EnchantDef tracer = EnchantRegistry.byId("tracer");      // max 1
        for (int tier = 1; tier <= 3; tier++) {
            assertEquals(1, EnchantRoller.rollLevel(tracer, tier, random));
        }
        int level2AtTier3 = 0, level2AtTier1 = 0;
        for (int i = 0; i < 2000; i++) {
            if (EnchantRoller.rollLevel(execution, 3, random) == 2) level2AtTier3++;
            if (EnchantRoller.rollLevel(execution, 1, random) == 2) level2AtTier1++;
        }
        assertTrue(level2AtTier3 > level2AtTier1 * 3, "полная стойка даёт уровни чаще");
        assertTrue(level2AtTier1 > 0);
    }

    @Test
    void codecRoundTripAndGarbage() {
        Map<String, Integer> map = Map.of("execution", 2, "lifesteal", 1);
        String encoded = EnchantCodec.encode(map);
        assertEquals("execution:2;lifesteal:1", encoded, "кодек сортирован по id");
        assertEquals(map, EnchantCodec.decode(encoded));
        assertTrue(EnchantCodec.decode(null).isEmpty());
        assertTrue(EnchantCodec.decode("").isEmpty());
        assertEquals(Map.of("x", 3), EnchantCodec.decode("x:3;;trash;1:;y:0;z:99;w:2.5;x:1"),
                "мусор отброшен, дубликат берёт максимум, лимит 10");
        assertEquals("", EnchantCodec.encode(Map.of()));
    }
}
