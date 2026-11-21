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
        assertEquals(1, result.size());
        assertSame(BuiltinItems.SHOVEL, result.get("mud_shovel"));
        assertEquals(810003, PrototypeVisuals.modelData(BuiltinItems.SHOVEL));
        assertNull(PrototypeEffects.effect(BuiltinItems.SHOVEL));
        assertThrows(IllegalArgumentException.class, () -> BuiltinItems.merge(Map.of("mud_shovel", BuiltinItems.SHOVEL)));
        var fake = new ItemCatalog.Definition("mud_shovel", ItemCatalog.Category.TOOL, Material.DIAMOND_SHOVEL, "chrdk_items:item/mud_shovel");
        assertFalse(BuiltinItems.isMudShovel(fake));
        assertNull(PrototypeVisuals.modelData(fake));
    }
}
