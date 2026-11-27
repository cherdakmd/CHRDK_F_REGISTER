package ru.cherdakmd.chrdkclaims;

import java.util.HashSet;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ClaimMenuVisualsTest {
    @Test void controlsTabsAndEntriesHaveDistinctFixedModels() {
        var models = new HashSet<NamespacedKey>();
        for (var icon : ClaimMenuVisuals.Icon.values()) assertTrue(models.add(icon.model()));
        for (var tab : ClaimMenuModel.Tab.values()) {
            assertTrue(models.add(ClaimMenuVisuals.tab(tab, false))); assertTrue(models.add(ClaimMenuVisuals.tab(tab, true)));
        }
        assertEquals(17, models.size());
        assertEquals(ClaimMenuVisuals.Icon.ENTRY_MINE, ClaimMenuVisuals.entry(new ClaimMenuModel.Entry("world", 0,0,"Me",0,50,true), "world"));
        assertEquals(ClaimMenuVisuals.Icon.ENTRY_OTHER_WORLD, ClaimMenuVisuals.entry(new ClaimMenuModel.Entry("nether", 0,0,"Me",0,0,true), "world"));
        assertEquals(ClaimMenuVisuals.Icon.ENTRY_SALE, ClaimMenuVisuals.entry(new ClaimMenuModel.Entry("world", 0,0,"Other",0,10,false), "world"));
        assertEquals(ClaimMenuVisuals.Icon.ENTRY_ACCESS, ClaimMenuVisuals.entry(new ClaimMenuModel.Entry("world", 0,0,"Other",0,0,false), "world"));
    }
    @Test void optInPreservesForeignMetadataAndNeverAddsIdentity() {
        var meta = mock(ItemMeta.class); var key = ClaimMenuVisuals.Icon.CLOSE.model();
        ClaimMenuVisuals.apply(meta, key, false); verifyNoInteractions(meta);
        ClaimMenuVisuals.apply(null, key, true); ClaimMenuVisuals.apply(meta, null, true); verifyNoInteractions(meta);
        when(meta.getItemModel()).thenReturn(new NamespacedKey("foreign", "model"));
        ClaimMenuVisuals.apply(meta, key, true); verify(meta, never()).setItemModel(any());
        when(meta.getItemModel()).thenReturn(null); when(meta.hasCustomModelData()).thenReturn(true);
        ClaimMenuVisuals.apply(meta, key, true); verify(meta, never()).setItemModel(any());
        when(meta.hasCustomModelData()).thenReturn(false); ClaimMenuVisuals.apply(meta, key, true);
        verify(meta).setItemModel(key); verify(meta, never()).getPersistentDataContainer();
    }
}
