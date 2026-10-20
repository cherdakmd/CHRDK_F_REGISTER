package ru.cherdakmd.chrdktierforge;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Случайные зачарования тир-предметов (волна 17): пул по виду предмета, рост
 * шанса и уровня по лестнице, взаимоисключающие чары и честный ноль.
 */
class TierEnchantsTest {

    private static final Set<String> PROTECTION_FAMILY = Set.of(
            "protection", "fire_protection", "blast_protection", "projectile_protection");
    private static final Set<String> DAMAGE_FAMILY = Set.of(
            "sharpness", "smite", "bane_of_arthropods");
    private static final Set<String> TOOL_BONUS = Set.of("fortune", "silk_touch");

    @Test
    void everyKindHasAPool() {
        for (ItemKind kind : EnumSet.allOf(ItemKind.class)) {
            List<TierEnchants.Roll> pool = TierEnchants.pool(kind);
            assertFalse(pool.isEmpty(), "пустой пул зачарований для " + kind);
            Set<String> keys = new HashSet<>();
            for (TierEnchants.Roll roll : pool) {
                assertTrue(keys.add(roll.key()), "дубль в пуле: " + roll.key());
                assertTrue(roll.key().equals(roll.key().toLowerCase(java.util.Locale.ROOT)),
                        "ключ реестра пишется в нижнем регистре: " + roll.key());
                assertTrue(roll.level() >= 1, "максимальный уровень положителен");
            }
            assertTrue(keys.contains("unbreaking"), kind + ": прочность полезна всем");
        }
        assertTrue(TierEnchants.pool(null).isEmpty());
    }

    @Test
    void poolsMatchWhatVanillaAllows() {
        Set<String> bow = keySet(TierEnchants.pool(ItemKind.BOW));
        Set<String> crossbow = keySet(TierEnchants.pool(ItemKind.CROSSBOW));
        Set<String> axe = keySet(TierEnchants.pool(ItemKind.AXE));
        Set<String> helmet = keySet(TierEnchants.pool(ItemKind.HELMET));
        Set<String> chest = keySet(TierEnchants.pool(ItemKind.CHESTPLATE));
        Set<String> boots = keySet(TierEnchants.pool(ItemKind.BOOTS));

        assertTrue(bow.contains("power"));
        assertFalse(crossbow.contains("power"), "сила недоступна арбалету");
        assertFalse(crossbow.contains("flame"), "пламя недоступно арбалету");
        assertTrue(crossbow.contains("quick_charge"));
        assertTrue(crossbow.contains("piercing"));

        assertTrue(axe.contains("efficiency"), "топор — ещё и инструмент");
        assertFalse(axe.contains("looting"), "добыча — только мечу");
        assertFalse(axe.contains("sweeping_edge"), "размах — только мечу");

        assertTrue(helmet.contains("respiration"));
        assertTrue(helmet.contains("aqua_affinity"));
        assertFalse(chest.contains("respiration"), "дыхание — только шлему");
        assertFalse(helmet.contains("feather_falling"), "падение — только ботинкам");
        assertTrue(boots.contains("feather_falling"));
        assertTrue(boots.contains("depth_strider"));
    }

    @Test
    void chanceGrowsWithTier() {
        assertEquals(20, TierEnchants.chanceFor(0, 20));
        assertEquals(20 + TierEnchants.PER_TIER_BONUS, TierEnchants.chanceFor(1, 20));
        assertEquals(68, TierEnchants.chanceFor(6, 20));
        assertEquals(100, TierEnchants.chanceFor(6, 100), "шанс не превышает 100%");
        assertEquals(0, TierEnchants.chanceFor(0, 0));
        assertEquals(0, TierEnchants.chanceFor(6, 0), "ноль отключает зачарования на всех тирах");
    }

    @Test
    void levelCapFollowsTheLadder() {
        assertEquals(1, TierEnchants.levelCap(0, 3));
        assertEquals(1, TierEnchants.levelCap(1, 3));
        assertEquals(2, TierEnchants.levelCap(2, 3));
        assertEquals(2, TierEnchants.levelCap(3, 3));
        assertEquals(3, TierEnchants.levelCap(4, 3));
        assertEquals(3, TierEnchants.levelCap(6, 3));
        assertEquals(1, TierEnchants.levelCap(6, 1), "конфиг может опустить потолок");
        assertEquals(1, TierEnchants.levelCap(6, 0), "потолок не бывает ниже I");
    }

