package ru.cherdakmd.chrdktierforge;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Универсальные аффиксы (волна 17): 15 хороших и 15 плохих, независимых от тира.
 * Проверяем то, на чём держится «огромная комбинация»: размеры пулов, покрытие
 * видов предметов, количество по тирам и детерминированность набора.
 */
class TierAffixesTest {

    private static final Set<ItemKind> ALL_KINDS = EnumSet.allOf(ItemKind.class);

    @Test
    void registryHasFifteenOfEachSign() {
        assertEquals(TierAffixes.GOOD_TOTAL, TierAffixes.good().size());
        assertEquals(TierAffixes.BAD_TOTAL, TierAffixes.bad().size());
        assertEquals(TierAffixes.GOOD_TOTAL + TierAffixes.BAD_TOTAL, TierAffixes.all().size());
        for (TierAffix affix : TierAffixes.good()) {
            assertTrue(affix.good(), "хороший пул не должен содержать дефектов: " + affix.id());
            assertTrue(affix.value() > 0, "бонус должен быть положительным: " + affix.id());
        }
        for (TierAffix affix : TierAffixes.bad()) {
            assertFalse(affix.good(), "плохой пул не должен содержать бонусов: " + affix.id());
            assertTrue(affix.value() < 0, "дефект должен быть отрицательным: " + affix.id());
        }
    }

    @Test
    void idsAreUniqueAndIndexed() {
        Set<String> ids = new HashSet<>();
        Set<String> defIds = new HashSet<>();
        for (TierAffix affix : TierAffixes.all()) {
            assertTrue(ids.add(affix.id()), "повторный id аффикса: " + affix.id());
            assertTrue(defIds.add(affix.asDef().id()),
                    "повторный ключ улучшения: " + affix.asDef().id());
            assertTrue(affix.asDef().id().startsWith("aff_"),
                    "ключ аффикса должен быть в своём пространстве: " + affix.asDef().id());
            assertEquals(affix.value(), affix.asDef().perLevel(), 1e-9);
            assertEquals(affix, TierAffixes.byId(affix.id()));
        }
        assertEquals(TierAffixes.all().size(), ids.size());
        assertNull(TierAffixes.byId("no_such_affix"));
        assertNull(TierAffixes.byId(null));
    }

    @Test
    void everyKindHasCandidates() {
        for (ItemKind kind : ALL_KINDS) {
            assertTrue(TierAffixes.countFor(TierAffix.Polarity.GOOD, kind) >= 2,
                    "нет хороших аффиксов для " + kind);
            assertTrue(TierAffixes.countFor(TierAffix.Polarity.BAD, kind) >= 2,
                    "нет плохих аффиксов для " + kind);
            for (TierAffix affix : TierAffixes.candidates(TierAffix.Polarity.GOOD, kind)) {
                assertTrue(affix.fits(kind), affix.id() + " не подходит " + kind);
            }
        }
        assertTrue(TierAffixes.candidates(TierAffix.Polarity.GOOD, null).isEmpty());
    }

    @Test
    void tierDecidesTheCount() {
        assertEquals(0, TierAffixes.goodCount("cursed", 2));
        assertEquals(2, TierAffixes.badCount("cursed", 2));
        assertEquals(0, TierAffixes.goodCount("weak", 2));
        assertEquals(1, TierAffixes.badCount("weak", 2));
        assertEquals(1, TierAffixes.goodCount("common", 2));
        assertEquals(1, TierAffixes.badCount("common", 2));
        assertEquals(1, TierAffixes.goodCount("fine", 2));
        assertEquals(0, TierAffixes.badCount("fine", 2));
        assertEquals(1, TierAffixes.goodCount("rare", 2));
        assertEquals(2, TierAffixes.goodCount("epic", 2));
        assertEquals(2, TierAffixes.goodCount("legendary", 2));
        assertEquals(0, TierAffixes.badCount("legendary", 2));

        // Конфиг может сузить, но не расширить: потолок 0 отключает знак целиком.
        assertEquals(1, TierAffixes.goodCount("legendary", 1));
        assertEquals(0, TierAffixes.goodCount("legendary", 0));
        assertEquals(0, TierAffixes.badCount("cursed", 0));
        assertEquals(0, TierAffixes.goodCount("no-such-tier", 3));
        assertEquals(0, TierAffixes.badCount(null, 3));
    }

