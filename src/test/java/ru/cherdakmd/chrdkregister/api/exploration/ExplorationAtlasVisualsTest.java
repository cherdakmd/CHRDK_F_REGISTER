package ru.cherdakmd.chrdkregister.api.exploration;

import java.util.HashSet;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ExplorationAtlasVisualsTest {
    @Test void statesHaveDistinctFixedModels() {
        var names=new HashSet<NamespacedKey>();
        for(var icon:ExplorationAtlasVisuals.Icon.values())assertTrue(names.add(icon.model()));
        assertEquals(11,names.size());
        assertEquals(ExplorationAtlasVisuals.Icon.UNKNOWN,ExplorationAtlasVisuals.biome(false,0));
        assertEquals(ExplorationAtlasVisuals.Icon.VISITED,ExplorationAtlasVisuals.biome(true,4));
        assertEquals(ExplorationAtlasVisuals.Icon.COMPLETE,ExplorationAtlasVisuals.biome(true,5));
        assertEquals(ExplorationAtlasVisuals.Icon.TASK_PROGRESS,ExplorationAtlasVisuals.task(9,10));
        assertEquals(ExplorationAtlasVisuals.Icon.TASK_DONE,ExplorationAtlasVisuals.task(10,10));
    }
    @Test void optInAndExistingMetadataAreRespected() {
        var meta=mock(ItemMeta.class);var icon=ExplorationAtlasVisuals.Icon.STUDY;
        ExplorationAtlasVisuals.apply(meta,icon,false);ExplorationAtlasVisuals.apply(null,icon,true);ExplorationAtlasVisuals.apply(meta,null,true);verifyNoInteractions(meta);
        when(meta.getItemModel()).thenReturn(new NamespacedKey("other","model"));ExplorationAtlasVisuals.apply(meta,icon,true);verify(meta,never()).setItemModel(any());
        when(meta.getItemModel()).thenReturn(null);when(meta.hasCustomModelData()).thenReturn(true);ExplorationAtlasVisuals.apply(meta,icon,true);verify(meta,never()).setItemModel(any());
        when(meta.hasCustomModelData()).thenReturn(false);ExplorationAtlasVisuals.apply(meta,icon,true);verify(meta).setItemModel(icon.model());verify(meta,never()).getPersistentDataContainer();
    }
}
