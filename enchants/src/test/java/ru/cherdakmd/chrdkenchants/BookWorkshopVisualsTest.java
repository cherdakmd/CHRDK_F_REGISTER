package ru.cherdakmd.chrdkenchants;

import java.util.HashSet;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BookWorkshopVisualsTest {
    @Test void virtualModelsAreDistinctAndRespectForeignMetadata() {
        var keys=new HashSet<>();for(var id:new String[]{"instructions","left","right","cost_scribe","cost_cleanse","cost_combine","choice","selected","prev","next","combine","prepare","confirm","close"})assertTrue(keys.add(BookWorkshopVisuals.key(id)));assertEquals(14,keys.size());for(var mode:BookWorkshopGui.Mode.values())assertNotNull(BookWorkshopVisuals.key(BookWorkshopVisuals.cost(mode)));assertNull(BookWorkshopVisuals.key(null));assertNull(BookWorkshopVisuals.key("other"));
        var meta=mock(ItemMeta.class);BookWorkshopVisuals.apply(meta,"choice",false);BookWorkshopVisuals.apply(null,"choice",true);BookWorkshopVisuals.apply(meta,"other",true);verifyNoInteractions(meta);
        when(meta.getItemModel()).thenReturn(BookWorkshopVisuals.key("selected"));BookWorkshopVisuals.apply(meta,"choice",true);verify(meta,never()).setItemModel(any());
        when(meta.getItemModel()).thenReturn(null);when(meta.hasCustomModelData()).thenReturn(true);BookWorkshopVisuals.apply(meta,"choice",true);verify(meta,never()).setItemModel(any());
        when(meta.hasCustomModelData()).thenReturn(false);BookWorkshopVisuals.apply(meta,"choice",true);verify(meta).setItemModel(BookWorkshopVisuals.key("choice"));verify(meta,never()).getPersistentDataContainer();
    }
}
