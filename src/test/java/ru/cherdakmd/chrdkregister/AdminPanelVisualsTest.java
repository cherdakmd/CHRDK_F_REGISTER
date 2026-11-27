package ru.cherdakmd.chrdkregister;

import java.util.HashSet;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AdminPanelVisualsTest {
    @Test void modelsAreDistinctAndRespectExistingMetadata() {
        var names=new HashSet<>();for(var icon:AdminPanelVisuals.Icon.values())assertTrue(names.add(AdminPanelVisuals.key(icon)));assertEquals(33,names.size());assertNull(AdminPanelVisuals.key(null));
        var meta=mock(ItemMeta.class);var icon=AdminPanelVisuals.Icon.WARN;
        AdminPanelVisuals.apply(meta,icon,false);AdminPanelVisuals.apply(null,icon,true);AdminPanelVisuals.apply(meta,null,true);verifyNoInteractions(meta);
        when(meta.getItemModel()).thenReturn(AdminPanelVisuals.key(AdminPanelVisuals.Icon.INFO));AdminPanelVisuals.apply(meta,icon,true);verify(meta,never()).setItemModel(any());
        when(meta.getItemModel()).thenReturn(null);when(meta.hasCustomModelData()).thenReturn(true);AdminPanelVisuals.apply(meta,icon,true);verify(meta,never()).setItemModel(any());
        when(meta.hasCustomModelData()).thenReturn(false);AdminPanelVisuals.apply(meta,icon,true);verify(meta).setItemModel(AdminPanelVisuals.key(icon));verify(meta,never()).getPersistentDataContainer();
    }
}