    @Test
    void chanceZeroGivesNothingAndHundredAlwaysGivesOne() {
        Random random = new Random(11);
        for (ItemKind kind : EnumSet.allOf(ItemKind.class)) {
            assertTrue(TierEnchants.roll(kind, 6, random, 0, 2, 3).isEmpty(),
                    "шанс 0% не должен ничего выдавать: " + kind);
            List<TierEnchants.Roll> rolls = TierEnchants.roll(kind, 6, random, 100, 1, 3);
            assertEquals(1, rolls.size(), "шанс 100% и max 1 = ровно одно: " + kind);
            assertTrue(rolls.get(0).level() >= 1);
        }
        assertTrue(TierEnchants.roll(null, 3, random, 100, 2, 3).isEmpty());
        assertTrue(TierEnchants.roll(ItemKind.SWORD, 3, random, 100, 0, 3).isEmpty());
        assertTrue(TierEnchants.roll(ItemKind.SWORD, 3, random, 100, 2, 0).isEmpty());
    }

    @Test
    void rollIsDeterministicForTheSameSeed() {
        List<TierEnchants.Roll> first = TierEnchants.roll(ItemKind.CHESTPLATE, 5,
                new Random(42), 100, 2, 3);
        List<TierEnchants.Roll> second = TierEnchants.roll(ItemKind.CHESTPLATE, 5,
                new Random(42), 100, 2, 3);
        assertEquals(first, second, "один seed — один результат (предпросмотр честен)");
    }

    @Test
    void exclusiveEnchantmentsNeverComeTogether() {
        Random random = new Random(3);
        Map<String, Integer> seen = new HashMap<>();
        for (int attempt = 0; attempt < 400; attempt++) {
            List<TierEnchants.Roll> armor = TierEnchants.roll(ItemKind.LEGGINGS, 6,
                    random, 100, 3, 3);
            assertTrue(countIn(armor, PROTECTION_FAMILY) <= 1,
                    "две защиты сразу: " + keys(armor));
            List<TierEnchants.Roll> tool = TierEnchants.roll(ItemKind.PICKAXE, 6,
                    random, 100, 3, 3);
            assertTrue(countIn(tool, TOOL_BONUS) <= 1,
                    "удача и шёлк одновременно: " + keys(tool));
            List<TierEnchants.Roll> sword = TierEnchants.roll(ItemKind.SWORD, 6,
                    random, 100, 3, 3);
            assertTrue(countIn(sword, DAMAGE_FAMILY) <= 1,
                    "острота и кара одновременно: " + keys(sword));
            armor.forEach(roll -> seen.merge(roll.key(), 1, Integer::sum));
        }
        assertTrue(seen.size() >= 4, "за 400 бросков должен раскрыться почти весь пул брони: "
                + seen.keySet());
    }

    @Test
    void levelsStayWithinCap() {
        Random random = new Random(19);
        for (int attempt = 0; attempt < 300; attempt++) {
            List<TierEnchants.Roll> rolls = TierEnchants.roll(ItemKind.SWORD, 2, random, 100, 2, 2);
            for (TierEnchants.Roll roll : rolls) {
                assertTrue(roll.level() >= 1 && roll.level() <= 2,
                        "уровень вне потолка тира: " + roll.level());
            }
        }
    }

    @Test
    void displayNameIsReadable() {
        assertEquals("Fire Aspect", TierEnchants.displayName("fire_aspect"));
        assertEquals("Unbreaking", TierEnchants.displayName("unbreaking"));
        assertEquals("", TierEnchants.displayName(""));
        assertEquals("", TierEnchants.displayName(null));
    }

    private static int countIn(List<TierEnchants.Roll> rolls, Set<String> keys) {
        return (int) rolls.stream().filter(roll -> keys.contains(roll.key())).count();
    }

    private static Set<String> keySet(List<TierEnchants.Roll> rolls) {
        return rolls.stream().map(TierEnchants.Roll::key)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static String keys(List<TierEnchants.Roll> rolls) {
        return rolls.stream().map(TierEnchants.Roll::key).toList().toString();
    }
}
