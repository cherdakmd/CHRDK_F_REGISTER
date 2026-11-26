package ru.cherdakmd.chrdkfishing.bukkit;

import java.util.HashSet;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FishOrdersVisualsTest {
    @Test void orderStatesAndControlsHaveDistinctModels() {
        var ids = new HashSet<NamespacedKey>();
        for (var icon : FishOrdersVisuals.Icon.values()) assertTrue(ids.add(icon.model()));
        assertEquals(7, ids.size());
        assertEquals(FishOrdersVisuals.Icon.ORDER_AVAILABLE, FishOrdersVisuals.order(false, false));
        assertEquals(FishOrdersVisuals.Icon.ORDER_SELECTED, FishOrdersVisuals.order(false, true));
        assertEquals(FishOrdersVisuals.Icon.ORDER_DONE, FishOrdersVisuals.order(true, false));
        assertEquals(FishOrdersVisuals.Icon.ORDER_DONE, FishOrdersVisuals.order(true, true));
    }
    @Test void optInAndForeignMetadataArePreserved() {
        var meta = mock(ItemMeta.class);
        FishOrdersVisuals.button(meta, FishOrdersVisuals.Icon.CONFIRM, false); FishOrdersVisuals.fish(meta, "pike", false);
        verifyNoInteractions(meta);
        when(meta.getItemModel()).thenReturn(new NamespacedKey("other", "model"));
        FishOrdersVisuals.button(meta, FishOrdersVisuals.Icon.CONFIRM, true); FishOrdersVisuals.fish(meta, "pike", true);
        verify(meta, never()).setItemModel(any());
        when(meta.getItemModel()).thenReturn(null); when(meta.hasCustomModelData()).thenReturn(true);
        FishOrdersVisuals.button(meta, FishOrdersVisuals.Icon.CONFIRM, true); FishOrdersVisuals.fish(meta, "pike", true);
        verify(meta, never()).setItemModel(any());
        when(meta.hasCustomModelData()).thenReturn(false);
        FishOrdersVisuals.fish(meta, "pike", true); verify(meta).setItemModel(FishingVisuals.fish("pike"));
        FishOrdersVisuals.button(meta, FishOrdersVisuals.Icon.CONFIRM, true); verify(meta).setItemModel(FishOrdersVisuals.Icon.CONFIRM.model());
    }
}
