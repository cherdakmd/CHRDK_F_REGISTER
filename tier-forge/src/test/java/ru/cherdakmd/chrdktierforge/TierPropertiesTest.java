package ru.cherdakmd.chrdktierforge;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Уникальные свойства тиров (1.4.118): по 15 на каждый из семи тиров, всего 105.
 * Проверяем то, на чём держится выдача: уникальность id, покрытие видов
 * предметов, честный бросок без дублей и хранение одной строкой в PDC.
 */
class TierPropertiesTest {

    /** Все виды предметов, на которые накатывается тир. */
    private static final Set<ItemKind> ALL_KINDS = EnumSet.allOf(ItemKind.class);

    @Test
    void everyTierHasExactlyFifteenProperties() {
        assertEquals(TierDef.LADDER.size() * TierProperties.PER_TIER, TierProperties.all().size());
        for (String tier : TierDef.LADDER) {
            List<TierProperty> pool = TierProperties.byTier(tier);
            assertEquals(TierProperties.PER_TIER, pool.size(), tier + " — ровно 15 свойств");
            for (TierProperty property : pool) {
                assertEquals(tier, property.tier(), property.id() + " не должен утекать в чужой тир");
            }
        }
    }

    @Test
    void idsAreUniqueAndIndexed() {
        Set<String> ids = new HashSet<>();
        Set<String> defIds = new HashSet<>();
        for (TierProperty property : TierProperties.all()) {
            assertTrue(ids.add(property.id()), "повторный id свойства: " + property.id());
            assertTrue(defIds.add(property.asDef().id()),
                    "повторный ключ улучшения: " + property.asDef().id());
            assertTrue(property.asDef().id().startsWith("prop_"),
                    "ключ свойства должен быть в своём пространстве: " + property.asDef().id());
            assertEquals(property.value(), property.asDef().perLevel(), 1e-9,
                    "величина свойства живёт в perLevel определения");
            assertNotNull(TierProperties.byId(property.id()));
            assertEquals(property, TierProperties.byId(property.id()));
        }
        assertEquals(TierProperties.all().size(), ids.size());
        assertNull(TierProperties.byId("no_such_property"));
        assertNull(TierProperties.byId(null));
    }

    @Test
    void everyPoolHasChoicesForEveryKind() {
        for (String tier : TierDef.LADDER) {
            // 5 оружий / 5 брони / 4 инструмента (+ иногда «любое»): у каждого
            // вида всегда есть минимум три варианта, иначе выбор был бы фиктивным.
            for (ForgeUpgrade.Target target : List.of(ForgeUpgrade.Target.WEAPON,
                    ForgeUpgrade.Target.ARMOR, ForgeUpgrade.Target.TOOL)) {
                int count = 0;
                for (TierProperty property : TierProperties.byTier(tier)) {
                    if (property.target() == target) {
                        count++;
                    }
                }
                assertTrue(count >= 3, tier + "/" + target + " — слишком мало свойств: " + count);
            }
            for (ItemKind kind : ALL_KINDS) {
                if (kind == ItemKind.UTILITY) {
                    // 1.4.142: у утилиты нет своего пула — ей достаётся ровно
                    // одно общее свойство тира (см. utilityGetsTheSharedProperty).
                    continue;
                }
                assertTrue(TierProperties.countFor(tier, kind) >= 3,
                        tier + "/" + kind + " — не из чего выбирать");
            }
        }
        assertEquals(0, TierProperties.countFor("no_such_tier", ItemKind.SWORD));
        assertTrue(TierProperties.byTier(null).isEmpty());
    }

    @Test
    void utilityGetsTheSharedPropertyOfItsTier() {
        // 1.4.142: щит, рог, фонарь и тотем не бьют, не копают и не носятся
        // бронёй — им положено ровно одно общее свойство тира (цель ANY).
        for (String tier : TierDef.LADDER) {
            List<TierProperty> candidates = TierProperties.candidates(tier, ItemKind.UTILITY);
            assertEquals(1, candidates.size(), tier + " — у утилиты должно быть одно свойство");
            assertEquals(ForgeUpgrade.Target.ANY, candidates.get(0).target(),
                    tier + " — утилите досталось чужое свойство: " + candidates.get(0).id());
            assertEquals(candidates, TierProperties.roll(tier, ItemKind.UTILITY,
                    TierProperties.MAX_PER_ITEM, new Random(5L)),
                    tier + " — больше общего пула не выдать");
        }
    }

    @Test
    void rollReturnsOnlyFittingPropertiesWithoutDuplicates() {
        Random random = new Random(20261020L);
        for (String tier : TierDef.LADDER) {
            for (ItemKind kind : ALL_KINDS) {
                for (int count = 1; count <= TierProperties.MAX_PER_ITEM; count++) {
                    List<TierProperty> rolled = TierProperties.roll(tier, kind, count, random);
                    int expected = Math.min(count, TierProperties.countFor(tier, kind));
                    assertEquals(expected, rolled.size(), tier + "/" + kind
                            + " — просили " + count + ", а в пуле "
                            + TierProperties.countFor(tier, kind));
                    assertEquals(rolled.size(), new HashSet<>(rolled).size(),
                            "дубль в броске: " + rolled);
                    for (TierProperty property : rolled) {
                        assertEquals(tier, property.tier());
                        assertTrue(property.fits(kind), property.id() + " не подходит " + kind);
                    }
                }
            }
        }
    }

