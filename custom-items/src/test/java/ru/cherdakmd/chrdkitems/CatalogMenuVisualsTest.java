package ru.cherdakmd.chrdkitems;

import java.util.HashSet;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CatalogMenuVisualsTest {
    @Test void allCategoryStatesAndNavigationUseDistinctModels() {
        var keys = new HashSet<NamespacedKey>();
        for (var c : CatalogMenuVisuals.Control.values()) {
            assertTrue(keys.add(c.model(false)));
            if (c.ordinal() <= CatalogMenuVisuals.Control.CONSUMABLE.ordinal()) assertTrue(keys.add(c.model(true)));
            else assertEquals(c.model(false), c.model(true));
        }
        assertEquals(13, keys.size());
        assertEquals("menu/catalog/all_selected", CatalogMenuVisuals.Control.ALL.model(true).getKey());
    }
    @Test void optInDoesNotOverwriteExistingMetadataOrAddIdentity() {
        var meta = mock(ItemMeta.class); var key = CatalogMenuVisuals.Control.CLOSE.model(false);
        CatalogMenuVisuals.apply(meta, key, false); verifyNoInteractions(meta);
        CatalogMenuVisuals.apply(null, key, true); CatalogMenuVisuals.apply(meta, null, true); verifyNoInteractions(meta);
        when(meta.getItemModel()).thenReturn(new NamespacedKey("foreign", "icon"));
        CatalogMenuVisuals.apply(meta, key, true); verify(meta, never()).setItemModel(any());
        when(meta.getItemModel()).thenReturn(null); when(meta.hasCustomModelData()).thenReturn(true);
        CatalogMenuVisuals.apply(meta, key, true); verify(meta, never()).setItemModel(any());
        when(meta.hasCustomModelData()).thenReturn(false); CatalogMenuVisuals.apply(meta, key, true);
        verify(meta).setItemModel(key); verify(meta, never()).getPersistentDataContainer();
    }
}
