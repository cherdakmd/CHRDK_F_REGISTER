package ru.cherdakmd.chrdkfishing.bukkit;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkfishing.BaitCatalog;
import ru.cherdakmd.chrdkfishing.BaitToken;
import java.util.HashSet;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BaitItemVisualsTest {
    private static NamespacedKey key(String name) { return new NamespacedKey("chrdk_fishing", name); }

    @Test void modelsCoverAllKindsWithoutOverwritingForeignMetadata() {
        var keys = new HashSet<NamespacedKey>();
        for (var bait : BaitCatalog.all()) {
            var model = FishingVisuals.bait(bait.id());
            assertEquals("chrdk_fishing", model.getNamespace()); assertEquals("bait/" + bait.id(), model.getKey());
            assertTrue(keys.add(model));
        }
        assertEquals(3, keys.size()); assertThrows(IllegalArgumentException.class, () -> FishingVisuals.bait("unknown"));
        var meta = mock(ItemMeta.class); FishingVisuals.applyNewBait(meta, "quick", false); verifyNoInteractions(meta);
        when(meta.getItemModel()).thenReturn(new NamespacedKey("other", "bait"));
        FishingVisuals.applyNewBait(meta, "quick", true); verify(meta, never()).setItemModel(any());
        when(meta.getItemModel()).thenReturn(null); when(meta.hasCustomModelData()).thenReturn(true);
        FishingVisuals.applyNewBait(meta, "quick", true); verify(meta, never()).setItemModel(any());
        when(meta.hasCustomModelData()).thenReturn(false); FishingVisuals.applyNewBait(meta, "quick", true);
        verify(meta).setItemModel(FishingVisuals.bait("quick"));
    }

    @Test void newAndLegacyDeliveriesKeepTheSameSignatureAndRemainReadable() {
        byte[] secret = new byte[32];
        for (var bait : BaitCatalog.all()) for (boolean textures : new boolean[]{false, true}) {
            var token = BaitToken.issue(secret, UUID.randomUUID(), bait.id());
            var meta = mock(ItemMeta.class); var pdc = mock(PersistentDataContainer.class);
            when(meta.getPersistentDataContainer()).thenReturn(pdc);
            when(pdc.get(key("bait_kind"), PersistentDataType.STRING)).thenReturn(token.kind());
            when(pdc.get(key("bait_id"), PersistentDataType.STRING)).thenReturn(token.id().toString());
            when(pdc.get(key("bait_signature"), PersistentDataType.STRING)).thenReturn(token.mac());
            try (var created = mockConstruction(ItemStack.class, (item, context) -> {
                when(item.getItemMeta()).thenReturn(meta); when(item.hasItemMeta()).thenReturn(true);
                when(item.getType()).thenReturn(Material.PAPER); when(item.getAmount()).thenReturn(1);
            })) {
                var codec = new BaitItemCodec(secret, textures); var item = codec.build(token);
                assertSame(created.constructed().get(0), item); verify(item).setItemMeta(meta);
                verify(pdc).set(key("bait_kind"), PersistentDataType.STRING, token.kind());
                verify(pdc).set(key("bait_id"), PersistentDataType.STRING, token.id().toString());
                verify(pdc).set(key("bait_signature"), PersistentDataType.STRING, token.mac());
                if (textures) verify(meta).setItemModel(FishingVisuals.bait(bait.id()));
                else verify(meta, never()).setItemModel(any());
                assertEquals(token, codec.read(item).orElseThrow());
                assertEquals(token, new BaitItemCodec(secret, !textures).read(item).orElseThrow());
                // A valid-looking appearance never substitutes for authentication.
                when(pdc.get(key("bait_kind"), PersistentDataType.STRING)).thenReturn(bait.id().equals("quick") ? "rare" : "quick");
                assertTrue(codec.read(item).isEmpty());
            }
        }
    }
}
