package ru.cherdakmd.chrdkfishing.bukkit;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FishMenuVisualsTest {
    @Test void eachSectionAndUnavailableStateHasAUniqueModel() {
        var ids = new HashSet<NamespacedKey>();
        for (var section : FishMenuVisuals.Section.values()) {
            assertEquals("chrdk_fishing", section.model(true).getNamespace());
            assertEquals("menu/main/" + section.id, section.model(true).getKey());
            assertTrue(ids.add(section.model(true)));
            if (section.canBeUnavailable) {
                assertEquals("menu/main/" + section.id + "_unavailable", section.model(false).getKey());
                assertTrue(ids.add(section.model(false)));
            } else assertEquals(section.model(true), section.model(false));
        }
        assertEquals(10, ids.size());
    }

    @Test void offAndForeignModelsOrCustomDataAreNeverOverwritten() {
        var meta = mock(ItemMeta.class);
        var section = FishMenuVisuals.Section.BAIT_SHOP;
        FishMenuVisuals.apply(meta, section, true, false);
        verify(meta, never()).setItemModel(any());
        var other = new NamespacedKey("other", "model");
        when(meta.getItemModel()).thenReturn(other);
        FishMenuVisuals.apply(meta, section, true, true);
        verify(meta, never()).setItemModel(any());
        when(meta.getItemModel()).thenReturn(null);
        when(meta.hasCustomModelData()).thenReturn(true);
        FishMenuVisuals.apply(meta, section, true, true);
        verify(meta, never()).setItemModel(any());
        when(meta.hasCustomModelData()).thenReturn(false);
        FishMenuVisuals.apply(meta, section, false, true);
        verify(meta).setItemModel(section.model(false));
        FishMenuVisuals.apply(null, section, true, true);
        FishMenuVisuals.apply(meta, null, true, true);
        verify(meta, times(1)).setItemModel(any());
    }
}
