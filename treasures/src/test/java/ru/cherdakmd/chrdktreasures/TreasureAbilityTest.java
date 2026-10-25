package ru.cherdakmd.chrdktreasures;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Активные способности: у кого они есть, какие у них границы и как они
 * связаны с редкостями.
 */
class TreasureAbilityTest {

    @Test
    void everyRareFindIsActiveAndCheapFindsArePassive() {
        for (TreasureItemDef def : TreasureCatalog.all()) {
            if (def.tier() == TreasureTier.COMMON || def.tier() == TreasureTier.RARE) {
                assertFalse(def.active(), "у обычной находки не должно быть ПКМ: " + def.id());
                continue;
            }
            assertTrue(def.active(), "у находки нет способности: " + def.id());
        }
    }

    @Test
    void abilityBoundsAreSane() {
        Set<TreasureAbility.Kind> kinds = new HashSet<>();
        for (TreasureItemDef def : TreasureCatalog.all()) {
            TreasureAbility ability = def.ability();
            if (ability == null) {
                continue;
            }
            kinds.add(ability.kind());
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
        assertTrue(kinds.size() >= 6, "слишком однообразные способности: " + kinds);
    }

    @Test
    void kindsHaveUniqueIdsAndUnits() {
        Set<String> ids = new HashSet<>();
        for (TreasureAbility.Kind kind : TreasureAbility.Kind.values()) {
            assertTrue(ids.add(kind.id()), "дубль ида способности: " + kind.id());
            assertEquals(kind.id(), kind.id().toLowerCase(Locale.ROOT));
            assertNotNull(kind.unit(), "у способности нет единицы силы: " + kind);
            assertNotNull(kind.title());
            assertFalse(kind.title().isBlank());
            assertEquals("ability." + kind.id(), kind.textKey());
        }
    }

    @Test
    void bowsUseSneakOnlySoShootingStillWorks() {
        assertTrue(TreasureItems.bowLike("BOW"));
        assertTrue(TreasureItems.bowLike("CROSSBOW"));
        assertFalse(TreasureItems.bowLike("NETHERITE_SWORD"));
        assertFalse(TreasureItems.bowLike(null));
    }

    @Test
    void invalidAbilitiesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new TreasureAbility(TreasureAbility.Kind.BLAST, 5, 5, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new TreasureAbility(TreasureAbility.Kind.BLAST, 5, 0, 30));
        assertThrows(IllegalArgumentException.class,
                () -> new TreasureAbility(TreasureAbility.Kind.HEAL, 5, 3, 30));
        assertThrows(IllegalArgumentException.class,
                () -> new TreasureAbility(TreasureAbility.Kind.HEAL, 0, 0, 30));
        assertThrows(IllegalArgumentException.class,
                () -> new TreasureAbility(null, 1, 0, 30));
        assertNotNull(new TreasureAbility(TreasureAbility.Kind.HEAL, 3, 0, 60));
    }

    @Test
    void auraLevelsArePrintedAsRomanNumerals() {
        assertEquals("I", TreasureItems.format(new TreasureEffect(TreasureEffect.Kind.HASTE, 1)));
        assertEquals("II", TreasureItems.format(new TreasureEffect(TreasureEffect.Kind.HASTE, 2)));
        assertEquals("III", TreasureItems.format(new TreasureEffect(TreasureEffect.Kind.HASTE, 3)));
        assertEquals("10 %", TreasureItems.format(new TreasureEffect(TreasureEffect.Kind.FLAME, 0.1)));
        assertEquals("5", TreasureItems.format(new TreasureEffect(TreasureEffect.Kind.LUCK, 5)));
    }
}
