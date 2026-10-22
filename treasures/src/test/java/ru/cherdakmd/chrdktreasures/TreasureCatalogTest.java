package ru.cherdakmd.chrdktreasures;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TreasureCatalogTest {

    @Test
    void catalogueHasOneHundredTwentyFourItems() {
        assertEquals(124, TreasureCatalog.EXPECTED_ITEMS);
        assertEquals(TreasureCatalog.EXPECTED_ITEMS, TreasureCatalog.size());
        assertEquals(12, TreasureCatalog.MYTHICAL_ITEMS);
        assertEquals(14, TreasureRegion.values().length);
        assertEquals(8, TreasureCatalog.PER_REGION);
        assertEquals(TreasureRegion.values().length * TreasureCatalog.PER_REGION
                + TreasureCatalog.MYTHICAL_ITEMS, TreasureCatalog.size());
    }

    @Test
    void everyRegionHasTwoItemsPerRarity() {
        for (TreasureRegion region : TreasureRegion.values()) {
            int count = 0;
            for (TreasureTier tier : TreasureTier.values()) {
                if (!tier.regional()) {
                    continue;
                }
                List<TreasureItemDef> pool = TreasureCatalog.all().stream()
                        .filter(def -> def.region() == region && def.tier() == tier)
                        .toList();
                assertEquals(2, pool.size(), "у региона " + region.id()
                        + " должно быть две находки " + tier.id());
                TreasureItemDef first = TreasureCatalog.forRegionTier(region, tier);
                assertNotNull(first, "у региона " + region.id() + " нет находки " + tier.id());
                assertTrue(pool.contains(first), "forRegionTier вернул чужой предмет");
                count += pool.size();
            }
            assertEquals(TreasureCatalog.PER_REGION, count);
        }
        assertNull(TreasureCatalog.forRegionTier(TreasureRegion.PLAINS, TreasureTier.MYTHICAL));
    }

    @Test
    void mythicalItemsAreNotBoundToBiomes() {
        List<TreasureItemDef> mythical = TreasureCatalog.mythical();
        assertEquals(TreasureCatalog.MYTHICAL_ITEMS, mythical.size());
        for (TreasureItemDef def : mythical) {
            assertEquals(TreasureTier.MYTHICAL, def.tier());
            assertNull(def.region(), "мифическая находка не должна быть в регионе: " + def.id());
        }
    }

    @Test
    void rarityCountsMatchCatalogTable() {
        assertEquals(28, TreasureCatalog.byTier(TreasureTier.COMMON).size());
        assertEquals(28, TreasureCatalog.byTier(TreasureTier.RARE).size());
        assertEquals(28, TreasureCatalog.byTier(TreasureTier.EPIC).size());
        assertEquals(28, TreasureCatalog.byTier(TreasureTier.LEGENDARY).size());
        assertEquals(TreasureCatalog.MYTHICAL_ITEMS,
                TreasureCatalog.byTier(TreasureTier.MYTHICAL).size());
    }

    @Test
    void idsAreUniqueLowercaseAndFound() {
        Set<String> ids = new HashSet<>();
        for (TreasureItemDef def : TreasureCatalog.all()) {
            assertTrue(ids.add(def.id()), "дубль ида: " + def.id());
            assertTrue(def.id().matches("[a-z0-9]+(-[a-z0-9]+)*"),
                    "ид не в нижнем регистре с дефисами: " + def.id());
            assertEquals(def, TreasureCatalog.find(def.id()));
            assertNotNull(def.tier());
        }
        assertNull(TreasureCatalog.find("nesushchestvuyushchiy"));
        assertNull(TreasureCatalog.find(null));
    }

    @Test
    void everyItemHasKnownMaterialAndEffects() {
        for (TreasureItemDef def : TreasureCatalog.all()) {
            Material material;
            try {
                material = Material.valueOf(def.material());
            } catch (IllegalArgumentException exception) {
                throw new AssertionError("неизвестный материал " + def.material()
                        + " у " + def.id(), exception);
            }
            // isAir()/isItem() ходят в Registry, которому в юнит-тесте негде жить:
            // достаточно того, что имя есть в enum (и значит, это реальный материал).
            assertNotNull(material);
            assertFalse(def.effects().isEmpty(), "нет эффектов у " + def.id());
            Set<TreasureEffect.Kind> kinds = new HashSet<>();
            for (TreasureEffect effect : def.effects()) {
                assertTrue(kinds.add(effect.kind()), "повтор эффекта у " + def.id());
                assertTrue(effect.value() > 0.0, "нулевой эффект у " + def.id());
                if (effect.kind().percent()) {
                    assertTrue(effect.value() <= 1.0, "доля больше 100 % у " + def.id());
                }
            }
            assertEquals(def.armor(), material.name().endsWith("_HELMET")
                    || material.name().endsWith("_CHESTPLATE")
                    || material.name().endsWith("_LEGGINGS")
                    || material.name().endsWith("_BOOTS"));
        }
    }

    @Test
    void mythicalItemsAreStrongerThanCommon() {
        double mythical = 0.0;
        for (TreasureItemDef def : TreasureCatalog.mythical()) {
            mythical += def.effects().stream().mapToDouble(TreasureEffect::value).sum();
        }
        double common = 0.0;
        for (TreasureItemDef def : TreasureCatalog.byTier(TreasureTier.COMMON)) {
            common += def.effects().stream().mapToDouble(TreasureEffect::value).sum();
        }
        assertTrue(mythical / TreasureCatalog.MYTHICAL_ITEMS
                > common / TreasureCatalog.byTier(TreasureTier.COMMON).size());
    }
}
