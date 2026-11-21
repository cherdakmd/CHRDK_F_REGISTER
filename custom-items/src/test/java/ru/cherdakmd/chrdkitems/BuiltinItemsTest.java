package ru.cherdakmd.chrdkitems;

import org.junit.jupiter.api.Test;
import org.bukkit.Material;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BuiltinItemsTest {
    @Test void abilityRequiresExactIdentityMaterialSchemaAndPositiveAmount() {
        var def = BuiltinItems.SHOVEL;
        assertTrue(PrototypeEffects.identityMatchesFields(Material.IRON_SHOVEL, 1, "mud_shovel", 1, def));
        assertFalse(PrototypeEffects.identityMatchesFields(Material.DIAMOND_SHOVEL, 1, "mud_shovel", 1, def));
        assertFalse(PrototypeEffects.identityMatchesFields(Material.IRON_SHOVEL, 0, "mud_shovel", 1, def));
        assertFalse(PrototypeEffects.identityMatchesFields(Material.IRON_SHOVEL, 1, "test_shovel", 1, def));
        assertFalse(PrototypeEffects.identityMatchesFields(Material.IRON_SHOVEL, 1, "mud_shovel", 2, def));
        assertFalse(PrototypeEffects.identityMatchesFields(Material.IRON_SHOVEL, 1, "mud_shovel", null, def));
        assertFalse(PrototypeEffects.identityMatchesFields(Material.IRON_SHOVEL, 1, null, 1, def));
        assertFalse(PrototypeEffects.identityMatchesFields(Material.IRON_SHOVEL, 1, "mud_shovel", 1, null));
    }
    @Test void fixedShovelCannotBeOverriddenAndHasOwnTexture() {
        var result = BuiltinItems.merge(Map.of());
        assertEquals(5, result.size());
        assertSame(BuiltinItems.SHOVEL, result.get("mud_shovel"));
        assertEquals(810003, PrototypeVisuals.modelData(BuiltinItems.SHOVEL));
        assertNull(PrototypeEffects.effect(BuiltinItems.SHOVEL));
        assertEquals(810005, PrototypeVisuals.modelData(BuiltinItems.LITTER));
        for (var utility : java.util.List.of(BuiltinItems.CAT_FINDER, BuiltinItems.SPAWN_GUIDE, BuiltinItems.WEATHER_GAUGE)) {
            assertTrue(BuiltinUse.supports(utility));
            assertEquals(810003, PrototypeVisuals.modelData(utility));
            assertThrows(IllegalArgumentException.class, () -> BuiltinItems.merge(Map.of(utility.id(), utility)));
        }
        assertFalse(BuiltinUse.supports(BuiltinItems.LITTER));
        assertThrows(IllegalArgumentException.class, () -> BuiltinItems.merge(Map.of(BuiltinItems.LITTER.id(), BuiltinItems.LITTER)));
        assertThrows(IllegalArgumentException.class, () -> BuiltinItems.merge(Map.of("mud_shovel", BuiltinItems.SHOVEL)));
        var fake = new ItemCatalog.Definition("mud_shovel", ItemCatalog.Category.TOOL, Material.DIAMOND_SHOVEL, "chrdk_items:item/mud_shovel");
        assertFalse(BuiltinItems.isMudShovel(fake));
        assertNull(PrototypeVisuals.modelData(fake));
    }
}
