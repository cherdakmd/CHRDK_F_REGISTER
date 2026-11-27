package ru.cherdakmd.chrdkguilds;

import java.util.HashSet;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GuildStyleVisualsTest {
    @Test void everyCurrentPresetAndSelectionStateHasFixedModel() {
        var models=new HashSet<NamespacedKey>();
        for(var kind:GuildStyleGui.KINDS) {
            assertTrue(models.add(GuildStyleVisuals.category(kind,false)));assertTrue(models.add(GuildStyleVisuals.category(kind,true)));
            for(var preset:GuildStyleDefs.options(kind))for(boolean personal:new boolean[]{false,true})for(boolean active:new boolean[]{false,true})
                assertTrue(models.add(GuildStyleVisuals.preset(kind,preset,personal,active)),kind+"/"+preset);
        }
        for(var control:GuildStyleVisuals.Control.values())assertTrue(models.add(control.model()));assertEquals(136,models.size());
        assertNull(GuildStyleVisuals.category(null,false));assertNull(GuildStyleVisuals.category("custom",false));assertNull(GuildStyleVisuals.preset("name","custom",false,false));
    }
    @Test void disabledAndForeignAppearanceIsNotOverwritten() {
        var meta=mock(ItemMeta.class);var key=GuildStyleVisuals.Control.OFF.model();GuildStyleVisuals.apply(meta,key,false);GuildStyleVisuals.apply(null,key,true);GuildStyleVisuals.apply(meta,null,true);verifyNoInteractions(meta);
        when(meta.getItemModel()).thenReturn(new NamespacedKey("other","art"));GuildStyleVisuals.apply(meta,key,true);verify(meta,never()).setItemModel(any());
        when(meta.getItemModel()).thenReturn(null);when(meta.hasCustomModelData()).thenReturn(true);GuildStyleVisuals.apply(meta,key,true);verify(meta,never()).setItemModel(any());
        when(meta.hasCustomModelData()).thenReturn(false);GuildStyleVisuals.apply(meta,key,true);verify(meta).setItemModel(key);verify(meta,never()).getPersistentDataContainer();
    }
}
