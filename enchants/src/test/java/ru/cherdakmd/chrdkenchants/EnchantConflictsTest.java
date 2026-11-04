package ru.cherdakmd.chrdkenchants;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 1.4.282: «Жилодробитель» и «Дробитель» несовместимы на всех путях записи чар. */
final class EnchantConflictsTest {

    @Test
    void megaMiningPairIsDeclaredIncompatible() {
        assertEquals("megamine", EnchantConflicts.group("veinminer"));
        assertEquals("megamine", EnchantConflicts.group("excavator"));
        assertTrue(EnchantConflicts.sameGroup("veinminer", "excavator"));
        assertTrue(EnchantConflicts.conflictsWith(Map.of("veinminer", 3), "excavator"));
        assertTrue(EnchantConflicts.conflictsWith(Map.of("excavator", 2), "veinminer"));
        assertFalse(EnchantConflicts.sameGroup("veinminer", "veinminer"), "апгрейд своей чары разрешён");
    }

    @Test
    void everyRegistryGroupIsHonouredByTheSharedRules() {
        for (EnchantDef def : EnchantRegistry.all()) {
            for (String other : EnchantCatalog.conflicts(def)) {
                assertTrue(EnchantConflicts.sameGroup(def.id(), other),
                        def.id() + " и " + other + " обязаны конфликтовать");
                assertFalse(EnchantConflicts.validSet(Map.of(def.id(), 1, other, 1)));
            }
        }
        // Проклятие «тонкая подкладка» входит в ту же группу ward, что и защитные чары.
        assertTrue(EnchantConflicts.sameGroup("bulwark", "thin_padding"));
        assertNull(EnchantConflicts.group("scholar"));
    }

    @Test
    void validSetAcceptsUnrelatedAndRejectsConflictingMaps() {
        assertTrue(EnchantConflicts.validSet(Map.of()));
        assertTrue(EnchantConflicts.validSet(Map.of("lifesteal", 3, "midas", 1)));
        assertTrue(EnchantConflicts.validSet(Map.of("seer", 1, "scholar", 3)));
        assertFalse(EnchantConflicts.validSet(Map.of("veinminer", 3, "excavator", 2)));
        assertFalse(EnchantConflicts.validSet(Map.of("thunderstrike", 1, "chainspark", 2)));
        assertFalse(EnchantConflicts.validSet(Map.of("phoenix", 1, "laststand", 1)));
        // Неизвестные id не ломают старые предметы.
        assertTrue(EnchantConflicts.validSet(Map.of("seer", 1, "legacy_unknown", 2)));
    }

    @Test
    void copyApiMergeNeverGluesConflictingEnchants() {
        Map<String, Integer> target = new LinkedHashMap<>(Map.of("veinminer", 2));
        Map<String, Integer> merged = EnchantConflicts.merge(
                Map.of("excavator", 2, "lifesteal", 3), target);
        assertEquals(Map.of("veinminer", 2, "lifesteal", 3), merged);
        assertTrue(EnchantConflicts.validSet(merged));

        // Конфликт и внутри самой переносимой карты: остаётся первая чара группы.
        Map<String, Integer> both = EnchantConflicts.merge(
                new LinkedHashMap<>(Map.of("excavator", 1, "veinminer", 1)), Map.of());
        assertEquals(Map.of("excavator", 1), both);
        assertEquals(Map.of("veinminer", 3),
                EnchantConflicts.merge(Map.of("veinminer", 3), Map.of("veinminer", 1)),
                "одинаковые чары сливаются по максимуму");
    }

    @Test
    void strictBookBoundaryRejectsConflictingPdc() {
        assertNull(EnchantBookValidation.read("excavator:2;veinminer:3"));
        assertNull(EnchantBookValidation.read("bulwark:1;second_skin:1"));
        assertEquals(Map.of("seer", 1, "scholar", 1), EnchantBookValidation.read("seer:1;scholar:1"));
        assertFalse(WorkshopPolicy.valid(Map.of("veinminer", 3, "excavator", 2)));
        assertTrue(WorkshopPolicy.valid(Map.of("seer", 1, "scholar", 1)));
    }

    @Test
    void rollerFiltersAllConflictGroupsNotOnlyMegaMining() {
        for (EnchantDef def : EnchantRegistry.all()) {
            if (def.conflictGroup() == null) continue;
            String other = EnchantCatalog.conflicts(def).getFirst();
            assertTrue(EnchantConflicts.conflictsWith(Map.of(other, 1), def.id()),
                    def.id() + " не должен выпадать при " + other);
            assertTrue(EnchantRoller.applicable(List.of(def), "DIAMOND_PICKAXE",
                    Map.of(other, 1), 100, 100).isEmpty());
        }
        assertTrue(EnchantRoller.applicable(List.of(EnchantRegistry.byId("excavator")),
                "DIAMOND_PICKAXE", Map.of("veinminer", 3), 100, 100).isEmpty());
        assertTrue(EnchantRoller.applicable(List.of(EnchantRegistry.byId("excavator")),
                "DIAMOND_PICKAXE", Map.of(), 100, 100).contains(EnchantRegistry.byId("excavator")));
    }
}
