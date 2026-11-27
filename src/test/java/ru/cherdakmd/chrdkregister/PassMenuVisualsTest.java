package ru.cherdakmd.chrdkregister;

import java.util.HashSet;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PassMenuVisualsTest {
    @Test void eachActionHasFixedModelAndForeignMetadataSurvives() {
        var keys=new HashSet<NamespacedKey>();for(var action:PassMenuService.Action.values())assertTrue(keys.add(PassMenuVisuals.model(action)));assertEquals(7,keys.size());
        assertNull(PassMenuVisuals.model(null));var meta=mock(ItemMeta.class);var action=PassMenuService.Action.TWO_FACTOR;
        PassMenuVisuals.apply(meta,action,false);PassMenuVisuals.apply(null,action,true);PassMenuVisuals.apply(meta,null,true);verifyNoInteractions(meta);
        when(meta.getItemModel()).thenReturn(new NamespacedKey("other","icon"));PassMenuVisuals.apply(meta,action,true);verify(meta,never()).setItemModel(any());
        when(meta.getItemModel()).thenReturn(null);when(meta.hasCustomModelData()).thenReturn(true);PassMenuVisuals.apply(meta,action,true);verify(meta,never()).setItemModel(any());
        when(meta.hasCustomModelData()).thenReturn(false);PassMenuVisuals.apply(meta,action,true);verify(meta).setItemModel(PassMenuVisuals.model(action));verify(meta,never()).getPersistentDataContainer();
    }
}
