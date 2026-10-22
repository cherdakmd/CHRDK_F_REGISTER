package ru.cherdakmd.chrdktierforge;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Пул случайных кличек кованных предметов (1.4.55): 15+10+10=35, без пустых и
 * дублей, выдача строго из пула вида предмета, топор идёт в оружейный пул.
 * С 1.4.142 к ним добавились 10 имён утилиты — всего 45.
 */
class ForgeNamePoolTest {

    private static ForgeNamePool defaults() {
        return new ForgeNamePool(ForgeNamePool.DEFAULT_WEAPONS, ForgeNamePool.DEFAULT_ARMOR,
                ForgeNamePool.DEFAULT_TOOLS, ForgeNamePool.DEFAULT_UTILITIES);
    }

    @Test
    void defaultPoolsTotalFortyFiveDistinctNames() {
        assertEquals(15, ForgeNamePool.DEFAULT_WEAPONS.size());
        assertEquals(10, ForgeNamePool.DEFAULT_ARMOR.size());
        assertEquals(10, ForgeNamePool.DEFAULT_TOOLS.size());
        assertEquals(10, ForgeNamePool.DEFAULT_UTILITIES.size(),
                "утилита кличется своим пулом (1.4.142)");
        assertEquals(ForgeNamePool.DEFAULT_UTILITIES, defaults().utilities());
        assertEquals(45, defaults().totalCount());
        Set<String> seen = new HashSet<>();
        for (String name : concatAll()) {
            assertTrue(seen.add(name), "кличка-дубликат: " + name);
            assertFalse(name.isBlank(), "пустая кличка в пуле");
        }
    }

    @Test
    void pickComesFromMatchingPoolOnly() {
        ForgeNamePool pool = defaults();
        Random random = new Random(42);
        for (int i = 0; i < 100; i++) {
            assertTrue(ForgeNamePool.DEFAULT_WEAPONS.contains(pool.pick(ItemKind.SWORD, random)));
            assertTrue(ForgeNamePool.DEFAULT_ARMOR.contains(pool.pick(ItemKind.CHESTPLATE, random)));
            assertTrue(ForgeNamePool.DEFAULT_TOOLS.contains(pool.pick(ItemKind.PICKAXE, random)));
        }
        // топор кличется оружейным именем (боевой образ сильнее инструментального)
        for (int i = 0; i < 50; i++) {
            assertTrue(ForgeNamePool.DEFAULT_WEAPONS.contains(pool.pick(ItemKind.AXE, random)));
        }
        // утилита — своим пулом (1.4.142)
        for (int i = 0; i < 50; i++) {
            assertTrue(ForgeNamePool.DEFAULT_UTILITIES.contains(pool.pick(ItemKind.UTILITY, random)));
        }
        // неисследуемый вид — без клички
        assertNull(pool.pick(null, random));
    }

    @Test
    void utilityPoolIsOptional() {
        // Трёхаргументный конструктор утилиту не кличит: пул включается явно.
        ForgeNamePool без = new ForgeNamePool(ForgeNamePool.DEFAULT_WEAPONS,
                ForgeNamePool.DEFAULT_ARMOR, ForgeNamePool.DEFAULT_TOOLS);
        assertNull(без.pick(ItemKind.UTILITY, new Random(3)));
    }

    @Test
    void emptyPoolsFallBackGracefully() {
        ForgeNamePool empty = new ForgeNamePool(java.util.List.of(), java.util.List.of(),
                java.util.List.of());
        assertEquals(0, empty.totalCount());
        assertNull(empty.pick(ItemKind.SWORD, new Random(1)));
    }

    @Test
    void customPoolsAreUsedVerbatim() {
        ForgeNamePool custom = new ForgeNamePool(java.util.List.of("Тестовый меч-имя"),
                java.util.List.of("Щит-имя"), java.util.List.of("Кирка-имя"));
        assertEquals("Тестовый меч-имя", custom.pick(ItemKind.TRIDENT, new Random(7)));
        assertEquals("Щит-имя", custom.pick(ItemKind.BOOTS, new Random(7)));
        assertEquals("Кирка-имя", custom.pick(ItemKind.HOE, new Random(7)));
    }

    private static java.util.List<String> concatAll() {
        java.util.List<String> all = new java.util.ArrayList<>(ForgeNamePool.DEFAULT_WEAPONS);
        all.addAll(ForgeNamePool.DEFAULT_ARMOR);
        all.addAll(ForgeNamePool.DEFAULT_TOOLS);
        all.addAll(ForgeNamePool.DEFAULT_UTILITIES);
        return all;
    }
}
