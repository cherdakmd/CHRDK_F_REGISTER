package ru.cherdakmd.chrdkmobs;

import java.util.HashSet;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.Messages;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MobLootVisualsTest {
    @Test void everyLootKindHasItsOwnOptInModelAndForeignMetadataIsPreserved() {
        var keys = new HashSet<NamespacedKey>();
        for (var kind : MobLootItems.Kind.values()) {
            assertEquals("loot/" + kind.id, MobLootVisuals.model(kind).getKey());
            assertTrue(keys.add(MobLootVisuals.model(kind)));
        }
        assertEquals(7, keys.size());
        var meta = mock(ItemMeta.class); var kind = MobLootItems.Kind.ESSENCE_COMBAT;
        MobLootVisuals.applyNew(meta, kind, false); verifyNoInteractions(meta);
        when(meta.getItemModel()).thenReturn(new NamespacedKey("other", "model"));
        MobLootVisuals.applyNew(meta, kind, true); verify(meta, never()).setItemModel(any());
        when(meta.getItemModel()).thenReturn(null); when(meta.hasCustomModelData()).thenReturn(true);
        MobLootVisuals.applyNew(meta, kind, true); verify(meta, never()).setItemModel(any());
        when(meta.hasCustomModelData()).thenReturn(false); MobLootVisuals.applyNew(meta, kind, true);
        verify(meta).setItemModel(MobLootVisuals.model(kind));
    }

    @Test void newLootPreservesIdentityAndOldItemsRemainReadableWithEitherSetting() {
        for (var kind : MobLootItems.Kind.values()) for (boolean enabled : new boolean[]{false, true}) {
            var plugin = mock(MobsPlugin.class); when(plugin.getName()).thenReturn("CHRDK_Mobs");
            var config = new YamlConfiguration(); config.set("textures.loot-items", enabled);
            when(plugin.getConfig()).thenReturn(config);
            var parsed = mock(MobsConfig.class); when(parsed.loot()).thenReturn(new MobsConfig.Loot(40, 60));
            when(plugin.config()).thenReturn(parsed); when(plugin.messages()).thenReturn(mock(Messages.class));
            var meta = mock(ItemMeta.class); var pdc = mock(PersistentDataContainer.class);
            when(meta.getPersistentDataContainer()).thenReturn(pdc);
            var lootKey = new NamespacedKey("chrdk_mobs", "loot-v1");
            when(pdc.get(lootKey, PersistentDataType.STRING)).thenReturn(kind.id);
            var arguments = new java.util.ArrayList<Object>();
            try (var items = mockConstruction(ItemStack.class, (item, context) -> {
                arguments.add(context.arguments().get(0)); arguments.add(context.arguments().get(1));
                when(item.getItemMeta()).thenReturn(meta); when(item.hasItemMeta()).thenReturn(true);
            })) {
                var codec = new MobLootItems(plugin); var item = codec.create(kind, 3);
                assertSame(items.constructed().get(0), item); assertEquals(kind.material, arguments.get(0));
                assertEquals(3, arguments.get(1)); verify(pdc).set(lootKey, PersistentDataType.STRING, kind.id);
                if (enabled) verify(meta).setItemModel(MobLootVisuals.model(kind));
                else verify(meta, never()).setItemModel(any());
                verify(item).setItemMeta(meta); assertEquals(kind, codec.kind(item));
                // Reading an older item never edits it, even with visuals enabled.
                var old = mock(ItemStack.class); when(old.hasItemMeta()).thenReturn(true); when(old.getItemMeta()).thenReturn(meta);
                assertEquals(kind, codec.kind(old)); verify(old, never()).setItemMeta(any());
            }
        }
    }
}
