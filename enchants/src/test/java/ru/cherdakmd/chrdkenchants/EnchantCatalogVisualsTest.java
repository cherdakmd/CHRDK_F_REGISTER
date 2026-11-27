package ru.cherdakmd.chrdkenchants;

import java.util.HashSet;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EnchantCatalogVisualsTest {
    @Test void filterAndIndividualEntryModelsAreDistinctAndSafe() {
        var models=new HashSet<NamespacedKey>();for(var name:new String[]{"empty","prev","next","result","clear"})assertTrue(models.add(EnchantCatalogVisuals.control(name)));
        assertTrue(models.add(EnchantCatalogVisuals.group(null)));for(var v:ItemGroup.values())assertTrue(models.add(EnchantCatalogVisuals.group(v)));
        assertTrue(models.add(EnchantCatalogVisuals.rarity(null)));for(var v:EnchantDef.Rarity.values())assertTrue(models.add(EnchantCatalogVisuals.rarity(v)));
        assertTrue(models.add(EnchantCatalogVisuals.polarity(null)));for(var v:EnchantDef.Polarity.values())assertTrue(models.add(EnchantCatalogVisuals.polarity(v)));
        assertTrue(models.add(EnchantCatalogVisuals.hand(true)));assertTrue(models.add(EnchantCatalogVisuals.hand(false)));for(var v:EnchantCatalog.Sort.values())assertTrue(models.add(EnchantCatalogVisuals.sort(v)));assertEquals(30,models.size());
        for(var def:EnchantRegistry.all()) {var key=EnchantCatalogVisuals.entry(def);assertNotNull(key);assertEquals("book/custom/"+def.id(),key.getKey());assertTrue(models.add(key));}
        assertNull(EnchantCatalogVisuals.entry(null));assertNull(EnchantCatalogVisuals.control("other"));assertNull(EnchantCatalogVisuals.sort(null));
        var meta=mock(ItemMeta.class);var key=EnchantCatalogVisuals.control("result");EnchantCatalogVisuals.apply(meta,key,false);EnchantCatalogVisuals.apply(null,key,true);EnchantCatalogVisuals.apply(meta,null,true);verifyNoInteractions(meta);
        when(meta.getItemModel()).thenReturn(EnchantCatalogVisuals.control("prev"));EnchantCatalogVisuals.apply(meta,key,true);verify(meta,never()).setItemModel(any());
        when(meta.getItemModel()).thenReturn(null);when(meta.hasCustomModelData()).thenReturn(true);EnchantCatalogVisuals.apply(meta,key,true);verify(meta,never()).setItemModel(any());
        when(meta.hasCustomModelData()).thenReturn(false);EnchantCatalogVisuals.apply(meta,key,true);verify(meta).setItemModel(key);verify(meta,never()).getPersistentDataContainer();
    }
}
