package ru.cherdakmd.chrdkfishing.bukkit;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkfishing.FishingModel;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FishingVisualsTest {
    @Test void modelNamesCoverAllRaritiesAndRodLevels() {
        assertEquals(new NamespacedKey("chrdk_fishing", "fish/mythic"), FishingVisuals.fish(FishingModel.Rarity.MYTHIC));
        for (int level = 2; level <= 10; level++)
            assertEquals("rod/level_" + level, FishingVisuals.rod(level).getKey());
        assertThrows(IllegalArgumentException.class, () -> FishingVisuals.rod(1));
    }
    @Test void neverOverwriteAnExternalModelOrSetAnythingWhenDisabled() {
        var meta = mock(ItemMeta.class);
        FishingVisuals.applyRod(meta, 2, false); verify(meta, never()).setItemModel(any());
        when(meta.getItemModel()).thenReturn(new NamespacedKey("other_plugin", "rod"));
        FishingVisuals.applyRod(meta, 2, true); verify(meta, never()).setItemModel(any());
        when(meta.getItemModel()).thenReturn(null);
        when(meta.hasCustomModelData()).thenReturn(true);
        FishingVisuals.applyRod(meta, 2, true); verify(meta, never()).setItemModel(any());
        when(meta.hasCustomModelData()).thenReturn(false);
        FishingVisuals.applyRod(meta, 2, true);
        verify(meta).setItemModel(FishingVisuals.rod(2));
        when(meta.getItemModel()).thenReturn(FishingVisuals.rod(2));
        FishingVisuals.applyRod(meta, 3, true);
        verify(meta).setItemModel(FishingVisuals.rod(3));
    }
}