    @Test
    void rollClampsToPoolAndRespectsExclude() {
        List<TierProperty> pool = TierProperties.candidates("legendary", ItemKind.SWORD);
        List<TierProperty> rolled = TierProperties.roll("legendary", ItemKind.SWORD,
                pool.size() + 10, new Random(7L));
        assertEquals(pool.size(), rolled.size(), "больше пула не выдать");
        assertEquals(pool.size(), new HashSet<>(rolled).size());

        List<TierProperty> exclude = rolled.subList(0, 2);
        List<TierProperty> remaining = TierProperties.roll("legendary", ItemKind.SWORD,
                pool.size(), new Random(11L), exclude);
        assertEquals(pool.size() - exclude.size(), remaining.size());
        for (TierProperty property : exclude) {
            assertFalse(remaining.contains(property), "исключённое свойство вернулось: " + property.id());
        }

        assertTrue(TierProperties.roll("legendary", ItemKind.SWORD, 0, new Random(1L)).isEmpty());
        assertTrue(TierProperties.roll("legendary", null, 1, new Random(1L)).isEmpty(),
                "без вида предмета свойство не выдаётся");
    }

    @Test
    void serializationRoundTripsOnePdcString() {
        List<TierProperty> properties = TierProperties.roll("epic", ItemKind.CHESTPLATE,
                TierProperties.MAX_PER_ITEM, new Random(42L));
        String raw = TierProperties.serialize(properties);
        assertEquals(properties.size(), raw.split(",").length);
        assertEquals(properties, TierProperties.parse(raw));
        assertEquals("", TierProperties.serialize(List.of()));
        assertEquals("", TierProperties.serialize(null));
        assertTrue(TierProperties.parse(null).isEmpty());
        assertTrue(TierProperties.parse("  ").isEmpty());
    }

    @Test
    void parseSkipsUnknownAndDuplicateIds() {
        TierProperty first = TierProperties.byTier("rare").get(0);
        String raw = first.id() + ",unknown_property," + first.id() + ",,"
                + TierProperties.byTier("rare").get(1).id();
        List<TierProperty> parsed = TierProperties.parse(raw);
        assertEquals(2, parsed.size());
        assertEquals(first, parsed.get(0));
    }

    @Test
    void describeCarriesTheSignOfTheValue() {
        // Знак берётся из величины, а не из названия: штраф всегда
        // читается с минусом, бонус — там, где описание начинается с числа.
        TierProperty rust = TierProperties.byId("rust");
        assertNotNull(rust);
        assertTrue(rust.describe().startsWith("-"), "проклятое свойство читается как минус: "
                + rust.describe());

        int signedBonuses = 0;
        for (TierProperty property : TierProperties.all()) {
            String text = property.describe();
            assertFalse(text.isBlank(), property.id() + " без описания");
            if (property.value() < 0) {
                assertTrue(text.startsWith("-"), "штраф без минуса: " + property.id()
                        + " → " + text);
            } else if (text.startsWith("+")) {
                signedBonuses++;
            }
        }
        assertTrue(signedBonuses > 0, "ни один бонус не читается с плюсом");
    }

    @Test
    void cursedPoolIsPenaltiesAndLegendaryIsBonuses() {
        // Проклятый тир — только атрибутные минусы: шансовый отрицательный эффект
        // просто не срабатывал бы, поэтому в пуле нет CRIT_CHANCE/THORNS и подобных.
        for (TierProperty property : TierProperties.byTier("cursed")) {
            assertTrue(property.value() < 0,
                    "проклятое свойство должно быть штрафом: " + property.id());
        }
        // Легендарный пул — только бонусы.
        for (TierProperty property : TierProperties.byTier("legendary")) {
            assertTrue(property.value() > 0,
                    "легендарное свойство должно быть бонусом: " + property.id());
        }
    }

    @Test
    void ladderGrowsInValue() {
        // Средняя величина растёт по лестнице: редкость должна ощущаться.
        double previous = Double.NEGATIVE_INFINITY;
        for (String tier : TierDef.LADDER) {
            double sum = 0;
            for (TierProperty property : TierProperties.byTier(tier)) {
                sum += property.value();
            }
            double average = sum / TierProperties.byTier(tier).size();
            assertTrue(average > previous, tier + " слабее предыдущего тира: " + average);
            previous = average;
        }
    }

    @Test
    void propertyAndUpgradeOfTheSameEffectAddUp() {
        // С 1.4.118 у свойства тира и кузнечного улучшения может совпасть
        // эффект: доли обязаны складываться, а не брать perLevel первого
        // определения. Пару ищем по факту, а не по имени свойства — пул
        // тиров меняется от волны к волне.
        ForgeUpgrade.Def upgrade = null;
        TierProperty property = null;
        for (TierProperty candidate : TierProperties.byTier("epic")) {
            if (candidate.value() <= 0) {
                continue;
            }
            for (ForgeUpgrade.Def def : ForgeUpgrade.ALL) {
                if (def.effect() == candidate.effect() && def.perLevel() > 0) {
                    upgrade = def;
                    property = candidate;
                    break;
                }
            }
            if (property != null) {
                break;
            }
        }
        assertNotNull(upgrade, "в эпическом пуле нет эффекта, который есть и в ковке");
        assertNotNull(property);

        Map<ForgeUpgrade.Def, Integer> effects = new LinkedHashMap<>();
        effects.put(upgrade, 2);
        effects.put(property.asDef(), 1);
        double expected = (upgrade.perLevel() * 2 + property.value()) / 100.0;
        double chance = ForgeUpgradeListener.levelOf(effects, property.effect())
                * ForgeUpgradeListener.fraction(effects, property.effect());
        assertEquals(expected, chance, 1e-9);
    }

    @Test
    void rollForAxeStaysWithinLimit() {
        for (String tier : TierDef.LADDER) {
            List<TierProperty> rolled = new ArrayList<>(TierProperties.roll(tier, ItemKind.AXE,
                    TierProperties.MAX_PER_ITEM, new Random()));
            assertTrue(rolled.size() <= TierProperties.MAX_PER_ITEM);
        }
    }
}
