package ru.cherdakmd.chrdkfishing.bukkit;

import java.util.UUID;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.mockito.ArgumentCaptor;
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
    @Test void stampingPreservesOtherLoreAndReplacesOnlyManagedLines() {
        byte[] secret = new byte[32]; var token = RodToken.issue(secret, UUID.randomUUID(), 4);
        var original = mock(ItemStack.class); var copy = mock(ItemStack.class);
        var meta = mock(ItemMeta.class); var pdc = mock(PersistentDataContainer.class);
        when(original.getType()).thenReturn(Material.FISHING_ROD); when(original.getAmount()).thenReturn(1);
        when(original.clone()).thenReturn(copy); when(copy.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        var ownerLine = Component.text("Описание игрока");
        var previous = Component.text("Старый уровень").insertion("chrdk_fishing:rod_info_v1");
        when(meta.lore()).thenReturn(List.of(ownerLine, previous));
        var result = new RodItemCodec(secret).stamp(original, token);
        assertSame(copy, result); verify(original, never()).setItemMeta(any());
        @SuppressWarnings("unchecked") ArgumentCaptor<List<Component>> captured = ArgumentCaptor.forClass(List.class);
        verify(meta).lore(captured.capture()); var lines = captured.getValue();
        assertEquals(4, lines.size()); assertEquals(ownerLine, lines.getFirst());
        var plain = PlainTextComponentSerializer.plainText();
        assertTrue(plain.serialize(lines.get(1)).contains("уровень 4"));
        assertTrue(plain.serialize(lines.get(2)).contains("+6%"));
        assertTrue(plain.serialize(lines.get(3)).contains("+3"));
        verify(copy).setItemMeta(meta);
    }
    @Test void upgradesApplyEachLevelModelOnlyToTheClonedResult() {
        byte[] secret = new byte[32];
        for (int level = 2; level <= 10; level++) for (boolean enabled : new boolean[]{false, true}) {
            var original = mock(ItemStack.class); var copy = mock(ItemStack.class);
            var meta = mock(ItemMeta.class); var pdc = mock(PersistentDataContainer.class);
            when(original.getType()).thenReturn(Material.FISHING_ROD); when(original.getAmount()).thenReturn(1);
            when(original.clone()).thenReturn(copy); when(copy.getItemMeta()).thenReturn(meta);
            when(meta.getPersistentDataContainer()).thenReturn(pdc);
            if (level > 2) when(meta.getItemModel()).thenReturn(FishingVisuals.rod(level - 1));
            var token = RodToken.issue(secret, UUID.randomUUID(), level);
            assertSame(copy, new RodItemCodec(secret, enabled).stamp(original, token));
            if (enabled) verify(meta).setItemModel(FishingVisuals.rod(level));
            else verify(meta, never()).setItemModel(any());
            verify(original, never()).setItemMeta(any()); verify(copy).setItemMeta(meta);
            verify(pdc).set(key("rod_level"), PersistentDataType.INTEGER, level);
            verify(pdc).set(key("rod_signature"), PersistentDataType.STRING, token.mac());
        }
    }
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
