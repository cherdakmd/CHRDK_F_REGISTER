package ru.cherdakmd.diamondshop;

import java.util.HashSet;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ShopMarketVisualsTest {
    @Test void categoryAndControlModelsAreDistinctAndSafe() {
        var models=new HashSet<>();for(var key:new String[]{"market.home.all","market.home.sellall","market.home.guide","market.home.promo","market.home.gift","market.home.gift-claimed","market.balance.name","trading.gui.close-name","trading.gui.admin-edit","trading.gui.empty","market.category.previous","market.home.back","trading.gui.next-name"})assertTrue(models.add(ShopMarketVisuals.control(key)));
        for(var category:ShopCategory.values()){assertTrue(models.add(ShopMarketVisuals.category(category,false)));assertTrue(models.add(ShopMarketVisuals.category(category,true)));}assertEquals(29,models.size());assertNull(ShopMarketVisuals.control(null));assertNull(ShopMarketVisuals.control("other"));assertNull(ShopMarketVisuals.category(null,true));
        var meta=mock(ItemMeta.class);var key=ShopMarketVisuals.control("market.home.all");ShopMarketVisuals.apply(meta,key,false);ShopMarketVisuals.apply(null,key,true);ShopMarketVisuals.apply(meta,null,true);verifyNoInteractions(meta);
        when(meta.getItemModel()).thenReturn(ShopMarketVisuals.control("market.home.sellall"));ShopMarketVisuals.apply(meta,key,true);verify(meta,never()).setItemModel(any());when(meta.getItemModel()).thenReturn(null);when(meta.hasCustomModelData()).thenReturn(true);ShopMarketVisuals.apply(meta,key,true);verify(meta,never()).setItemModel(any());when(meta.hasCustomModelData()).thenReturn(false);ShopMarketVisuals.apply(meta,key,true);verify(meta).setItemModel(key);verify(meta,never()).getPersistentDataContainer();
    }
}
