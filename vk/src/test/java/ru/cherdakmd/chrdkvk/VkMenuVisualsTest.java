package ru.cherdakmd.chrdkvk;

import java.util.HashSet;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VkMenuVisualsTest {
    @Test void fixedModelsCoverAllDisplayStatesWithoutPersonalData() {
        var models = new HashSet<NamespacedKey>();
        for (var icon : VkMenuVisuals.Icon.values()) {
            assertTrue(models.add(icon.model())); assertEquals("chrdk_vk", icon.model().getNamespace());
            assertTrue(icon.model().getKey().matches("menu/[a-z_]+"));
        }
        assertEquals(12, models.size());
        assertEquals(VkMenuVisuals.Icon.NOTIFY_UNLINKED, VkMenuVisuals.notify(false, true));
        assertEquals(VkMenuVisuals.Icon.NOTIFY_UNLINKED, VkMenuVisuals.notify(false, false));
        assertEquals(VkMenuVisuals.Icon.NOTIFY_ON, VkMenuVisuals.notify(true, true));
        assertEquals(VkMenuVisuals.Icon.NOTIFY_OFF, VkMenuVisuals.notify(true, false));
    }
    @Test void modelIsOptInAndDoesNotOverrideMetadataOrAddIdentity() {
        var meta = mock(ItemMeta.class); var icon = VkMenuVisuals.Icon.CLOSE;
        VkMenuVisuals.apply(meta, icon, false); verifyNoInteractions(meta);
        VkMenuVisuals.apply(null, icon, true); VkMenuVisuals.apply(meta, null, true); verifyNoInteractions(meta);
        when(meta.getItemModel()).thenReturn(new NamespacedKey("foreign", "model"));
        VkMenuVisuals.apply(meta, icon, true); verify(meta, never()).setItemModel(any());
        when(meta.getItemModel()).thenReturn(null); when(meta.hasCustomModelData()).thenReturn(true);
        VkMenuVisuals.apply(meta, icon, true); verify(meta, never()).setItemModel(any());
        when(meta.hasCustomModelData()).thenReturn(false); VkMenuVisuals.apply(meta, icon, true);
        verify(meta).setItemModel(icon.model()); verify(meta, never()).getPersistentDataContainer();
    }
}
