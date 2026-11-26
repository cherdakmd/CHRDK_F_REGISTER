package ru.cherdakmd.chrdkfishing.bukkit;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkfishing.BaitCatalog;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BaitMenuVisualsTest {
    @Test void allKindsAndSelectionsHaveDistinctModels() {
        var models = new HashSet<NamespacedKey>();
        for (var kind : BaitCatalog.all()) for (boolean selected : new boolean[]{false, true}) {
            var key = BaitMenuVisuals.bait(kind.id(), selected);
            assertEquals("chrdk_fishing", key.getNamespace());
            assertEquals("menu/bait/" + kind.id() + (selected ? "_selected" : ""), key.getKey());
            assertTrue(models.add(key));
        }
        for (var icon : BaitMenuVisuals.Icon.values()) assertTrue(models.add(icon.model()));
        assertEquals(10, models.size());
        assertThrows(IllegalArgumentException.class, () -> BaitMenuVisuals.bait("unknown", false));
    }
    @Test void disabledAndForeignModelsOrDataArePreserved() {
        var meta = mock(ItemMeta.class);
        BaitMenuVisuals.bait(meta, "quick", false, false); BaitMenuVisuals.button(meta, BaitMenuVisuals.Icon.BACK, false);
        verifyNoInteractions(meta);
        when(meta.getItemModel()).thenReturn(new NamespacedKey("other", "item"));
        BaitMenuVisuals.bait(meta, "quick", false, true); BaitMenuVisuals.button(meta, BaitMenuVisuals.Icon.BACK, true);
        verify(meta, never()).setItemModel(any());
        when(meta.getItemModel()).thenReturn(null); when(meta.hasCustomModelData()).thenReturn(true);
        BaitMenuVisuals.bait(meta, "quick", false, true); BaitMenuVisuals.button(meta, BaitMenuVisuals.Icon.BACK, true);
        verify(meta, never()).setItemModel(any());
        when(meta.hasCustomModelData()).thenReturn(false);
        BaitMenuVisuals.bait(meta, "quick", true, true); verify(meta).setItemModel(BaitMenuVisuals.bait("quick", true));
    }
}
