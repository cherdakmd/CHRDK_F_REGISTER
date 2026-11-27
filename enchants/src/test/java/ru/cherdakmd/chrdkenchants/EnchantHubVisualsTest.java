package ru.cherdakmd.chrdkenchants;

import java.util.HashSet;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EnchantHubVisualsTest {
    @Test void modelsAreUniqueAndForeignMetadataIsPreserved() {
        var keys=new HashSet<>();for(var id:EnchantHubVisuals.IDS)assertTrue(keys.add(EnchantHubVisuals.key(id)));assertEquals(31,keys.size());assertNull(EnchantHubVisuals.key(null));assertNull(EnchantHubVisuals.key("untrusted"));
        var meta=mock(ItemMeta.class);EnchantHubVisuals.apply(meta,"catalog",false);EnchantHubVisuals.apply(null,"catalog",true);EnchantHubVisuals.apply(meta,"untrusted",true);verifyNoInteractions(meta);
        when(meta.getItemModel()).thenReturn(EnchantHubVisuals.key("library"));EnchantHubVisuals.apply(meta,"catalog",true);verify(meta,never()).setItemModel(any());
        when(meta.getItemModel()).thenReturn(null);when(meta.hasCustomModelData()).thenReturn(true);EnchantHubVisuals.apply(meta,"catalog",true);verify(meta,never()).setItemModel(any());
        when(meta.hasCustomModelData()).thenReturn(false);EnchantHubVisuals.apply(meta,"catalog",true);verify(meta).setItemModel(EnchantHubVisuals.key("catalog"));verify(meta,never()).getPersistentDataContainer();
    }
}
