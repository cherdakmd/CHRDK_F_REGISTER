package ru.cherdakmd.chrdkenchants;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NewTableBookVisualListenerTest {
    private Enchantment enchant(String namespace, String key) {
        var enchant = mock(Enchantment.class); when(enchant.getKey()).thenReturn(new NamespacedKey(namespace, key)); return enchant;
    }
    @Test void modelsFollowOnlyKnownValidOfferedEnchantments() {
        var sharp = enchant("minecraft", "sharpness"); var unbreaking = enchant("minecraft", "unbreaking");
        assertEquals(new NamespacedKey("chrdk_enchants", "book/vanilla/sharpness"), NewTableBookVisualListener.model(Map.of(sharp, 3)));
        assertEquals(new NamespacedKey("chrdk_enchants", "book/mixed/vanilla"), NewTableBookVisualListener.model(Map.of(sharp, 3, unbreaking, 1)));
        assertNull(NewTableBookVisualListener.model(Map.of(sharp, 0))); assertNull(NewTableBookVisualListener.model(Map.of()));
        assertNull(NewTableBookVisualListener.model(Map.of(enchant("other", "sharpness"), 1)));
        assertNull(NewTableBookVisualListener.model(Map.of(enchant("minecraft", "unknown"), 1)));
    }
    @Test void inputIsNeverMutatedAndCopyIsUsedOnlyForSupportedNewOutput() throws Exception {
        var event = mock(EnchantItemEvent.class); var original = mock(ItemStack.class); var copy = mock(ItemStack.class);
        var meta = mock(ItemMeta.class); var cloneMeta = mock(ItemMeta.class); var pdc = mock(PersistentDataContainer.class);
        when(event.getItem()).thenReturn(original); when(event.getEnchantsToAdd()).thenReturn(Map.of(enchant("minecraft", "sharpness"), 3));
        when(original.getType()).thenReturn(Material.BOOK); when(original.getAmount()).thenReturn(1);
        when(original.getItemMeta()).thenReturn(meta); when(meta.getPersistentDataContainer()).thenReturn(pdc); when(pdc.getKeys()).thenReturn(Set.of());
        when(original.clone()).thenReturn(copy); when(copy.getItemMeta()).thenReturn(cloneMeta); when(copy.setItemMeta(cloneMeta)).thenReturn(true);
        var enabled = new AtomicBoolean(false); var listener = new NewTableBookVisualListener(enabled::get);
        listener.onEnchant(event); verify(original, never()).clone();
        enabled.set(true); when(meta.hasCustomModelData()).thenReturn(true); listener.onEnchant(event); verify(original, never()).clone();
        when(meta.hasCustomModelData()).thenReturn(false); when(meta.getItemModel()).thenReturn(new NamespacedKey("other", "book")); listener.onEnchant(event); verify(original, never()).clone();
        when(meta.getItemModel()).thenReturn(null); when(pdc.getKeys()).thenReturn(Set.of(new NamespacedKey("other", "marker"))); listener.onEnchant(event); verify(original, never()).clone();
        when(pdc.getKeys()).thenReturn(Set.of()); when(original.getAmount()).thenReturn(2); listener.onEnchant(event); verify(original, never()).clone();
        when(original.getAmount()).thenReturn(1); listener.onEnchant(event);
        verify(cloneMeta).setItemModel(new NamespacedKey("chrdk_enchants", "book/vanilla/sharpness"));
        verify(event).setItem(copy); verify(original, never()).setItemMeta(any()); verify(meta, never()).setItemModel(any());
        var annotation = NewTableBookVisualListener.class.getDeclaredMethod("onEnchant", EnchantItemEvent.class).getAnnotation(EventHandler.class);
        assertEquals(EventPriority.HIGHEST, annotation.priority()); assertTrue(annotation.ignoreCancelled());
    }
}
