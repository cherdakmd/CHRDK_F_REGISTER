package ru.cherdakmd.chrdkregister;

import java.util.HashSet;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProfileMenuVisualsTest {
    @Test void builtinButtonsAreDistinctAndMetadataSurvives() {
        var keys=new HashSet<NamespacedKey>();for(var button:ProfileMenuVisuals.Button.values())assertTrue(keys.add(button.model()));assertEquals(6,keys.size());
        assertEquals(ProfileMenuVisuals.Button.FRIEND,ProfileMenuVisuals.button("profile.friend-invite-button"));assertNull(ProfileMenuVisuals.button("addon"));
        var meta=mock(ItemMeta.class);var button=ProfileMenuVisuals.Button.GROUP;ProfileMenuVisuals.apply(meta,button,false);ProfileMenuVisuals.apply(null,button,true);ProfileMenuVisuals.apply(meta,null,true);verifyNoInteractions(meta);
        when(meta.getItemModel()).thenReturn(new NamespacedKey("other","art"));ProfileMenuVisuals.apply(meta,button,true);verify(meta,never()).setItemModel(any());
        when(meta.getItemModel()).thenReturn(null);when(meta.hasCustomModelData()).thenReturn(true);ProfileMenuVisuals.apply(meta,button,true);verify(meta,never()).setItemModel(any());
        when(meta.hasCustomModelData()).thenReturn(false);ProfileMenuVisuals.apply(meta,button,true);verify(meta).setItemModel(button.model());verify(meta,never()).getPersistentDataContainer();
    }
}
