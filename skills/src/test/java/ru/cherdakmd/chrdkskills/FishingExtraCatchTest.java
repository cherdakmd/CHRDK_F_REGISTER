package ru.cherdakmd.chrdkskills;

import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FishingExtraCatchTest {
    static NamespacedKey key(String id) { return new NamespacedKey("chrdk_fishing", id); }
    static ItemStack fish(String id) {
        var item = mock(ItemStack.class); var meta = mock(ItemMeta.class); var pdc = mock(PersistentDataContainer.class);
        when(item.hasItemMeta()).thenReturn(true); when(item.getItemMeta()).thenReturn(meta);
        when(item.getAmount()).thenReturn(1); when(item.getType()).thenReturn(Material.COD);
        when(item.clone()).thenReturn(item); when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.has(key("kind"))).thenReturn(true); when(pdc.get(key("kind"), PersistentDataType.STRING)).thenReturn("fish-v1");
        when(pdc.get(key("fish_id"), PersistentDataType.STRING)).thenReturn(id);
        when(pdc.get(key("species"), PersistentDataType.STRING)).thenReturn("pike");
        when(pdc.get(key("size"), PersistentDataType.STRING)).thenReturn("NORMAL");
        when(pdc.get(key("length_cm"), PersistentDataType.INTEGER)).thenReturn(40);
        when(pdc.get(key("quoted_points"), PersistentDataType.LONG)).thenReturn(70L);
        when(pdc.get(key("signature"), PersistentDataType.STRING)).thenReturn("signature");
        return item;
    }
    @Test void ordinaryLootStillClonesWithoutAnyProvider() {
        var original = mock(ItemStack.class); var copy = mock(ItemStack.class);
        when(original.clone()).thenReturn(copy);
        assertSame(copy, FishingExtraCatch.extra(mock(Player.class), original, null));
    }
    @Test void customFishFailsClosedWhenProviderMissingOrIdRepeated() {
        var player = mock(Player.class); String id = UUID.randomUUID().toString(); var source = fish(id);
        assertNull(FishingExtraCatch.extra(player, source, null));
        var repeated = fish(id);
        assertNull(FishingExtraCatch.extra(player, source, (p, item) -> repeated));
        assertNull(FishingExtraCatch.extra(player, source, (p, item) -> { throw new IllegalStateException(); }));
        assertNull(FishingExtraCatch.extra(player, source, (p, item) -> null));
    }
    @Test void validDistinctIdIsRequiredAndSameFishPropertiesArePreserved() {
        var player = mock(Player.class); var source = fish(UUID.randomUUID().toString());
        var second = fish(UUID.randomUUID().toString());
        assertSame(second, FishingExtraCatch.extra(player, source, (p, item) -> second));
        var pdc = second.getItemMeta().getPersistentDataContainer();
        when(pdc.get(key("quoted_points"), PersistentDataType.LONG)).thenReturn(999L);
        assertNull(FishingExtraCatch.extra(player, source, (p, item) -> second));
        when(pdc.get(key("quoted_points"), PersistentDataType.LONG)).thenReturn(70L);
        when(pdc.get(key("fish_id"), PersistentDataType.STRING)).thenReturn("bad-id");
        assertNull(FishingExtraCatch.extra(player, source, (p, item) -> second));
    }
}
