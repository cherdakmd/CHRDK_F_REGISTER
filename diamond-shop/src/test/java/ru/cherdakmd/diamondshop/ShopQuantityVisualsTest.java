package ru.cherdakmd.diamondshop;

import java.util.HashSet;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ShopQuantityVisualsTest {
    @Test void controlModelsAreDistinctAndDoNotOverwriteMetadata() {
        var keys=new HashSet<>();for(var id:new String[]{"minus_64","minus_8","minus_1","plus_1","plus_8","plus_64","preset_1","preset_8","preset_16","preset_32","preset_64","minimum","total_buy","total_sell","confirm","unavailable","back","maximum"})assertTrue(keys.add(ShopQuantityVisuals.key(id)));assertEquals(18,keys.size());for(int value:new int[]{-64,-8,-1,1,8,64})assertNotNull(ShopQuantityVisuals.key(ShopQuantityVisuals.delta(value)));assertNull(ShopQuantityVisuals.key(null));assertNull(ShopQuantityVisuals.key("unknown"));
        var meta=mock(ItemMeta.class);ShopQuantityVisuals.apply(meta,"confirm",false);ShopQuantityVisuals.apply(null,"confirm",true);ShopQuantityVisuals.apply(meta,"unknown",true);verifyNoInteractions(meta);
        when(meta.getItemModel()).thenReturn(ShopQuantityVisuals.key("back"));ShopQuantityVisuals.apply(meta,"confirm",true);verify(meta,never()).setItemModel(any());when(meta.getItemModel()).thenReturn(null);when(meta.hasCustomModelData()).thenReturn(true);ShopQuantityVisuals.apply(meta,"confirm",true);verify(meta,never()).setItemModel(any());when(meta.hasCustomModelData()).thenReturn(false);ShopQuantityVisuals.apply(meta,"confirm",true);verify(meta).setItemModel(ShopQuantityVisuals.key("confirm"));verify(meta,never()).getPersistentDataContainer();
    }
}
