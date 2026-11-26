package ru.cherdakmd.chrdkfishing.bukkit;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkfishing.FishCatalog;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FishAlbumVisualsTest {
    @Test void allButtonsAndFoundSpeciesHaveModelsAndUnknownIsIdentical() {
        var icons = new HashSet<NamespacedKey>();
        for (var icon : FishAlbumVisuals.Icon.values()) {
            assertEquals("chrdk_fishing", icon.model().getNamespace());
            assertEquals("menu/album/" + icon.id, icon.model().getKey());
            assertTrue(icons.add(icon.model()));
        }
        assertEquals(7, icons.size());
        var fishModels = new HashSet<NamespacedKey>();
        for (var species : FishCatalog.all()) {
            var found = mock(ItemMeta.class); FishAlbumVisuals.entry(found, species.id(), true, true);
            verify(found).setItemModel(FishingVisuals.fish(species.id()));
            assertTrue(fishModels.add(FishingVisuals.fish(species.id())));
            var hidden = mock(ItemMeta.class); FishAlbumVisuals.entry(hidden, species.id(), false, true);
            verify(hidden).setItemModel(FishAlbumVisuals.Icon.UNKNOWN.model());
        }
        assertEquals(FishCatalog.all().size(), fishModels.size());
        var hiddenInvalid = mock(ItemMeta.class); FishAlbumVisuals.entry(hiddenInvalid, "not-a-species", false, true);
        verify(hiddenInvalid).setItemModel(FishAlbumVisuals.Icon.UNKNOWN.model());
    }

    @Test void flagAndForeignMetadataRemainUntouched() {
        var meta = mock(ItemMeta.class);
        FishAlbumVisuals.entry(meta, "ruffe", true, false);
        FishAlbumVisuals.button(meta, FishAlbumVisuals.Icon.NEXT, false);
        verifyNoInteractions(meta);
        var foreign = new NamespacedKey("other", "model"); when(meta.getItemModel()).thenReturn(foreign);
        FishAlbumVisuals.entry(meta, "ruffe", true, true); FishAlbumVisuals.button(meta, FishAlbumVisuals.Icon.NEXT, true);
        verify(meta, never()).setItemModel(any());
        when(meta.getItemModel()).thenReturn(null); when(meta.hasCustomModelData()).thenReturn(true);
        FishAlbumVisuals.entry(meta, "ruffe", true, true); FishAlbumVisuals.button(meta, FishAlbumVisuals.Icon.NEXT, true);
        verify(meta, never()).setItemModel(any());
        FishAlbumVisuals.entry(null, "ruffe", true, true); FishAlbumVisuals.button(meta, null, true);
        verify(meta, never()).setItemModel(any());
    }
}
