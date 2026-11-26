package ru.cherdakmd.chrdkfishing.bukkit;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FishMainGuiVisualsTest {
    @Test void visualFlagDoesNotChangeNavigationOrInventoryGuards() {
        for (boolean enabled : new boolean[]{false, true}) {
            var plugin = mock(Plugin.class);
            var config = new YamlConfiguration(); config.set("textures.menu", enabled);
            when(plugin.getConfig()).thenReturn(config);
            var player = mock(Player.class); var owner = UUID.randomUUID();
            when(player.isOnline()).thenReturn(true); when(player.getUniqueId()).thenReturn(owner);
            var top = mock(Inventory.class); var view = mock(InventoryView.class);
            when(player.getOpenInventory()).thenReturn(view); when(view.getTopInventory()).thenReturn(top);
            var holder = new AtomicReference<InventoryHolder>();
            when(top.getHolder()).thenAnswer(call -> holder.get());
            var scheduler = mock(BukkitScheduler.class);
            doAnswer(call -> { call.getArgument(1, Runnable.class).run(); return null; })
                    .when(scheduler).runTask(eq(plugin), any(Runnable.class));
            List<String> opened = new ArrayList<>();
            var gui = new FishMainGui(plugin, p -> true, p -> opened.add("album"), p -> opened.add("sale"),
                    p -> opened.add("rod"), p -> opened.add("cooking"));
            gui.setBaitSections(p -> opened.add("shop"), p -> opened.add("bait"));
            gui.setOrders(p -> opened.add("orders"));
            List<ItemMeta> metadata = new ArrayList<>();
            try (var bukkit = mockStatic(Bukkit.class);
                 var items = mockConstruction(ItemStack.class, (item, context) -> {
                     var meta = mock(ItemMeta.class); metadata.add(meta); when(item.getItemMeta()).thenReturn(meta);
                 })) {
                bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(27), any(Component.class)))
                        .thenAnswer(call -> { holder.set(call.getArgument(0)); return top; });
                bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
                gui.open(player);
                verify(player).openInventory(top);
                assertEquals(7, metadata.size());
                var sections = FishMenuVisuals.Section.values();
                for (int i = 0; i < sections.length; i++) {
                    if (enabled) verify(metadata.get(i)).setItemModel(sections[i].model(true));
                    else verify(metadata.get(i), never()).setItemModel(any());
                }
                int[] slots = {10, 12, 14, 16, 18, 20, 22};
                for (int i = 0; i < slots.length; i++) {
                    verify(top).setItem(eq(slots[i]), same(items.constructed().get(i)));
                    var event = mock(InventoryClickEvent.class);
                    when(event.getView()).thenReturn(view); when(event.getWhoClicked()).thenReturn(player);
                    when(event.getRawSlot()).thenReturn(slots[i]);
                    gui.click(event); verify(event).setCancelled(true);
                }
                assertEquals(List.of("album", "sale", "rod", "shop", "bait", "orders", "cooking"), opened);
                var wrong = mock(InventoryClickEvent.class); var stranger = mock(Player.class);
                when(wrong.getView()).thenReturn(view); when(wrong.getWhoClicked()).thenReturn(stranger);
                when(stranger.getUniqueId()).thenReturn(UUID.randomUUID()); when(wrong.getRawSlot()).thenReturn(12);
                gui.click(wrong); verify(wrong).setCancelled(true); assertEquals(7, opened.size());
                var drag = mock(InventoryDragEvent.class); when(drag.getView()).thenReturn(view);
                gui.drag(drag); verify(drag).setCancelled(true);
            }
        }
    }

    @Test void unavailableSectionsReceiveSeparateModelsWithoutEnablingThem() {
        var plugin = mock(Plugin.class); var config = new YamlConfiguration(); config.set("textures.menu", true);
        when(plugin.getConfig()).thenReturn(config);
        var player = mock(Player.class); when(player.isOnline()).thenReturn(true); when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        var top = mock(Inventory.class); List<ItemMeta> metadata = new ArrayList<>();
        var gui = new FishMainGui(plugin, p -> true, p -> {}, p -> {}, p -> {}, p -> {});
        try (var bukkit = mockStatic(Bukkit.class);
             var items = mockConstruction(ItemStack.class, (item, context) -> {
                 var meta = mock(ItemMeta.class); metadata.add(meta); when(item.getItemMeta()).thenReturn(meta);
             })) {
            bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(27), any(Component.class))).thenReturn(top);
            gui.open(player);
            verify(metadata.get(3)).setItemModel(FishMenuVisuals.Section.BAIT_SHOP.model(false));
            verify(metadata.get(4)).setItemModel(FishMenuVisuals.Section.BAIT_EQUIP.model(false));
            verify(metadata.get(5)).setItemModel(FishMenuVisuals.Section.ORDERS.model(false));
        }
    }
}
