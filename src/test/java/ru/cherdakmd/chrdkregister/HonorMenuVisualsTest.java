package ru.cherdakmd.chrdkregister;

import java.util.HashSet;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class HonorMenuVisualsTest {
    @Test void uniqueModelsAndForeignMetadataAreRespected() {
        var names=new HashSet<>();
        for(var icon:HonorMenuVisuals.Icon.values()) assertTrue(names.add(HonorMenuVisuals.key(icon)));
        assertEquals(22,names.size());assertNull(HonorMenuVisuals.key(null));
        var meta=mock(ItemMeta.class);var model=HonorMenuVisuals.Icon.INFO;
        HonorMenuVisuals.apply(meta,model,false);HonorMenuVisuals.apply(null,model,true);HonorMenuVisuals.apply(meta,null,true);verifyNoInteractions(meta);
        when(meta.getItemModel()).thenReturn(HonorMenuVisuals.key(HonorMenuVisuals.Icon.CREATE));HonorMenuVisuals.apply(meta,model,true);verify(meta,never()).setItemModel(any());
        when(meta.getItemModel()).thenReturn(null);when(meta.hasCustomModelData()).thenReturn(true);HonorMenuVisuals.apply(meta,model,true);verify(meta,never()).setItemModel(any());
        when(meta.hasCustomModelData()).thenReturn(false);HonorMenuVisuals.apply(meta,model,true);verify(meta).setItemModel(HonorMenuVisuals.key(model));verify(meta,never()).getPersistentDataContainer();
    }
}
