package ru.cherdakmd.chrdkdaily;

import java.util.HashSet;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DailyMenuVisualsTest {
    @Test void eachCalendarStateHasItsOwnModel() {
        var models = new HashSet<NamespacedKey>();
        for (var icon : DailyMenuVisuals.Icon.values()) assertTrue(models.add(icon.model()));
        assertEquals(5, models.size());
    }
    @Test void disabledAndForeignMetadataRemainUnchanged() {
        var meta = mock(ItemMeta.class);
        DailyMenuVisuals.apply(meta, DailyMenuVisuals.Icon.TODAY, false); verifyNoInteractions(meta);
        DailyMenuVisuals.apply(null, DailyMenuVisuals.Icon.TODAY, true);
        when(meta.getItemModel()).thenReturn(new NamespacedKey("other", "model"));
        DailyMenuVisuals.apply(meta, DailyMenuVisuals.Icon.TODAY, true); verify(meta, never()).setItemModel(any());
        when(meta.getItemModel()).thenReturn(null); when(meta.hasCustomModelData()).thenReturn(true);
        DailyMenuVisuals.apply(meta, DailyMenuVisuals.Icon.TODAY, true); verify(meta, never()).setItemModel(any());
        when(meta.hasCustomModelData()).thenReturn(false);
        DailyMenuVisuals.apply(meta, DailyMenuVisuals.Icon.TODAY, true); verify(meta).setItemModel(DailyMenuVisuals.Icon.TODAY.model());
        verify(meta, never()).getPersistentDataContainer();
    }
}
