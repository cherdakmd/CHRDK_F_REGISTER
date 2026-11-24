package ru.cherdakmd.chrdkfishing.bukkit;

import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkfishing.RodToken;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RodItemCodecTest {
    private static NamespacedKey key(String name) { return new NamespacedKey("chrdk_fishing", name); }
    @Test void signedRodCannotBeRetieredOrPassedOffAsVanilla() {
        byte[] secret = new byte[32]; var token = RodToken.issue(secret, UUID.randomUUID(), 4);
        var item = mock(ItemStack.class); var meta = mock(ItemMeta.class); var pdc = mock(PersistentDataContainer.class);
        when(item.getType()).thenReturn(Material.FISHING_ROD); when(item.getAmount()).thenReturn(1);
        when(item.hasItemMeta()).thenReturn(true); when(item.getItemMeta()).thenReturn(meta); when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.has(key("rod_kind"))).thenReturn(true);
        when(pdc.get(key("rod_kind"), PersistentDataType.STRING)).thenReturn("rod-v1");
        when(pdc.get(key("rod_id"), PersistentDataType.STRING)).thenReturn(token.id().toString());
        when(pdc.get(key("rod_level"), PersistentDataType.INTEGER)).thenReturn(4);
        when(pdc.get(key("rod_signature"), PersistentDataType.STRING)).thenReturn(token.mac());
        var codec = new RodItemCodec(secret);
        assertTrue(codec.marked(item)); assertEquals(token, codec.read(item).orElseThrow());
        when(pdc.get(key("rod_level"), PersistentDataType.INTEGER)).thenReturn(10);
        assertTrue(codec.marked(item)); assertTrue(codec.read(item).isEmpty());
        when(pdc.get(key("rod_level"), PersistentDataType.INTEGER)).thenReturn(4);
        when(item.getAmount()).thenReturn(2); assertTrue(codec.read(item).isEmpty());
    }
}
