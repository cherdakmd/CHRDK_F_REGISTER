package ru.cherdakmd.chrdkguilds;

import java.util.HashSet;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GuildRecruitVisualsTest {
    @Test void controlsAreDistinctAndCustomItemMetadataIsPreserved() {
        var keys=new HashSet<NamespacedKey>(); for(var control:GuildRecruitVisuals.Control.values()) assertTrue(keys.add(control.model())); assertEquals(12,keys.size());
        assertEquals(GuildRecruitVisuals.Control.OPEN_INVITED,GuildRecruitVisuals.listing(true,true));
        assertEquals(GuildRecruitVisuals.Control.OPEN,GuildRecruitVisuals.listing(true,false));
        assertEquals(GuildRecruitVisuals.Control.CLOSED_INVITED,GuildRecruitVisuals.listing(false,true));
        assertEquals(GuildRecruitVisuals.Control.CLOSED,GuildRecruitVisuals.listing(false,false)); assertNull(GuildRecruitVisuals.button("recruit-entry"));
        var meta=mock(ItemMeta.class); var value=GuildRecruitVisuals.Control.CONFIRM;
        GuildRecruitVisuals.apply(meta,value,false); GuildRecruitVisuals.apply(null,value,true); GuildRecruitVisuals.apply(meta,null,true); verifyNoInteractions(meta);
        when(meta.getItemModel()).thenReturn(new NamespacedKey("other","model")); GuildRecruitVisuals.apply(meta,value,true); verify(meta,never()).setItemModel(any());
        when(meta.getItemModel()).thenReturn(null); when(meta.hasCustomModelData()).thenReturn(true); GuildRecruitVisuals.apply(meta,value,true); verify(meta,never()).setItemModel(any());
        when(meta.hasCustomModelData()).thenReturn(false); GuildRecruitVisuals.apply(meta,value,true); verify(meta).setItemModel(value.model()); verify(meta,never()).getPersistentDataContainer();
    }
}
