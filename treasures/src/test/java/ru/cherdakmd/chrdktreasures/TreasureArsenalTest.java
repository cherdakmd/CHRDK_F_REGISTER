package ru.cherdakmd.chrdktreasures;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Арсенал Mine Treasure: структура, ограничения способностей и стык с каталогом.
 *
 * <p>Тест намеренно не зависит от Bukkit: как и каталог, арсенал — чистые
 * данные, поэтому все правила (регион у редкости, радиус у способности,
 * отсутствие проклятий у арсенала) проверяются без сервера.</p>
 */
class TreasureArsenalTest {

    @Test
    void arsenalCarriesThirtySixItemsFromPack() {
        assertEquals(36, TreasureArsenal.EXPECTED_SIZE);
        assertEquals(TreasureArsenal.EXPECTED_SIZE, TreasureArsenal.size());
        assertEquals(TreasureArsenal.EXPECTED_SIZE, TreasureArsenal.all().size());
        assertEquals(1, TreasureArsenal.mythical().size(),
                "из арсенала мифический только Гига-бур");
    }

    @Test
    void everyIdIsUniqueAndPrefixed() {
        Set<String> ids = new HashSet<>();
        for (TreasureItemDef def : TreasureArsenal.all()) {
            assertTrue(def.id().startsWith("dp-"), "ид арсенала без префикса: " + def.id());
            assertTrue(ids.add(def.id()), "повторяющийся ид арсенала: " + def.id());
        }
        assertEquals(TreasureArsenal.size(), ids.size());
    }

    @Test
    void regionsAndTiersStayConsistent() {
        for (TreasureItemDef def : TreasureArsenal.all()) {
            if (def.tier() == TreasureTier.MYTHICAL) {
                assertNull(def.region(), "мифическая вещь арсенала не привязана к биому: " + def.id());
                continue;
            }
            assertNotNull(def.region(), "у вещи арсенала нет региона: " + def.id());
            assertTrue(def.tier().regional(), "вещь арсенала в нерегиональной редкости: " + def.id());
            assertFalse(def.effects().isEmpty(), "у вещи арсенала нет эффектов: " + def.id());
            assertNull(def.curse(), "арсенал не несёт проклятий: " + def.id());
        }
    }

    @Test
    void everyArsenalRegionHasItemsAndClassicLadderStaysWhole() {
        for (TreasureRegion region : TreasureRegion.values()) {
            assertTrue(TreasureArsenal.countInRegion(region) >= 1,
                    "у региона нет вещей арсенала: " + region.id());
            for (TreasureTier tier : TreasureTier.values()) {
                if (!tier.regional()) {
                    continue;
                }
                assertNotNull(TreasureCatalog.forRegionTier(region, tier),
                        "классическая находка пропала: " + region.id() + '/' + tier.id());
            }
        }
    }

    @Test
    void weaponsAreActiveAndArmorIsPassive() {
        int weapons = 0;
        int armor = 0;
        for (TreasureItemDef def : TreasureArsenal.all()) {
            if (def.armor()) {
                armor++;
                assertFalse(def.active(), "броня арсенала не должна требовать ПКМ: " + def.id());
            } else if (def.material().endsWith("_SWORD") || def.material().endsWith("_AXE")
                    || def.material().equals("BOW") || def.material().equals("TRIDENT")) {
                weapons++;
                assertTrue(def.active(), "у оружия арсенала нет способности: " + def.id());
            }
        }
        assertTrue(weapons >= 10, "слишком мало оружия в арсенале: " + weapons);
        assertTrue(armor >= 15, "слишком мало брони в арсенале: " + armor);
    }

    @Test
    void abilitiesObeyTheSameBoundsAsCatalog() {
        for (TreasureItemDef def : TreasureArsenal.all()) {
            TreasureAbility ability = def.ability();
            if (ability == null) {
                continue;
            }
            assertTrue(ability.cooldownSeconds() >= 5 && ability.cooldownSeconds() <= 300,
                    "перезарядка вне 5..300: " + def.id());
            assertTrue(ability.power() > 0.0 && ability.power() <= 40.0,
                    "сила вне разумного: " + def.id());
            if (ability.kind().targeted()) {
                assertTrue(ability.radius() >= 1 && ability.radius() <= 24,
                        "радиус цели вне 1..24: " + def.id());
            } else {
                assertEquals(0, ability.radius(),
                        "способность на себя не должна иметь радиус: " + def.id());
            }
        }
    }

    @Test
    void registryJoinsBothPoolsWithoutLosingClassics() {
        assertEquals(TreasureCatalog.size() + TreasureArsenal.size(), TreasureRegistry.size());
        assertEquals(98, TreasureRegistry.size());
        for (TreasureItemDef def : TreasureCatalog.all()) {
            assertSame(def, TreasureRegistry.find(def.id()));
        }
        for (TreasureItemDef def : TreasureArsenal.all()) {
            assertSame(def, TreasureRegistry.find(def.id()));
        }
        assertNull(TreasureRegistry.find("нет-такого"));
        assertNull(TreasureRegistry.find(null));
    }

    @Test
    void poolKeepsClassicFirstAndAddsArsenal() {
        List<TreasureItemDef> desert = TreasureRegistry.pool(TreasureRegion.DESERT,
                TreasureTier.LEGENDARY);
        assertEquals(TreasureCatalog.forRegionTier(TreasureRegion.DESERT, TreasureTier.LEGENDARY),
                desert.get(0));
        assertTrue(desert.size() >= 2, "в пуле пустыни нет арсенала");
        assertEquals(desert.size(), TreasureArsenal.pool(TreasureRegion.DESERT,
                TreasureTier.LEGENDARY).size() + 1);

        List<TreasureItemDef> mythical = TreasureRegistry.pool(TreasureRegion.END,
                TreasureTier.MYTHICAL);
        assertEquals(TreasureCatalog.MYTHICAL_ITEMS + TreasureArsenal.mythical().size(),
                mythical.size());

        assertTrue(TreasureRegistry.pool(null, TreasureTier.COMMON).isEmpty());
        assertTrue(TreasureRegistry.pool(TreasureRegion.PLAINS, null).isEmpty());
    }

    @Test
    void regionViewCountsClassicsAndArsenal() {
        int total = 0;
        for (TreasureRegion region : TreasureRegion.values()) {
            List<TreasureItemDef> items = TreasureRegistry.region(region);
            assertTrue(items.size() >= TreasureCatalog.PER_REGION,
                    "регион потерял классические находки: " + region.id());
            total += items.size();
        }
        assertEquals(TreasureRegistry.size() - TreasureRegistry.mythical().size(), total,
                "региональных вещей должно быть ровно столько, сколько без мифических");
    }

    @Test
    void arsenalHasNoDuplicateMaterialsWhereItWouldConfusePlayers() {
        Set<String> names = new HashSet<>();
        for (TreasureItemDef def : TreasureArsenal.all()) {
            assertTrue(names.add(def.material() + '/' + def.tier().id() + '/' + def.region()),
                    "две вещи арсенала неразличимы в пуле: " + def.id());
        }
    }
}
