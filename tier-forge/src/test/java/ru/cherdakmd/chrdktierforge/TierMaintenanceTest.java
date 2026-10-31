package ru.cherdakmd.chrdktierforge;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TierMaintenanceTest {
    @Test
    void propertyDisableLimitAndWrongTierDoNotModifyStoredSelection() {
        List<TierProperty> stored = new ArrayList<>(TierProperties.candidates("legendary", ItemKind.SWORD));
        List<TierProperty> original = List.copyOf(stored);
        assertFalse(stored.isEmpty());
        assertTrue(TierPropertySelection.active(stored, ItemKind.SWORD, "legendary", false, 3).isEmpty());
        assertTrue(TierPropertySelection.active(stored, ItemKind.SWORD, "cursed", true, 3).isEmpty());
        assertTrue(TierPropertySelection.active(stored, null, "legendary", true, 3).isEmpty());
        var limited = TierPropertySelection.active(stored, ItemKind.SWORD, "legendary", true, 1);
        assertEquals(List.of(stored.getFirst()), limited);
        assertEquals(original.subList(0, Math.min(3, original.size())),
                TierPropertySelection.active(stored, ItemKind.SWORD, "legendary", true, 3));
        assertEquals(original, stored);
    }

    @Test
    void migrationKnowsEveryFormerAffixCapCombination() {
        for (String tier : TierDef.LADDER) {
            for (ItemKind kind : ItemKind.values()) {
                var possible = TierAffixes.possibleForItem("0123456789ab", tier, kind);
                for (int good = 0; good <= 3; good++) {
                    for (int bad = 0; bad <= 3; bad++) {
                        assertTrue(possible.containsAll(TierAffixes.forItem("0123456789ab", tier, kind, good, bad)));
                    }
                }
            }
        }
    }

    @Test
    void knownLoreUpdatesWithoutErasingCustomMarkersOrNamePrefixes() {
        Component oldTier = Component.text("Легендарный · +1% к скорости");
        Component actual = Component.text("Легендарный · +1.2% к скорости");
        Component custom = Component.text("Легендарный подарок другу");
        Component enchant = Component.text("✧ Чужая строка чар");
        Component disabled = Component.text("◆ Старый аффикс");
        Component active = Component.text("◆ Новый аффикс");
        Map<String, Component> replacements = new LinkedHashMap<>();
        replacements.put("◆ Старый аффикс", null);
        replacements.put("◆ Новый аффикс", active);
        List<Component> before = List.of(custom, oldTier, enchant, disabled);
        List<Component> after = TierLore.refresh(before, Set.of("Легендарный"), actual, replacements);
        assertEquals(List.of(custom, actual, enchant, active), after);
        assertEquals(after, TierLore.refresh(after, Set.of("Легендарный"), actual, replacements));
        assertEquals(List.of(custom, oldTier, enchant, disabled), before);
    }

    @Test
    void unrecognizedCustomLayoutAndMissingLoreStayUntouched() {
        List<Component> custom = List.of(Component.text("✧ Только собственная карточка"));
        assertSame(custom, TierLore.refresh(custom, Set.of("Легендарный"), Component.text("Новая"), Map.of()));
        assertNull(TierLore.refresh(null, Set.of(), Component.empty(), Map.of()));
    }
}
