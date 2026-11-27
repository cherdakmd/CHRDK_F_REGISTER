package ru.cherdakmd.chrdkregister;

import java.util.HashSet;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KeypadVisualsTest {
    @Test void controlsAreFixedAndExistingMetadataIsPreserved() {
        var models=new HashSet<NamespacedKey>();for(var control:KeypadVisuals.Control.values())assertTrue(models.add(control.model()));assertEquals(6,models.size());
        var meta=mock(ItemMeta.class);var control=KeypadVisuals.Control.PROGRESS;KeypadVisuals.apply(meta,control,false);KeypadVisuals.apply(null,control,true);KeypadVisuals.apply(meta,null,true);verifyNoInteractions(meta);
        when(meta.getItemModel()).thenReturn(new NamespacedKey("other","model"));KeypadVisuals.apply(meta,control,true);verify(meta,never()).setItemModel(any());
        when(meta.getItemModel()).thenReturn(null);when(meta.hasCustomModelData()).thenReturn(true);KeypadVisuals.apply(meta,control,true);verify(meta,never()).setItemModel(any());
        when(meta.hasCustomModelData()).thenReturn(false);KeypadVisuals.apply(meta,control,true);verify(meta).setItemModel(control.model());verify(meta,never()).getPersistentDataContainer();
    }
}
