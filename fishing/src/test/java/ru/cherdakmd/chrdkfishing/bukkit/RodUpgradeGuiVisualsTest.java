package ru.cherdakmd.chrdkfishing.bukkit;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkfishing.RodToken;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RodUpgradeGuiVisualsTest {
    @Test void virtualStatesKeepOriginalRodAndSeparateConfirmation() {
        for (boolean enabled : new boolean[]{false, true}) {
            var plugin = mock(Plugin.class); var config = new YamlConfiguration(); config.set("textures.rod-menu", enabled);
            when(plugin.getConfig()).thenReturn(config);
            var engine = mock(RodUpgradeEngine.class); var codec = mock(RodItemCodec.class);
            var gui = new RodUpgradeGui(plugin, engine, codec); gui.setBack(p -> {});
            var holder = new RodUpgradeGui.Holder(UUID.randomUUID()); var top = mock(Inventory.class); holder.inventory = top;
            var player = mock(Player.class); var inventory = mock(PlayerInventory.class); when(player.getInventory()).thenReturn(inventory);
            var rod = mock(ItemStack.class); when(rod.getType()).thenReturn(Material.FISHING_ROD); when(rod.getAmount()).thenReturn(1);
            var storage = new ItemStack[36]; storage[4] = rod; when(inventory.getStorageContents()).thenReturn(storage);
            var token = RodToken.issue(new byte[32], UUID.randomUUID(), 2); when(codec.read(rod)).thenReturn(Optional.of(token));
            Map<Integer, ItemStack> shown = new HashMap<>();
            doAnswer(c -> { shown.clear(); return null; }).when(top).clear();
            doAnswer(c -> { shown.put(c.getArgument(0), c.getArgument(1)); return null; }).when(top).setItem(anyInt(), nullable(ItemStack.class));
            try (var items = mockConstruction(ItemStack.class, (item, context) -> {
                var meta = mock(ItemMeta.class); when(item.getItemMeta()).thenReturn(meta);
            })) {
                gui.render(holder, player);
                checkModel(shown.get(RodUpgradeGui.WINDOW), RodUpgradeVisuals.Icon.EMPTY.model(), enabled);
                assertFalse(shown.containsKey(RodUpgradeGui.CONFIRM));
                holder.selected = 4; holder.selectedRod = rod; gui.render(holder, player);
                checkModel(shown.get(RodUpgradeGui.WINDOW), FishingVisuals.rod(2), enabled);
                checkModel(shown.get(RodUpgradeGui.CONFIRM), RodUpgradeVisuals.Icon.PREPARE.model(), enabled);
                assertNull(holder.quote); assertEquals(4, holder.selected);
                holder.quote = mock(RodUpgradePlanner.Plan.class); gui.render(holder, player);
                checkModel(shown.get(RodUpgradeGui.CONFIRM), RodUpgradeVisuals.Icon.CONFIRM.model(), enabled);
                var max = RodToken.issue(new byte[32], UUID.randomUUID(), 10); when(codec.read(rod)).thenReturn(Optional.of(max));
                gui.render(holder, player); checkModel(shown.get(RodUpgradeGui.WINDOW), RodUpgradeVisuals.Icon.MAXIMUM.model(), enabled);
                assertNull(holder.quote); assertFalse(shown.containsKey(RodUpgradeGui.CONFIRM));
                assertSame(rod, storage[4]); verify(rod, never()).setItemMeta(any()); verify(inventory, never()).setStorageContents(any());
                verify(inventory, never()).setItem(anyInt(), any()); verifyNoInteractions(engine);
                for (var item : items.constructed()) verify(item.getItemMeta(), never()).getPersistentDataContainer();
            }
        }
    }
    private static void checkModel(ItemStack item, org.bukkit.NamespacedKey key, boolean enabled) {
        assertNotNull(item); if (enabled) verify(item.getItemMeta()).setItemModel(key);
        else verify(item.getItemMeta(), never()).setItemModel(any());
    }
}
