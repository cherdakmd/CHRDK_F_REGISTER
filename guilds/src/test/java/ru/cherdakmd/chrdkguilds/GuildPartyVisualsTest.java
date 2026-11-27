package ru.cherdakmd.chrdkguilds;

import java.util.HashSet;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GuildPartyVisualsTest {
    @Test void controlsAreFixedAndForeignAppearanceIsPreserved() {
        var models=new HashSet<NamespacedKey>(); for(var value:GuildPartyVisuals.Control.values())assertTrue(models.add(value.model())); assertEquals(10,models.size());
        var meta=mock(ItemMeta.class); var control=GuildPartyVisuals.Control.ACCEPT;
        GuildPartyVisuals.apply(meta,control,false); GuildPartyVisuals.apply(null,control,true); GuildPartyVisuals.apply(meta,null,true); verifyNoInteractions(meta);
        when(meta.getItemModel()).thenReturn(new NamespacedKey("other","model")); GuildPartyVisuals.apply(meta,control,true); verify(meta,never()).setItemModel(any());
        when(meta.getItemModel()).thenReturn(null); when(meta.hasCustomModelData()).thenReturn(true); GuildPartyVisuals.apply(meta,control,true); verify(meta,never()).setItemModel(any());
        when(meta.hasCustomModelData()).thenReturn(false); GuildPartyVisuals.apply(meta,control,true); verify(meta).setItemModel(control.model()); verify(meta,never()).getPersistentDataContainer();
    }
}
