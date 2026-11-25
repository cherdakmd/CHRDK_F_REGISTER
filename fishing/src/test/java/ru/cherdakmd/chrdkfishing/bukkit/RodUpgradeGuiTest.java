package ru.cherdakmd.chrdkfishing.bukkit;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
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
        when(event.getRawSlot()).thenReturn(0); gui.click(event);
        assertEquals(-1, holder.selected); verify(engine, never()).quote(any(), anyInt());
        var rod = mock(ItemStack.class); when(rod.getType()).thenReturn(Material.FISHING_ROD); when(rod.getAmount()).thenReturn(1);
        when(codec.read(rod)).thenReturn(Optional.empty()); storage[0] = rod;
        gui.click(event); assertEquals(0, holder.selected); assertNull(holder.quote);
        when(event.getRawSlot()).thenReturn(49); when(holder.inventory.getItem(49)).thenReturn(mock(ItemStack.class));
        gui.click(event); verify(engine, never()).quote(any(), anyInt());
        verify(player).sendMessage(contains("Нить: 0/8"));
        verify(inventory, never()).setStorageContents(any());
    }
    @Test void selectedRodAndVisibleButtonAreRequired() throws Exception {
        var engine = mock(RodUpgradeEngine.class); var codec = mock(RodItemCodec.class);
        var gui = new RodUpgradeGui(mock(Plugin.class), engine, codec);
        var player = mock(Player.class); var holder = new RodUpgradeGui.Holder(UUID.randomUUID());
        holder.inventory = mock(Inventory.class); var view = mock(InventoryView.class); var event = mock(InventoryClickEvent.class);
        when(event.getView()).thenReturn(view); when(view.getTopInventory()).thenReturn(holder.inventory);
        when(holder.inventory.getHolder()).thenReturn(holder); when(event.getWhoClicked()).thenReturn(player);
        when(player.getUniqueId()).thenReturn(holder.owner); when(engine.available(player)).thenReturn(true);
        when(event.getRawSlot()).thenReturn(49); gui.click(event);
        verify(engine, never()).quote(any(), anyInt()); verify(event).setCancelled(true);
    }
}
