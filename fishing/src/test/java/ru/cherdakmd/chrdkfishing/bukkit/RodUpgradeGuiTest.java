package ru.cherdakmd.chrdkfishing.bukkit;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RodUpgradeGuiTest {
    @Test void emptySlotsDoNothingAndMissingMaterialsNeverPrepare() throws Exception {
        var engine = mock(RodUpgradeEngine.class); var codec = mock(RodItemCodec.class);
        var gui = spy(new RodUpgradeGui(mock(Plugin.class), engine, codec));
        var player = mock(Player.class); var inventory = mock(PlayerInventory.class);
        var holder = new RodUpgradeGui.Holder(UUID.randomUUID()); holder.inventory = mock(Inventory.class);
        doNothing().when(gui).render(holder, player);
        var view = mock(InventoryView.class); var event = mock(InventoryClickEvent.class);
        when(event.getView()).thenReturn(view); when(view.getTopInventory()).thenReturn(holder.inventory);
        when(holder.inventory.getHolder()).thenReturn(holder); when(event.getWhoClicked()).thenReturn(player);
        when(player.getUniqueId()).thenReturn(holder.owner); when(engine.available(player)).thenReturn(true);
        when(player.getInventory()).thenReturn(inventory); var storage = new ItemStack[36];
        when(inventory.getStorageContents()).thenReturn(storage);
        when(view.getBottomInventory()).thenReturn(inventory); when(event.getClickedInventory()).thenReturn(inventory);
        when(event.getSlot()).thenReturn(0); when(event.getClick()).thenReturn(ClickType.LEFT);
        when(event.getRawSlot()).thenReturn(54); gui.click(event);
        assertEquals(-1, holder.selected); verify(engine, never()).quote(any(), anyInt());
        var rod = mock(ItemStack.class); when(rod.getType()).thenReturn(Material.FISHING_ROD); when(rod.getAmount()).thenReturn(1);
        when(codec.read(rod)).thenReturn(Optional.empty()); when(rod.clone()).thenReturn(rod); storage[0] = rod;
        gui.click(event); assertEquals(0, holder.selected); assertNull(holder.quote);
        when(event.getClickedInventory()).thenReturn(holder.inventory);
        when(event.getRawSlot()).thenReturn(RodUpgradeGui.CONFIRM); when(holder.inventory.getItem(RodUpgradeGui.CONFIRM)).thenReturn(mock(ItemStack.class));
        gui.click(event); verify(engine, never()).quote(any(), anyInt());
        verify(player).sendMessage(contains("Нить: 0/8"));
        verify(inventory, never()).setStorageContents(any());
    }
    @Test void selectFromBottomAndClearWindowWithoutMovingRod() {
        var engine = mock(RodUpgradeEngine.class); var codec = mock(RodItemCodec.class);
        var gui = spy(new RodUpgradeGui(mock(Plugin.class), engine, codec));
        var player = mock(Player.class); var bottom = mock(PlayerInventory.class);
        var holder = new RodUpgradeGui.Holder(UUID.randomUUID()); holder.inventory = mock(Inventory.class);
        doNothing().when(gui).render(holder, player);
        var view = mock(InventoryView.class); var event = mock(InventoryClickEvent.class);
        when(event.getView()).thenReturn(view); when(view.getTopInventory()).thenReturn(holder.inventory);
        when(view.getBottomInventory()).thenReturn(bottom); when(holder.inventory.getHolder()).thenReturn(holder);
        when(event.getWhoClicked()).thenReturn(player); when(player.getUniqueId()).thenReturn(holder.owner);
        when(engine.available(player)).thenReturn(true); when(player.getInventory()).thenReturn(bottom);
        var rod = mock(ItemStack.class); when(rod.getType()).thenReturn(Material.FISHING_ROD);
        when(rod.getAmount()).thenReturn(1); when(rod.clone()).thenReturn(rod);
        var storage = new ItemStack[36]; storage[9] = rod; when(bottom.getStorageContents()).thenReturn(storage);
        when(event.getClickedInventory()).thenReturn(bottom); when(event.getSlot()).thenReturn(9);
        when(event.getRawSlot()).thenReturn(54); when(event.getClick()).thenReturn(ClickType.SHIFT_LEFT);
        gui.click(event); assertEquals(9, holder.selected); assertSame(rod, holder.selectedRod);
        assertSame(rod, storage[9]); verify(event).setCancelled(true);
        verify(bottom, never()).setItem(anyInt(), any()); verify(bottom, never()).setStorageContents(any());
        var clear = mock(InventoryClickEvent.class); when(clear.getView()).thenReturn(view);
        when(clear.getWhoClicked()).thenReturn(player); when(clear.getClick()).thenReturn(ClickType.LEFT);
        when(clear.getClickedInventory()).thenReturn(holder.inventory); when(clear.getRawSlot()).thenReturn(RodUpgradeGui.WINDOW);
        gui.click(clear); assertEquals(-1, holder.selected); assertNull(holder.selectedRod); assertSame(rod, storage[9]);
    }
    @Test void unsafeClicksCursorAndDragNeverMoveAnything() {
        var engine = mock(RodUpgradeEngine.class); var codec = mock(RodItemCodec.class);
        var gui = new RodUpgradeGui(mock(Plugin.class), engine, codec);
        var holder = new RodUpgradeGui.Holder(UUID.randomUUID()); holder.inventory = mock(Inventory.class);
        var view = mock(InventoryView.class); var player = mock(Player.class);
        when(view.getTopInventory()).thenReturn(holder.inventory); when(holder.inventory.getHolder()).thenReturn(holder);
        when(player.getUniqueId()).thenReturn(holder.owner); when(engine.available(player)).thenReturn(true);
        for (var type : new ClickType[]{ClickType.NUMBER_KEY, ClickType.DOUBLE_CLICK, ClickType.SWAP_OFFHAND, ClickType.DROP}) {
            var event = mock(InventoryClickEvent.class); when(event.getView()).thenReturn(view);
            when(event.getWhoClicked()).thenReturn(player); when(event.getClick()).thenReturn(type);
            gui.click(event); verify(event).setCancelled(true); assertEquals(-1, holder.selected);
        }
        var held = mock(InventoryClickEvent.class); var cursor = mock(ItemStack.class);
        when(held.getView()).thenReturn(view); when(held.getWhoClicked()).thenReturn(player);
        when(held.getClick()).thenReturn(ClickType.LEFT); when(held.getCursor()).thenReturn(cursor);
        when(cursor.getType()).thenReturn(Material.FISHING_ROD);
        gui.click(held); verify(held).setCancelled(true); verify(player).sendMessage(contains("курсора"));
        var drag = mock(InventoryDragEvent.class); when(drag.getView()).thenReturn(view);
        gui.drag(drag); verify(drag).setCancelled(true);
        verifyNoInteractions(codec);
    }
    @Test void changedSelectedRodCannotBeQuoted() throws Exception {
        var engine = mock(RodUpgradeEngine.class); var codec = mock(RodItemCodec.class);
        var gui = spy(new RodUpgradeGui(mock(Plugin.class), engine, codec));
        var player = mock(Player.class); var inv = mock(PlayerInventory.class);
        var holder = new RodUpgradeGui.Holder(UUID.randomUUID()); holder.inventory = mock(Inventory.class);
        doNothing().when(gui).render(holder, player);
        var view = mock(InventoryView.class); var event = mock(InventoryClickEvent.class);
        when(event.getView()).thenReturn(view); when(view.getTopInventory()).thenReturn(holder.inventory);
        when(event.getClickedInventory()).thenReturn(holder.inventory); when(holder.inventory.getHolder()).thenReturn(holder);
        when(event.getWhoClicked()).thenReturn(player); when(player.getUniqueId()).thenReturn(holder.owner);
        when(engine.available(player)).thenReturn(true); when(player.getInventory()).thenReturn(inv);
        var storage = new ItemStack[36]; storage[0] = mock(ItemStack.class);
        when(inv.getStorageContents()).thenReturn(storage); holder.selected = 0; holder.selectedRod = mock(ItemStack.class);
        when(event.getClick()).thenReturn(ClickType.LEFT); when(event.getRawSlot()).thenReturn(RodUpgradeGui.CONFIRM);
        when(holder.inventory.getItem(RodUpgradeGui.CONFIRM)).thenReturn(mock(ItemStack.class));
        gui.click(event); verify(engine, never()).quote(any(), anyInt());
        verify(player).sendMessage(contains("Удочка изменилась"));
        verify(inv, never()).setStorageContents(any());
    }
    @Test void insufficientFundsIsReportedWithoutPreparingTransaction() throws Exception {
        var engine = mock(RodUpgradeEngine.class); var codec = mock(RodItemCodec.class);
        var gui = spy(new RodUpgradeGui(mock(Plugin.class), engine, codec));
        var player = mock(Player.class); var inv = mock(PlayerInventory.class);
        var holder = new RodUpgradeGui.Holder(UUID.randomUUID()); holder.inventory = mock(Inventory.class);
        doNothing().when(gui).render(holder, player);
        var view = mock(InventoryView.class); var event = mock(InventoryClickEvent.class);
        when(event.getView()).thenReturn(view); when(view.getTopInventory()).thenReturn(holder.inventory);
        when(event.getClickedInventory()).thenReturn(holder.inventory); when(holder.inventory.getHolder()).thenReturn(holder);
        when(event.getWhoClicked()).thenReturn(player); when(player.getUniqueId()).thenReturn(holder.owner);
        when(engine.available(player)).thenReturn(true); when(player.getInventory()).thenReturn(inv);
        var rod = mock(ItemStack.class); when(rod.getType()).thenReturn(Material.FISHING_ROD); when(rod.getAmount()).thenReturn(1);
        when(codec.read(rod)).thenReturn(Optional.empty()); holder.selected = 0; holder.selectedRod = rod;
        var string = mock(ItemStack.class); when(string.getType()).thenReturn(Material.STRING);
        when(string.getAmount()).thenReturn(8); when(string.getMaxStackSize()).thenReturn(64);
        var iron = mock(ItemStack.class); when(iron.getType()).thenReturn(Material.IRON_INGOT);
        when(iron.getAmount()).thenReturn(2); when(iron.getMaxStackSize()).thenReturn(64);
        var storage = new ItemStack[36]; storage[0] = rod; storage[1] = string; storage[2] = iron;
        when(inv.getStorageContents()).thenReturn(storage);
        when(event.getClick()).thenReturn(ClickType.LEFT); when(event.getRawSlot()).thenReturn(RodUpgradeGui.CONFIRM);
        when(holder.inventory.getItem(RodUpgradeGui.CONFIRM)).thenReturn(mock(ItemStack.class));
        when(engine.quote(player, 0)).thenThrow(new RodUpgradeEngine.InsufficientFunds());
        gui.click(event); assertNull(holder.quote); verify(player).sendMessage(contains("Недостаточно доступных средств"));
        verify(engine, never()).confirm(any(), any()); verify(inv, never()).setStorageContents(any());
    }
    @Test void preflightDistinguishesInsufficientBalanceFromUnavailableBank() throws Exception {
        assertDoesNotThrow(() -> RodUpgradeEngine.requireBalance(100, 100));
        assertThrows(RodUpgradeEngine.InsufficientFunds.class, () -> RodUpgradeEngine.requireBalance(99, 100));
        assertThrows(RodUpgradeEngine.BalanceUnavailable.class, () -> RodUpgradeEngine.requireBalance(-1, 100));
    }

    @Test void refreshesOnlySignedCurrentRodsWithoutChangingOtherSlots() throws Exception {
        var engine = mock(RodUpgradeEngine.class); var codec = mock(RodItemCodec.class);
        var gui = new RodUpgradeGui(mock(Plugin.class), engine, codec);
        var player = mock(Player.class); var inventory = mock(PlayerInventory.class);
        var good = mock(ItemStack.class); var stale = mock(ItemStack.class); var updated = mock(ItemStack.class);
        var token = ru.cherdakmd.chrdkfishing.RodToken.issue(new byte[32], UUID.randomUUID(), 2);
        var oldToken = ru.cherdakmd.chrdkfishing.RodToken.issue(new byte[32], UUID.randomUUID(), 3);
        ItemStack[] storage = new ItemStack[36]; storage[0] = good; storage[1] = stale;
        when(player.getInventory()).thenReturn(inventory); when(inventory.getStorageContents()).thenReturn(storage);
        when(codec.read(good)).thenReturn(Optional.of(token)); when(codec.read(stale)).thenReturn(Optional.of(oldToken));
        when(codec.read(null)).thenReturn(Optional.empty());
        when(engine.rodLevel(good)).thenReturn(2); when(engine.rodLevel(stale)).thenReturn(1);
        when(codec.stamp(good, token)).thenReturn(updated);
        gui.refreshLegacyLore(player);
        verify(inventory).setItem(0, updated); verify(inventory, never()).setItem(eq(1), any());
        verify(codec, never()).stamp(stale, oldToken);
    }
    @Test void selectedRodAndVisibleButtonAreRequired() throws Exception {
        var engine = mock(RodUpgradeEngine.class); var codec = mock(RodItemCodec.class);
        var gui = new RodUpgradeGui(mock(Plugin.class), engine, codec);
        var player = mock(Player.class); var holder = new RodUpgradeGui.Holder(UUID.randomUUID());
        holder.inventory = mock(Inventory.class); var view = mock(InventoryView.class); var event = mock(InventoryClickEvent.class);
        when(event.getView()).thenReturn(view); when(view.getTopInventory()).thenReturn(holder.inventory);
        when(holder.inventory.getHolder()).thenReturn(holder); when(event.getWhoClicked()).thenReturn(player);
        when(player.getUniqueId()).thenReturn(holder.owner); when(engine.available(player)).thenReturn(true);
        when(event.getClick()).thenReturn(ClickType.LEFT); when(event.getRawSlot()).thenReturn(RodUpgradeGui.CONFIRM); gui.click(event);
        verify(engine, never()).quote(any(), anyInt()); verify(event).setCancelled(true);
    }
}
