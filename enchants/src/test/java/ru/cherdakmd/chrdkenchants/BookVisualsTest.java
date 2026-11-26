package ru.cherdakmd.chrdkenchants;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BookVisualsTest {
    private static final NamespacedKey CUSTOM = new NamespacedKey("chrdk_enchants", "custom_enchants");
    private static final NamespacedKey SOURCE = new NamespacedKey("chrdk_enchants", "book_source_v1");
    private static final NamespacedKey FOREIGN = new NamespacedKey("other", "data");

    private static final class Book {
        final EnchantmentStorageMeta meta = mock(EnchantmentStorageMeta.class);
        final PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        final AtomicReference<NamespacedKey> model = new AtomicReference<>();
        final Map<String, Integer> vanilla = new HashMap<>();
        final HashSet<NamespacedKey> keys = new HashSet<>();
        String custom;
        boolean validType = true;
        Book() {
            when(meta.getPersistentDataContainer()).thenReturn(pdc);
            when(meta.getItemModel()).thenAnswer(c -> model.get());
            doAnswer(c -> { model.set(c.getArgument(0)); return null; }).when(meta).setItemModel(nullable(NamespacedKey.class));
            when(pdc.getKeys()).thenAnswer(c -> keys);
            when(pdc.has(any(NamespacedKey.class))).thenAnswer(c -> keys.contains(c.getArgument(0)));
            when(pdc.has(eq(CUSTOM), eq(PersistentDataType.STRING))).thenAnswer(c -> validType);
            when(pdc.get(eq(CUSTOM), eq(PersistentDataType.STRING))).thenAnswer(c -> custom);
        }
        void custom(String value) { keys.add(CUSTOM); custom = value; }
        void noCustom() { keys.remove(CUSTOM); custom = null; }
        void vanilla(String name, int level) { vanilla.put(name, level); }
        boolean apply(boolean enabled) { return BookVisuals.apply(meta, CUSTOM, SOURCE, enabled, () -> vanilla); }
        String selected() { return model.get() == null ? null : model.get().toString(); }
    }

    @Test void transitionsFollowActualCustomVanillaAndMixedContents() {
        Book b = new Book(); b.custom("lifesteal:1");
        assertTrue(b.apply(true)); assertEquals("chrdk_enchants:book/custom/lifesteal", b.selected());
        b.vanilla("minecraft:sharpness", 2); assertTrue(b.apply(true)); assertEquals("chrdk_enchants:book/mixed/both", b.selected());
        b.noCustom(); assertTrue(b.apply(true)); assertEquals("chrdk_enchants:book/vanilla/sharpness", b.selected());
        b.vanilla("minecraft:unbreaking", 3); assertTrue(b.apply(true)); assertEquals("chrdk_enchants:book/mixed/vanilla", b.selected());
        b.vanilla.clear(); b.custom("lifesteal:1;thunderstrike:1");
        assertTrue(b.apply(true)); assertEquals("chrdk_enchants:book/mixed/custom", b.selected());
        b.noCustom(); assertTrue(b.apply(true)); assertNull(b.model.get());
        assertFalse(b.apply(true));
        verify(b.pdc, never()).set(any(), any(), any());
        verify(b.pdc, never()).remove(any(NamespacedKey.class));
        verify(b.meta, never()).addStoredEnchant(any(), anyInt(), anyBoolean());
        verify(b.meta, never()).removeStoredEnchant(any());
    }

    @Test void foreignAndInvalidDataFailClosedWithoutDamagingIt() {
        Book b = new Book(); b.custom("lifesteal:1");
        var other = new NamespacedKey("other", "model"); b.model.set(other);
        assertFalse(b.apply(true)); assertEquals(other, b.model.get());
        b.model.set(null); when(b.meta.hasCustomModelData()).thenReturn(true);
        assertFalse(b.apply(true)); assertNull(b.model.get());
        when(b.meta.hasCustomModelData()).thenReturn(false); b.keys.add(FOREIGN);
        assertFalse(b.apply(true)); assertNull(b.model.get());
        b.keys.remove(FOREIGN); b.validType = false;
        assertFalse(b.apply(true)); assertNull(b.model.get());
        b.validType = true; b.custom("lifesteal:1;unknown:1");
        assertFalse(b.apply(true)); assertNull(b.model.get());
        b.custom("lifesteal:1"); b.vanilla("other:enchanted", 1);
        assertFalse(b.apply(true)); assertNull(b.model.get());
        b.vanilla.clear(); assertTrue(b.apply(true));
        b.keys.add(FOREIGN); assertTrue(b.apply(true)); assertNull(b.model.get());
        assertTrue(b.keys.contains(FOREIGN));
    }

    @Test void disabledOnlyMaintainsAlreadyOwnedModelsAndNeverAdoptsNewOnes() {
        Book b = new Book(); b.custom("lifesteal:1");
        assertFalse(b.apply(false)); assertNull(b.model.get());
        assertTrue(b.apply(true)); b.vanilla("minecraft:sharpness", 1);
        assertTrue(b.apply(false)); assertEquals("chrdk_enchants:book/mixed/both", b.selected());
        b.custom("corrupt"); assertTrue(b.apply(false)); assertNull(b.model.get());
        assertFalse(b.apply(false));
        assertFalse(BookVisuals.owned(new NamespacedKey("chrdk_enchants", "book/custom/not_registered")));
        assertFalse(BookVisuals.owned(new NamespacedKey("chrdk_enchants", "other")));
        assertFalse(BookVisuals.owned(new NamespacedKey("thirdparty", "book/custom/lifesteal")));
    }

    @Test void runtimeHooksRecalculateAfterTheActualContentChanges() throws Exception {
        var visuals = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/ru/cherdakmd/chrdkenchants/BookVisuals.java"));
        assertTrue(visuals.contains("storage.getStoredEnchants().entrySet()"));
        assertTrue(visuals.contains("entry.getKey().getKey().toString()"));
        var items = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/ru/cherdakmd/chrdkenchants/EnchantItems.java"));
        assertTrue(items.contains("BookVisuals.apply(meta, key, bookSource"));
        var workshop = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/ru/cherdakmd/chrdkenchants/EnchantWorkshop.java"));
        assertTrue(workshop.contains("refreshBookVisual(after[slot])"));
        assertTrue(workshop.contains("refreshBookVisual(extracted)"));
        assertTrue(workshop.indexOf("refreshBookVisual(after[slot])") > workshop.indexOf("originalMeta.removeStoredEnchant(selected)"));
        var anvil = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/ru/cherdakmd/chrdkenchants/VanillaMergeListener.java"));
        assertTrue(anvil.contains("plugin.items().refreshBookVisual(out)"));
        assertTrue(anvil.contains("plugin.items().marked(left)"));
        assertTrue(anvil.contains("plugin.items().marked(right)"));
        var plugin = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/ru/cherdakmd/chrdkenchants/EnchantsPlugin.java"));
        assertFalse(plugin.contains("new BookVisualListener("));
        assertFalse(java.nio.file.Files.exists(java.nio.file.Path.of("src/main/java/ru/cherdakmd/chrdkenchants/BookVisualListener.java")));
    }

    @Test void combineIgnoresOnlyOurModelAndNeverModifiesInputs() {
        var a = mock(ItemStack.class); var b = mock(ItemStack.class);
        var ac = mock(ItemStack.class); var bc = mock(ItemStack.class);
        var am = mock(EnchantmentStorageMeta.class); var bm = mock(EnchantmentStorageMeta.class);
        when(a.clone()).thenReturn(ac); when(b.clone()).thenReturn(bc);
        when(ac.getItemMeta()).thenReturn(am); when(bc.getItemMeta()).thenReturn(bm);
        when(am.getItemModel()).thenReturn(BookVisualPolicy.select(Map.of("lifesteal", 1), Map.of()));
        var foreign = new NamespacedKey("other", "model"); when(bm.getItemModel()).thenReturn(foreign);
        when(ac.isSimilar(bc)).thenReturn(false);
        assertFalse(BookVisuals.equivalentForCombine(a, b));
        verify(am).setItemModel(null); verify(ac).setItemMeta(am);
        verify(bm, never()).setItemModel(any()); verify(bc, never()).setItemMeta(any());
        verify(a, never()).setItemMeta(any()); verify(b, never()).setItemMeta(any());
        when(bm.getItemModel()).thenReturn(null); when(ac.isSimilar(bc)).thenReturn(true);
        assertTrue(BookVisuals.equivalentForCombine(a, b));
    }
}