    @Test
    void badAffixesAreAttributeOnly() {
        // Шансовый минус («−5% к дублю руды») никогда бы не сработал: отрицательный
        // шанс — это не штраф, а пустая строка в лоре.
        Set<ForgeUpgrade.Effect> attributes = Set.of(
                ForgeUpgrade.Effect.ATTACK_PERCENT,
                ForgeUpgrade.Effect.ATTACK_SPEED_PERCENT,
                ForgeUpgrade.Effect.RAW_DAMAGE,
                ForgeUpgrade.Effect.REACH,
                ForgeUpgrade.Effect.ATTACK_KNOCKBACK,
                ForgeUpgrade.Effect.ARMOR_PERCENT,
                ForgeUpgrade.Effect.TOUGHNESS_FLAT,
                ForgeUpgrade.Effect.MOVE_SPEED_PERCENT,
                ForgeUpgrade.Effect.MAX_HEALTH,
                ForgeUpgrade.Effect.SNEAK_SPEED,
                ForgeUpgrade.Effect.DIG_SPEED_PERCENT,
                ForgeUpgrade.Effect.MINING_EFFICIENCY,
                ForgeUpgrade.Effect.BLOCK_REACH,
                ForgeUpgrade.Effect.LUCK);
        for (TierAffix affix : TierAffixes.bad()) {
            assertTrue(attributes.contains(affix.effect()),
                    "дефект должен быть атрибутным: " + affix.id() + " → " + affix.effect());
            assertTrue(affix.describe().startsWith("-"),
                    "дефект читается как минус: " + affix.describe());
        }
        for (TierAffix affix : TierAffixes.good()) {
            if (affix.effect() == ForgeUpgrade.Effect.DAMAGE_TAKEN_PERCENT) {
                // «-2.5% входящего урона» — это бонус, минус здесь часть формулировки.
                continue;
            }
            assertFalse(affix.describe().startsWith("-"),
                    "бонус не должен читаться как минус: " + affix.describe());
        }
    }

    @Test
    void forItemIsDeterministicAndKindAware() {
        String uid = "3f9a1c0b7d2e";
        List<TierAffix> first = TierAffixes.forItem(uid, "legendary", ItemKind.SWORD, 2, 2);
        List<TierAffix> second = TierAffixes.forItem(uid, "legendary", ItemKind.SWORD, 2, 2);
        assertEquals(first, second, "набор аффиксов должен быть детерминированным");
        assertEquals(2, first.size(), "легендарное оружие получает два бонуса");
        for (TierAffix affix : first) {
            assertTrue(affix.good(), "у легендарного предмета не бывает дефектов");
            assertTrue(affix.fits(ItemKind.SWORD), affix.id() + " не подходит мечу");
        }

        List<TierAffix> cursed = TierAffixes.forItem(uid, "cursed", ItemKind.PICKAXE, 2, 2);
        assertEquals(2, cursed.size());
        for (TierAffix affix : cursed) {
            assertFalse(affix.good(), "проклятый предмет — только дефекты");
            assertTrue(affix.fits(ItemKind.PICKAXE), affix.id() + " не подходит кирке");
        }

        // Разные предметы — разные наборы: uid и тир входят в seed.
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < 40; index++) {
            String key = TierAffixes.forItem("uid" + index, "epic", ItemKind.CHESTPLATE, 2, 2)
                    .stream().map(TierAffix::id).reduce("", (a, b) -> a + "+" + b);
            seen.add(key);
        }
        assertTrue(seen.size() > 1, "набор должен зависеть от предмета");
    }

    @Test
    void forItemNeedsUidAndKnownTier() {
        assertTrue(TierAffixes.forItem(null, "epic", ItemKind.SWORD, 2, 2).isEmpty());
        assertTrue(TierAffixes.forItem("", "epic", ItemKind.SWORD, 2, 2).isEmpty());
        assertTrue(TierAffixes.forItem("uid", null, ItemKind.SWORD, 2, 2).isEmpty());
        assertTrue(TierAffixes.forItem("uid", "epic", null, 2, 2).isEmpty());
        assertTrue(TierAffixes.forItem("uid", "epic", ItemKind.SWORD, 0, 0).isEmpty());
        assertTrue(TierAffixes.forItem("uid", "legendary", ItemKind.SWORD, 0, 0).isEmpty());
        assertTrue(TierAffixes.forItem("uid", "no-tier", ItemKind.SWORD, 2, 2).isEmpty());
    }

    @Test
    void rollPrefersDistinctEffects() {
        java.util.Random random = new java.util.Random(7);
        List<TierAffix> pair = TierAffixes.roll(TierAffix.Polarity.GOOD, ItemKind.HELMET, 2, random);
        assertEquals(2, pair.size());
        assertNotNull(pair.get(0).effect());
        assertTrue(pair.get(0).effect() != pair.get(1).effect(),
                "два аффикса одного эффекта — это не вариативность: "
                        + pair.get(0).effect());
        assertTrue(pair.stream().allMatch(TierAffix::good));
    }
}
