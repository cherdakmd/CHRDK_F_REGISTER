package ru.cherdakmd.chrdkfishing.bukkit;

import java.util.HashSet;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FishSaleVisualsTest {
    @Test void controlsAndStatesHaveDistinctModels() {
        var keys = new HashSet<NamespacedKey>();
        for (var icon : FishSaleVisuals.Icon.values()) assertTrue(keys.add(icon.model()));
        assertEquals(6, keys.size());
        assertNotEquals(FishSaleVisuals.Icon.EMPTY.model(), FishSaleVisuals.Icon.SELL.model());
    }
    @Test void optInAndForeignMetadataAreRespected() {
        var meta = mock(ItemMeta.class);
        FishSaleVisuals.fish(meta, "pike", false); FishSaleVisuals.button(meta, FishSaleVisuals.Icon.SELL, false);
        verifyNoInteractions(meta);
        when(meta.getItemModel()).thenReturn(new NamespacedKey("other", "model"));
        FishSaleVisuals.fish(meta, "pike", true); FishSaleVisuals.button(meta, FishSaleVisuals.Icon.SELL, true);
        verify(meta, never()).setItemModel(any());
        when(meta.getItemModel()).thenReturn(null); when(meta.hasCustomModelData()).thenReturn(true);
        FishSaleVisuals.fish(meta, "pike", true); FishSaleVisuals.button(meta, FishSaleVisuals.Icon.SELL, true);
        verify(meta, never()).setItemModel(any());
        when(meta.hasCustomModelData()).thenReturn(false);
        FishSaleVisuals.fish(meta, "pike", true); verify(meta).setItemModel(FishingVisuals.fish("pike"));
        FishSaleVisuals.button(meta, FishSaleVisuals.Icon.SELL, true); verify(meta).setItemModel(FishSaleVisuals.Icon.SELL.model());
    }
}
