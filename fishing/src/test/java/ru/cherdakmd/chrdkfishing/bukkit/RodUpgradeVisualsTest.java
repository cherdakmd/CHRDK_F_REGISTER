package ru.cherdakmd.chrdkfishing.bukkit;

import java.util.HashSet;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RodUpgradeVisualsTest {
    @Test void statesAndRodLevelsHaveDistinctModels() {
        var keys = new HashSet<NamespacedKey>();
        for (var icon : RodUpgradeVisuals.Icon.values()) assertTrue(keys.add(icon.model()));
        assertEquals(8, keys.size());
        assertEquals(RodUpgradeVisuals.Icon.SELECTED.model(), RodUpgradeVisuals.selected(1));
        for (int level = 2; level <= 10; level++) {
            var model = RodUpgradeVisuals.selected(level);
            assertEquals(FishingVisuals.rod(level), model); assertTrue(keys.add(model));
        }
        assertThrows(IllegalArgumentException.class, () -> RodUpgradeVisuals.selected(0));
        assertThrows(IllegalArgumentException.class, () -> RodUpgradeVisuals.selected(11));
    }
    @Test void disabledAndForeignMetadataAreNotOverwritten() {
        var meta = mock(ItemMeta.class);
        RodUpgradeVisuals.rod(meta, 2, false); RodUpgradeVisuals.button(meta, RodUpgradeVisuals.Icon.CONFIRM, false); verifyNoInteractions(meta);
        when(meta.getItemModel()).thenReturn(new NamespacedKey("other", "model"));
        RodUpgradeVisuals.rod(meta, 2, true); RodUpgradeVisuals.button(meta, RodUpgradeVisuals.Icon.CONFIRM, true);
        verify(meta, never()).setItemModel(any());
        when(meta.getItemModel()).thenReturn(null); when(meta.hasCustomModelData()).thenReturn(true);
        RodUpgradeVisuals.rod(meta, 2, true); RodUpgradeVisuals.button(meta, RodUpgradeVisuals.Icon.CONFIRM, true);
        verify(meta, never()).setItemModel(any());
        when(meta.hasCustomModelData()).thenReturn(false);
        RodUpgradeVisuals.rod(meta, 2, true); verify(meta).setItemModel(FishingVisuals.rod(2));
        RodUpgradeVisuals.button(meta, RodUpgradeVisuals.Icon.CONFIRM, true); verify(meta).setItemModel(RodUpgradeVisuals.Icon.CONFIRM.model());
    }
}
