package ru.cherdakmd.diamondshop;

import java.util.HashSet;
import org.bukkit.Material;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ShopAdminVisualsTest {
    @Test void controlsAndSellStatesHaveIndependentModelsWithoutChangingMetadata() {
        var keys=new HashSet<>();for(var key:new String[]{"empty","close-name","save-name","prev-name","next-name","back-name","restore-name","remove-name","remove-confirm","price-down-small","price-down","price-up","price-up-small","stock-down","stock-up","field-category","tariff-down","tariff-up"})assertTrue(keys.add(ShopAdminVisuals.key("admin.gui."+key,Material.PAPER)));
        assertTrue(keys.add(ShopAdminVisuals.key("admin.gui.field-sell",Material.LIME_DYE)));assertTrue(keys.add(ShopAdminVisuals.key("admin.gui.field-sell",Material.GRAY_DYE)));assertEquals(20,keys.size());assertNull(ShopAdminVisuals.key(null,Material.PAPER));assertNull(ShopAdminVisuals.key("other",Material.PAPER));
        var meta=mock(ItemMeta.class);var model=ShopAdminVisuals.key("admin.gui.save-name",Material.DIAMOND);ShopAdminVisuals.apply(meta,model,false);ShopAdminVisuals.apply(null,model,true);ShopAdminVisuals.apply(meta,null,true);verifyNoInteractions(meta);
        when(meta.getItemModel()).thenReturn(ShopAdminVisuals.key("admin.gui.close-name",Material.BARRIER));ShopAdminVisuals.apply(meta,model,true);verify(meta,never()).setItemModel(any());when(meta.getItemModel()).thenReturn(null);when(meta.hasCustomModelData()).thenReturn(true);ShopAdminVisuals.apply(meta,model,true);verify(meta,never()).setItemModel(any());when(meta.hasCustomModelData()).thenReturn(false);ShopAdminVisuals.apply(meta,model,true);verify(meta).setItemModel(model);verify(meta,never()).getPersistentDataContainer();
    }
}
