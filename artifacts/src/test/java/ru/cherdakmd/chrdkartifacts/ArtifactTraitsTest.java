package ru.cherdakmd.chrdkartifacts;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Каталог свойств артефактов (волна 19): 15 ▲ + 15 ▼, знаки величин, зельевые
 * типы и правила броска.
 */
class ArtifactTraitsTest {

    @Test
    void catalogIsFifteenGoodAndFifteenBad() {
        assertEquals(30, ArtifactTraits.size());
        assertEquals(15, ArtifactTraits.good().size());
        assertEquals(15, ArtifactTraits.bad().size());
        assertEquals(30, ArtifactTraits.good().size() + ArtifactTraits.bad().size());
    }

    @Test
    void idsAreUniqueAndLowercase() {
        Set<String> ids = new HashSet<>();
        for (ArtifactTraits.Def def : ArtifactTraits.all()) {
            assertTrue(ids.add(def.id()), "дубликат id " + def.id());
            assertEquals(def.id(), def.id().toLowerCase(Locale.ROOT));
            assertEquals(3, def.maxLevel(), "у свойства три ступени: " + def.id());
            assertNotNull(def.polarity());
            assertNotNull(def.kind());
        }
        assertEquals(30, ids.size());
    }

    @Test
    void goodTraitsArePositiveAndBadAreNegative() {
        for (ArtifactTraits.Def def : ArtifactTraits.good()) {
            if (def.kind() == ArtifactTraits.Kind.POTION) {
                assertTrue(def.amplifier(1) >= 0);
                continue;
            }
            for (int level = 1; level <= def.maxLevel(); level++) {
                assertTrue(def.value(level) > 0,
                        "польза должна быть положительной: " + def.id() + " уровень " + level);
            }
        }
        for (ArtifactTraits.Def def : ArtifactTraits.bad()) {
            // Время горения — вред, выраженный положительной величиной: дольше горишь.
            if (def.kind() == ArtifactTraits.Kind.POTION
                    || def.kind() == ArtifactTraits.Kind.BURNING_TIME) {
                continue;
            }
            for (int level = 1; level <= def.maxLevel(); level++) {
                assertTrue(def.value(level) < 0,
                        "вред должен быть отрицательным: " + def.id() + " уровень " + level);
            }
        }
    }

    @Test
    void burningTimeIsBadDespitePositiveValue() {
        ArtifactTraits.Def flame = ArtifactTraits.byId("zharkoe-plamya");
        assertNotNull(flame);
        assertEquals(ArtifactTraits.Polarity.BAD, flame.polarity());
        assertTrue(flame.value(3) > 0, "дольше горишь — плюс в атрибуте, минус для игрока");
        assertEquals(ArtifactTraits.Kind.BURNING_TIME, flame.kind());
    }

    @Test
    void potionTraitsUseKnownEffectNames() {
        int potions = 0;
        for (ArtifactTraits.Def def : ArtifactTraits.all()) {
            if (def.kind() != ArtifactTraits.Kind.POTION) {
                assertNull(def.potionType(), "атрибутное свойство не должно ссылаться на зелье");
                continue;
            }
            potions++;
            assertNotNull(def.potionType(), def.id());
            assertTrue(Potions.isKnown(def.potionType()),
                    "неизвестный тип эффекта: " + def.potionType());
            assertTrue(def.amplifier(3) <= 1, "не выше II уровня: " + def.id());
        }
        assertEquals(8, potions, "по четыре зельевых свойства с каждой стороны");
    }

    @Test
    void attributeScaleGrowsWithLevel() {
        for (ArtifactTraits.Def def : ArtifactTraits.all()) {
            if (def.kind() == ArtifactTraits.Kind.POTION) {
                continue;
            }
            double previous = def.value(1);
            for (int level = 2; level <= def.maxLevel(); level++) {
                assertTrue(Math.abs(def.value(level)) >= Math.abs(previous),
                        "величина не должна убывать: " + def.id());
                previous = def.value(level);
            }
        }
    }

    @Test
    void rollRespectsChanceLimitsAndCap() {
        assertTrue(ArtifactTraits.roll(ArtifactDef.Side.GOOD, new Random(1L), 0, 2, 3, 0)
                .isEmpty(), "шанс 0 — предмет чистый");
        assertTrue(ArtifactTraits.roll(ArtifactDef.Side.GOOD, new Random(1L), 100, 0, 3, 0)
                .isEmpty(), "max-per-item 0 — свойств нет");

        for (long seed = 0; seed < 100; seed++) {
            List<ArtifactTraits.Roll> rolls = ArtifactTraits.roll(ArtifactDef.Side.GOOD,
                    new Random(seed), 100, 3, 3, 0);
            assertFalse(rolls.isEmpty(), "100% шанс обязан выдать свойство");
            assertTrue(rolls.size() <= 3, "не больше трёх свойств на предмет");
            Set<String> ids = new HashSet<>();
            for (ArtifactTraits.Roll roll : rolls) {
                assertTrue(ids.add(roll.id()), "свойства не повторяются");
                assertTrue(roll.level() >= 1 && roll.level() <= 3, "уровень I..III");
            }
        }
    }

    @Test
    void levelCapIsHonoured() {
        for (long seed = 0; seed < 100; seed++) {
            for (ArtifactTraits.Roll roll : ArtifactTraits.roll(ArtifactDef.Side.CURSED,
                    new Random(seed), 100, 2, 1, 0)) {
                assertEquals(1, roll.level(), "потолок уровня 1 — только I");
            }
        }
    }

    @Test
    void sideChoosesPolarityAndCrossFlipsIt() {
        for (long seed = 0; seed < 100; seed++) {
            for (ArtifactTraits.Roll roll : ArtifactTraits.roll(ArtifactDef.Side.GOOD,
                    new Random(seed), 100, 2, 3, 0)) {
                assertEquals(ArtifactTraits.Polarity.GOOD,
                        ArtifactTraits.byId(roll.id()).polarity(), "светлому — польза");
            }
            for (ArtifactTraits.Roll roll : ArtifactTraits.roll(ArtifactDef.Side.CURSED,
                    new Random(seed), 100, 2, 3, 100)) {
                assertEquals(ArtifactTraits.Polarity.GOOD,
                        ArtifactTraits.byId(roll.id()).polarity(),
                        "100% cross-chance на проклятом — только польза");
            }
        }
    }

    @Test
    void rollIsDeterministic() {
        List<ArtifactTraits.Roll> first = ArtifactTraits.roll(ArtifactDef.Side.CURSED,
                new Random(77L), 100, 3, 3, 30);
        List<ArtifactTraits.Roll> second = ArtifactTraits.roll(ArtifactDef.Side.CURSED,
                new Random(77L), 100, 3, 3, 30);
        assertEquals(first, second);
        assertFalse(first.isEmpty());
    }

    @Test
    void resolveByIdIsCaseInsensitive() {
        assertNotNull(ArtifactTraits.byId("silnyj-zahvat"));
        assertNotNull(ArtifactTraits.byId("  SILNYJ-ZAHVAT "));
        assertNull(ArtifactTraits.byId("no-such"));
        assertNull(ArtifactTraits.byId(null));
        assertEquals(3, ArtifactTraits.hardMaxLevel());
    }
}
