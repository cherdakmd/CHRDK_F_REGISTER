package ru.cherdakmd.chrdkgenerator;

import java.util.HashSet;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GeneratorMenuVisualsTest {
    @Test void fixedModelsCoverStatesTiersModulesAndButtons() {
        var keys = new HashSet<NamespacedKey>();
        for (String s : new String[]{"working", "no-lava", "no-water", "no-cells", "unloaded", "carried"}) assertTrue(keys.add(GeneratorMenuVisuals.status("state-" + s)));
        assertNull(GeneratorMenuVisuals.status("state-other")); assertNull(GeneratorMenuVisuals.status(null));
        for (int i=1; i<=GeneratorConfig.TIERS; i++) assertTrue(keys.add(GeneratorMenuVisuals.tier(i)));
        assertNull(GeneratorMenuVisuals.tier(0)); assertNull(GeneratorMenuVisuals.tier(GeneratorConfig.TIERS+1));
        for (String s : new String[]{"speed", "capacity", "luck"}) {
            assertTrue(keys.add(GeneratorMenuVisuals.module(s, 0))); assertTrue(keys.add(GeneratorMenuVisuals.module(s, GeneratorStore.MAX_LEVEL)));
        }
        assertNull(GeneratorMenuVisuals.module("other", 0));
        assertTrue(keys.add(GeneratorMenuVisuals.upgradeTier(1))); assertTrue(keys.add(GeneratorMenuVisuals.upgradeTier(GeneratorConfig.TIERS)));
        assertTrue(keys.add(GeneratorMenuVisuals.chunk(0))); assertTrue(keys.add(GeneratorMenuVisuals.chunk(1)));
        assertTrue(keys.add(GeneratorMenuVisuals.bunker(0, 100))); assertTrue(keys.add(GeneratorMenuVisuals.bunker(1, 100))); assertTrue(keys.add(GeneratorMenuVisuals.bunker(100, 100)));
        for (var control : GeneratorMenuVisuals.Control.values()) assertTrue(keys.add(control.model()));
        assertEquals(44, keys.size());
        assertEquals(GeneratorMenuVisuals.Control.FUEL_OPEN, GeneratorMenuVisuals.button("fuel-open")); assertNull(GeneratorMenuVisuals.button("custom"));
    }
    @Test void disabledAndForeignMetadataAreNotOverwritten() {
        var meta = mock(ItemMeta.class); var key = GeneratorMenuVisuals.Control.CLOSE.model();
        GeneratorMenuVisuals.apply(meta, key, false); verifyNoInteractions(meta);
        GeneratorMenuVisuals.apply(null, key, true); GeneratorMenuVisuals.apply(meta, null, true); verifyNoInteractions(meta);
        when(meta.getItemModel()).thenReturn(new NamespacedKey("other", "model")); GeneratorMenuVisuals.apply(meta, key, true); verify(meta, never()).setItemModel(any());
        when(meta.getItemModel()).thenReturn(null); when(meta.hasCustomModelData()).thenReturn(true); GeneratorMenuVisuals.apply(meta, key, true); verify(meta, never()).setItemModel(any());
        when(meta.hasCustomModelData()).thenReturn(false); GeneratorMenuVisuals.apply(meta, key, true); verify(meta).setItemModel(key); verify(meta, never()).getPersistentDataContainer();
    }
}
