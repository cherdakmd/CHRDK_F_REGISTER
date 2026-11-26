package ru.cherdakmd.diamondshop;

import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.api.ShopBookKey;
import ru.cherdakmd.chrdkregister.api.ShopBooks;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShopBookVisualsTest {
    @Test void knownModelsAreOptInAndForeignMetadataIsPreserved() {
        var custom = new ShopBookKey.Spec("lifesteal", 1, true);
        var vanilla = new ShopBookKey.Spec("sharpness", 4, false);
        assertEquals("chrdk_enchants:book/custom/lifesteal", ShopBookVisuals.model(custom).toString());
        assertEquals("chrdk_enchants:book/vanilla/sharpness", ShopBookVisuals.model(vanilla).toString());
        assertNull(ShopBookVisuals.model(new ShopBookKey.Spec("unknown", 1, true)));
        assertNull(ShopBookVisuals.model(new ShopBookKey.Spec("unknown", 1, false)));
        var meta = mock(ItemMeta.class); assertFalse(ShopBookVisuals.applyNew(meta, custom, false)); verifyNoInteractions(meta);
        when(meta.getItemModel()).thenReturn(new NamespacedKey("other", "book"));
        assertFalse(ShopBookVisuals.applyNew(meta, custom, true)); verify(meta, never()).setItemModel(any());
        when(meta.getItemModel()).thenReturn(null); when(meta.hasCustomModelData()).thenReturn(true);
        assertFalse(ShopBookVisuals.applyNew(meta, custom, true)); verify(meta, never()).setItemModel(any());
        when(meta.hasCustomModelData()).thenReturn(false);
        assertTrue(ShopBookVisuals.applyNew(meta, custom, true)); verify(meta).setItemModel(ShopBookVisuals.model(custom));
        verify(meta, never()).getPersistentDataContainer();
    }
    @Test void purchaseOnlyDecoratesNewTemplateAndKeepsExistingStorage() throws Exception {
        var spec = new ShopBookKey.Spec("lifesteal", 1, true);
        for (boolean enabled : new boolean[]{false, true}) {
            var template = mock(ItemStack.class); var result = mock(ItemStack.class); var meta = mock(ItemMeta.class);
            when(template.getItemMeta()).thenReturn(meta); when(template.clone()).thenReturn(result);
            var old = mock(ItemStack.class);
            var items = new ItemStack[36]; items[1] = old;
            try (var books = mockStatic(ShopBooks.class)) {
                books.when(() -> ShopBooks.create(eq(spec), any())).thenReturn(template);
                assertNull(ShopService.insertBooks(items, spec, 1, enabled));
            }
            assertSame(result, items[0]); assertSame(old, items[1]); verify(old, never()).setItemMeta(any());
            if (enabled) { verify(meta).setItemModel(ShopBookVisuals.model(spec)); verify(template).setItemMeta(meta); }
            else { verify(meta, never()).setItemModel(any()); verify(template, never()).setItemMeta(any()); }
        }
        String service = Files.readString(Path.of("src/main/java/ru/cherdakmd/diamondshop/ShopService.java"));
        assertTrue(service.indexOf("insertBooks(after, spec, units, bookTextures.getAsBoolean())") < service.indexOf("ChargeInventory.encode(after)"));
        assertTrue(service.contains("ShopInventory.valid(t)"));
    }
    @Test void legacyAndNewShopBooksAreBothRecognizedByTheirUnchangedMarkers() {
        var spec = new ShopBookKey.Spec("lifesteal", 1, true);
        for (var model : new NamespacedKey[]{null, ShopBookVisuals.model(spec)}) {
            var item = mock(ItemStack.class); var meta = mock(ItemMeta.class); var pdc = mock(PersistentDataContainer.class);
            when(item.getType()).thenReturn(Material.ENCHANTED_BOOK); when(item.hasItemMeta()).thenReturn(true); when(item.getItemMeta()).thenReturn(meta);
            when(meta.getPersistentDataContainer()).thenReturn(pdc); when(meta.getItemModel()).thenReturn(model);
            var book = new NamespacedKey("chrdk_diamond_shop", "book_key");
            var source = new NamespacedKey("chrdkenchants", "book_source_v1");
            var custom = new NamespacedKey("chrdkenchants", "custom_enchants");
            when(pdc.get(source, PersistentDataType.STRING)).thenReturn("v1:shop");
            when(pdc.has(book, PersistentDataType.STRING)).thenReturn(true);
            when(pdc.get(book, PersistentDataType.STRING)).thenReturn(spec.key());
            when(pdc.get(custom, PersistentDataType.STRING)).thenReturn("lifesteal:1");
            assertTrue(ShopBooks.matches(item, spec)); verify(item, never()).setItemMeta(any());
        }
    }
}
