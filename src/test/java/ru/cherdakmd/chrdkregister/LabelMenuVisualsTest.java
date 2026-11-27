package ru.cherdakmd.chrdkregister;

import java.util.*;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LabelMenuVisualsTest {
    @Test void allLabelsStatesAndControlsHaveDistinctFixedModels() {
        var keys=new HashSet<NamespacedKey>();
        for(var label:LabelPreferences.Label.values()) {
            for(var state:LabelMenuVisuals.State.values())assertTrue(keys.add(LabelMenuVisuals.label(label,state)));
            for(var state:LabelMenuVisuals.Pick.values()) {
                var key=LabelMenuVisuals.picker(label,state);
                if(label==LabelPreferences.Label.HEADER)assertNull(key);else assertTrue(keys.add(key));
            }
        }
        for(var value:LabelMenuVisuals.Control.values())assertTrue(keys.add(LabelMenuVisuals.control(value)));
        for(var value:LabelMenuVisuals.Place.values())assertTrue(keys.add(LabelMenuVisuals.place(value)));
        assertTrue(keys.add(LabelMenuVisuals.preset(true)));assertTrue(keys.add(LabelMenuVisuals.preset(false)));assertEquals(78,keys.size());
        var rank=LabelPreferences.Label.RANK;var guild=LabelPreferences.Label.GUILDS;var classes=LabelPreferences.Label.CLASSES;
        var a=new LabelStyleService.Decoration(rank,"rank");var b=new LabelStyleService.Decoration(guild,"guild");var c=new LabelStyleService.Decoration(classes,"classes");
        var selection=new LabelStyleService.Selection(List.of(a),List.of(b),1,List.of(a,b,c));
        assertEquals(LabelMenuVisuals.State.OFF,LabelMenuVisuals.state(false,rank,selection));
        assertEquals(LabelMenuVisuals.State.ON,LabelMenuVisuals.state(true,rank,selection));
        assertEquals(LabelMenuVisuals.State.WAITING,LabelMenuVisuals.state(true,guild,selection));
        assertEquals(LabelMenuVisuals.State.UNASSIGNED,LabelMenuVisuals.state(true,classes,selection));
        assertEquals(LabelMenuVisuals.State.UNAVAILABLE,LabelMenuVisuals.state(true,LabelPreferences.Label.VK,selection));
        assertEquals(LabelMenuVisuals.State.ON,LabelMenuVisuals.state(true,LabelPreferences.Label.HEADER,selection));
    }
    @Test void disabledOrForeignMetadataIsNeverOverwritten() {
        var meta=mock(ItemMeta.class);var model=LabelMenuVisuals.control(LabelMenuVisuals.Control.COLOR);
        LabelMenuVisuals.apply(meta,model,false);LabelMenuVisuals.apply(null,model,true);LabelMenuVisuals.apply(meta,null,true);verifyNoInteractions(meta);
        when(meta.getItemModel()).thenReturn(new NamespacedKey("other","art"));LabelMenuVisuals.apply(meta,model,true);verify(meta,never()).setItemModel(any());
        when(meta.getItemModel()).thenReturn(null);when(meta.hasCustomModelData()).thenReturn(true);LabelMenuVisuals.apply(meta,model,true);verify(meta,never()).setItemModel(any());
        when(meta.hasCustomModelData()).thenReturn(false);LabelMenuVisuals.apply(meta,model,true);verify(meta).setItemModel(model);verify(meta,never()).getPersistentDataContainer();
    }
}
