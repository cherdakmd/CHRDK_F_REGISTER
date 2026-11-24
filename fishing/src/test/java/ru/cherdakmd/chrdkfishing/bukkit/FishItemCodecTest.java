package ru.cherdakmd.chrdkfishing.bukkit;

import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkfishing.FishToken;
import ru.cherdakmd.chrdkfishing.FishingModel;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FishItemCodecTest {
    private static NamespacedKey key(String name) { return new NamespacedKey("chrdk_fishing", name); }
    @Test void readsOnlySingleValidSignedItemAndDetectsTampering() {
        byte[] secret = new byte[32];
        var token = FishToken.issue(secret, UUID.randomUUID(), new FishingModel.Catch("pike", FishingModel.Size.NORMAL, 40), 70);
        var item = mock(ItemStack.class); var meta = mock(ItemMeta.class); var pdc = mock(PersistentDataContainer.class);
        when(item.getType()).thenReturn(Material.COD); when(item.getAmount()).thenReturn(1);
        when(item.hasItemMeta()).thenReturn(true); when(item.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.has(key("kind"))).thenReturn(true);
        when(pdc.get(key("kind"), PersistentDataType.STRING)).thenReturn("fish-v1");
        when(pdc.get(key("fish_id"), PersistentDataType.STRING)).thenReturn(token.id().toString());
        when(pdc.get(key("species"), PersistentDataType.STRING)).thenReturn("pike");
        when(pdc.get(key("size"), PersistentDataType.STRING)).thenReturn("NORMAL");
        when(pdc.get(key("length_cm"), PersistentDataType.INTEGER)).thenReturn(40);
        when(pdc.get(key("quoted_points"), PersistentDataType.LONG)).thenReturn(70L);
        when(pdc.get(key("signature"), PersistentDataType.STRING)).thenReturn(token.mac());
        var codec = new FishItemCodec(secret);
        assertTrue(codec.marked(item)); assertEquals(token, codec.read(item).orElseThrow());
        when(pdc.get(key("quoted_points"), PersistentDataType.LONG)).thenReturn(999L);
        assertTrue(codec.marked(item)); assertTrue(codec.read(item).isEmpty());
        when(pdc.get(key("quoted_points"), PersistentDataType.LONG)).thenReturn(70L);
        when(item.getAmount()).thenReturn(2);
        assertTrue(codec.marked(item)); assertTrue(codec.read(item).isEmpty());
        when(item.getAmount()).thenReturn(1); when(item.getType()).thenReturn(Material.SALMON);
        assertTrue(codec.read(item).isEmpty());
    }
}
