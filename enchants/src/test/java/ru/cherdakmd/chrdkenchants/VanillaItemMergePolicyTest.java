package ru.cherdakmd.chrdkenchants;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Registry-independent arithmetic and preview-upgrade policy, not a live anvil test. */
class VanillaItemMergePolicyTest {
    @Test void twoEfficiencyFiveItemsNeedSixWhenCapIsTen() {
        var first = Map.of("minecraft:efficiency", 5);
        var second = Map.of("minecraft:efficiency", 5);
        var merged = AnvilPrice.merge(first, second, key -> 10);
        assertEquals(Map.of("minecraft:efficiency", 6), merged);
        assertTrue(AnvilPrice.strengthens(merged, first));
        assertEquals(merged, VanillaLevels.upgrades(first, merged));
    }
    @Test void configuredCapFiveKeepsLevelFive() {
        var first = Map.of("minecraft:efficiency", 5);
        var merged = AnvilPrice.merge(first, first, key -> 5);
        assertEquals(first, merged);
        assertFalse(AnvilPrice.strengthens(merged, first));
        assertTrue(VanillaLevels.upgrades(first, merged).isEmpty());
    }
    @Test void noReintroductionOfDroppedOrDowngradeOfHigherEnchants() {
        var merged = Map.of("efficiency", 6, "unbreaking", 4, "fortune", 4);
        var nativePreview = Map.of("efficiency", 5, "unbreaking", 7);
        assertEquals(Map.of("efficiency", 6), VanillaLevels.upgrades(nativePreview, merged));
    }
}
