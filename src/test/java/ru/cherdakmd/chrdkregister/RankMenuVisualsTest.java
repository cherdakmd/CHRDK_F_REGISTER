package ru.cherdakmd.chrdkregister;

import java.util.HashSet;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RankMenuVisualsTest {
    @Test void rankStatesAndMetadataAreIndependent() {
        var keys=new HashSet<NamespacedKey>();for(int level=1;level<=5;level++){
            assertTrue(keys.add(RankMenuVisuals.card(level,0)));assertTrue(keys.add(RankMenuVisuals.card(level,level)));assertTrue(keys.add(RankMenuVisuals.card(level,level+1)));
        }
        for(var button:RankMenuVisuals.Button.values())assertTrue(keys.add(button.model()));assertEquals(19,keys.size());assertNull(RankMenuVisuals.card(0,0));assertNull(RankMenuVisuals.card(6,0));
        var meta=mock(ItemMeta.class);var key=RankMenuVisuals.card(2,0);RankMenuVisuals.apply(meta,key,false);RankMenuVisuals.apply(null,key,true);RankMenuVisuals.apply(meta,null,true);verifyNoInteractions(meta);
        when(meta.getItemModel()).thenReturn(new NamespacedKey("other","model"));RankMenuVisuals.apply(meta,key,true);verify(meta,never()).setItemModel(any());
        when(meta.getItemModel()).thenReturn(null);when(meta.hasCustomModelData()).thenReturn(true);RankMenuVisuals.apply(meta,key,true);verify(meta,never()).setItemModel(any());
        when(meta.hasCustomModelData()).thenReturn(false);RankMenuVisuals.apply(meta,key,true);verify(meta).setItemModel(key);verify(meta,never()).getPersistentDataContainer();
    }
}
