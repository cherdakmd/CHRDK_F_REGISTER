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
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkfishing.BaitToken;
import ru.cherdakmd.chrdkfishing.RodUpgradeJournal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BaitMenusGuiTest {
    @Test void shopRetainsQuoteAndConfirmationGuardsWithBothVisualSettings() throws Exception {
        for (boolean enabled : new boolean[]{false, true}) {
            var plugin = mock(Plugin.class); var config = new YamlConfiguration(); config.set("textures.bait-menus", enabled);
            when(plugin.getConfig()).thenReturn(config);
            var engine = mock(BaitPurchaseEngine.class); var player = mock(Player.class); UUID owner = UUID.randomUUID();
            when(player.getUniqueId()).thenReturn(owner); when(player.isOnline()).thenReturn(true); when(engine.available(player)).thenReturn(true);
            var token = BaitToken.issue(new byte[32], UUID.randomUUID(), "quick");
            var plan = new BaitPurchasePlanner.Plan(new ItemStack[36], new ItemStack[36], token, 5);
            when(engine.quote(player, "quick")).thenReturn(plan); when(engine.confirm(player, plan)).thenReturn(RodUpgradeJournal.Result.DONE);
            var top = mock(Inventory.class); var view = mock(InventoryView.class); when(view.getTopInventory()).thenReturn(top);
            when(player.getOpenInventory()).thenReturn(view);
            var holder = new AtomicReference<InventoryHolder>(); when(top.getHolder()).thenAnswer(c -> holder.get());
            var scheduler = mock(BukkitScheduler.class); var scheduled = new AtomicReference<Runnable>();
            doAnswer(c -> { scheduled.set(c.getArgument(1)); return null; }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
            var gui = new BaitShopGui(plugin, engine, p -> {}); List<ItemMeta> metadata = new ArrayList<>();
            try (var bukkit = mockStatic(Bukkit.class);
                 var items = mockConstruction(ItemStack.class, (item, context) -> {
                     var meta = mock(ItemMeta.class); metadata.add(meta); when(item.getItemMeta()).thenReturn(meta);
                 })) {
                bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(27), any(Component.class)))
                        .thenAnswer(c -> { holder.set(c.getArgument(0)); return top; });
                bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
                gui.open(player); verify(player).openInventory(top); assertEquals(5, metadata.size());
                verify(top).setItem(eq(10), same(items.constructed().get(0)));
                verify(top).setItem(eq(12), same(items.constructed().get(1)));
                verify(top).setItem(eq(14), same(items.constructed().get(2)));
                verify(top, never()).setItem(eq(22), any());
                verify(metadata.get(0)).lore(argThat(lines -> lines != null && lines.stream().anyMatch(line ->
                        net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(line).contains("Цена: 0.05 алм."))));
                if (enabled) verify(metadata.get(0)).setItemModel(BaitMenuVisuals.bait("quick", false));
                else verify(metadata.get(0), never()).setItemModel(any());
                var stranger = mock(Player.class); when(stranger.getUniqueId()).thenReturn(UUID.randomUUID());
                var wrong = click(view, stranger, 10); gui.click(wrong); verify(wrong).setCancelled(true);
                verify(engine, never()).quote(any(), anyString()); verify(engine, never()).confirm(any(), any());
                var drag = mock(InventoryDragEvent.class); when(drag.getView()).thenReturn(view);
                gui.drag(drag); verify(drag).setCancelled(true);
                var choose = click(view, player, 10); gui.click(choose); verify(choose).setCancelled(true);
                verify(engine).quote(player, "quick"); verify(engine, never()).confirm(any(), any());
                assertEquals(11, metadata.size());
                if (enabled) {
                    verify(metadata.get(5)).setItemModel(BaitMenuVisuals.bait("quick", true));
                    verify(metadata.get(8)).setItemModel(BaitMenuVisuals.Icon.CONFIRM.model());
                } else for (var meta : metadata) verify(meta, never()).setItemModel(any());
                verify(top).setItem(eq(22), any());
                verify(metadata.get(8)).lore(argThat(lines -> lines != null && lines.stream().anyMatch(line ->
                        net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(line).contains("Списать 0.05 алм."))));
                var confirm = click(view, player, 22); gui.click(confirm); verify(confirm).setCancelled(true);
                verify(engine, never()).confirm(any(), any());
                var original = holder.get(); holder.set(mock(InventoryHolder.class)); scheduled.get().run();
                verify(engine, never()).confirm(any(), any());
                holder.set(original); gui.click(choose); gui.click(confirm); scheduled.get().run();
                verify(engine, times(1)).confirm(player, plan); verify(player).closeInventory();
                for (var meta : metadata) verify(meta, never()).getPersistentDataContainer();
            }
        }
    }
    @Test void equipRechecksSelectedTokenBeforeActionWithBothVisualSettings() throws Exception {
        for (boolean enabled : new boolean[]{false, true}) {
            var plugin = mock(Plugin.class); var config = new YamlConfiguration(); config.set("textures.bait-menus", enabled);
            when(plugin.getConfig()).thenReturn(config);
            var engine = mock(BaitPurchaseEngine.class); var codec = mock(BaitItemCodec.class);
            var player = mock(Player.class); UUID owner = UUID.randomUUID();
            when(player.getUniqueId()).thenReturn(owner); when(player.isOnline()).thenReturn(true); when(engine.available(player)).thenReturn(true);
            when(engine.recoverEquip(player)).thenReturn(RodUpgradeJournal.Result.DONE);
            when(engine.equip(player, 3)).thenReturn(RodUpgradeJournal.Result.DONE);
            var token = BaitToken.issue(new byte[32], UUID.randomUUID(), "rare");
            var actual = mock(ItemStack.class); var storage = new ItemStack[36]; storage[3] = actual;
            when(codec.read(actual)).thenReturn(Optional.of(token));
            var inv = mock(PlayerInventory.class); when(player.getInventory()).thenReturn(inv); when(inv.getStorageContents()).thenReturn(storage);
            var top = mock(Inventory.class); var view = mock(InventoryView.class); when(view.getTopInventory()).thenReturn(top);
            when(player.getOpenInventory()).thenReturn(view);
            var holder = new AtomicReference<InventoryHolder>(); when(top.getHolder()).thenAnswer(c -> holder.get());
            var scheduler = mock(BukkitScheduler.class); var scheduled = new AtomicReference<Runnable>();
            doAnswer(c -> { scheduled.set(c.getArgument(1)); return null; }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
            var gui = new BaitEquipGui(plugin, engine, codec, p -> {}); List<ItemMeta> metadata = new ArrayList<>();
            try (var bukkit = mockStatic(Bukkit.class);
                 var items = mockConstruction(ItemStack.class, (item, context) -> {
                     var meta = mock(ItemMeta.class); metadata.add(meta); when(item.getItemMeta()).thenReturn(meta);
                 })) {
                bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(54), any(Component.class)))
                        .thenAnswer(c -> { holder.set(c.getArgument(0)); return top; });
                bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
                gui.open(player); verify(player).openInventory(top); assertEquals(4, metadata.size());
                if (enabled) verify(metadata.get(0)).setItemModel(BaitMenuVisuals.bait("rare", false));
                else verify(metadata.get(0), never()).setItemModel(any());
                verify(top, never()).setItem(eq(49), any());
                var stranger = mock(Player.class); when(stranger.getUniqueId()).thenReturn(UUID.randomUUID());
                var wrong = click(view, stranger, 3); gui.click(wrong); verify(wrong).setCancelled(true);
                verify(engine, never()).equip(any(), anyInt());
                var drag = mock(InventoryDragEvent.class); when(drag.getView()).thenReturn(view); gui.drag(drag); verify(drag).setCancelled(true);
                var select = click(view, player, 3); gui.click(select); verify(select).setCancelled(true);
                verify(engine, never()).equip(any(), anyInt()); assertEquals(9, metadata.size());
                if (enabled) {
                    verify(metadata.get(4)).setItemModel(BaitMenuVisuals.bait("rare", true));
                    verify(metadata.get(6)).setItemModel(BaitMenuVisuals.Icon.CONFIRM.model());
                } else for (var meta : metadata) verify(meta, never()).setItemModel(any());
                var confirm = click(view, player, 49); gui.click(confirm); verify(confirm).setCancelled(true);
                when(codec.read(actual)).thenReturn(Optional.empty()); scheduled.get().run();
                verify(engine, never()).equip(any(), anyInt());
                when(codec.read(actual)).thenReturn(Optional.of(token)); gui.click(select); gui.click(confirm); scheduled.get().run();
                verify(engine, times(1)).equip(player, 3); verify(inv, never()).setItem(anyInt(), any());
                verify(inv, never()).setStorageContents(any());
                for (var meta : metadata) verify(meta, never()).getPersistentDataContainer();
            }
        }
    }
    private static InventoryClickEvent click(InventoryView view, Player player, int slot) {
        var event = mock(InventoryClickEvent.class); when(event.getView()).thenReturn(view);
        when(event.getWhoClicked()).thenReturn(player); when(event.getRawSlot()).thenReturn(slot); return event;
    }
}
