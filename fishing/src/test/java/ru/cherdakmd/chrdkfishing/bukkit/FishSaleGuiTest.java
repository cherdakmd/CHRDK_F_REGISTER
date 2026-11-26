package ru.cherdakmd.chrdkfishing.bukkit;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkfishing.FishToken;
import ru.cherdakmd.chrdkfishing.FishingModel;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FishSaleGuiTest {
    private final UUID owner = UUID.randomUUID();
    private final FishSaleEngine engine = mock(FishSaleEngine.class);
    private final FishItemCodec codec = mock(FishItemCodec.class);
    private final FishSaleGui gui = new FishSaleGui(mock(Plugin.class), engine, codec);
    private final FishSaleGui.Holder holder = new FishSaleGui.Holder(owner);
    private final InventoryView view = mock(InventoryView.class);
    private final Inventory top = mock(Inventory.class);
    private final Player player = mock(Player.class);

    private InventoryClickEvent click(ClickType type) {
        var event = mock(InventoryClickEvent.class);
        when(event.getView()).thenReturn(view); when(view.getTopInventory()).thenReturn(top);
        when(top.getHolder()).thenReturn(holder); when(event.getWhoClicked()).thenReturn(player);
        when(player.getUniqueId()).thenReturn(owner); when(engine.available(player)).thenReturn(true);
        when(event.getClick()).thenReturn(type);
        return event;
    }
    @Test void keyboardDoubleClickDropAndOffhandAreCancelledWithoutSelecting() {
        for (var type : new ClickType[]{ClickType.NUMBER_KEY, ClickType.SWAP_OFFHAND, ClickType.DOUBLE_CLICK,
                ClickType.DROP, ClickType.CONTROL_DROP, ClickType.CREATIVE, ClickType.MIDDLE}) {
            var event = click(type); when(event.getRawSlot()).thenReturn(49);
            gui.click(event);
            verify(event).setCancelled(true);
            verify(event, never()).getClickedInventory();
        }
        verifyNoInteractions(codec);
    }
    @Test void alreadyCancelledEventAndOtherPlayerCannotSelectAnything() {
        var cancelled = click(ClickType.LEFT); when(cancelled.isCancelled()).thenReturn(true);
        gui.click(cancelled); verify(cancelled).setCancelled(true);
        var wrongOwner = click(ClickType.LEFT); when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        gui.click(wrongOwner); verify(wrongOwner).setCancelled(true);
        verifyNoInteractions(codec);
    }
    @Test void occupiedCursorAndNonFishNeverAlterPlayerStorage() {
        var held = click(ClickType.LEFT); var cursor = mock(ItemStack.class); when(cursor.getType()).thenReturn(Material.COD);
        when(held.getCursor()).thenReturn(cursor); when(held.getRawSlot()).thenReturn(49);
        gui.click(held); verify(held).setCancelled(true); verify(player).sendMessage(contains("курсора"));
        var bottom = mock(PlayerInventory.class); var emptySlot = click(ClickType.SHIFT_LEFT);
        when(view.getBottomInventory()).thenReturn(bottom); when(emptySlot.getClickedInventory()).thenReturn(bottom);
        when(emptySlot.getSlot()).thenReturn(0); when(emptySlot.getRawSlot()).thenReturn(81);
        when(player.getInventory()).thenReturn(bottom); var storage = new ItemStack[36]; storage[0] = mock(ItemStack.class);
        when(bottom.getStorageContents()).thenReturn(storage); when(codec.read(storage[0])).thenReturn(Optional.empty());
        gui.click(emptySlot); verify(emptySlot).setCancelled(true);
        verify(bottom, never()).setStorageContents(any()); verify(bottom, never()).setItem(anyInt(), any());
    }
    @Test void bottomClickAddsAndUpperClickRemovesWithoutMovingActualFish() {
        var screen = spy(gui); doNothing().when(screen).render(holder);
        var bottom = mock(PlayerInventory.class); var source = mock(ItemStack.class);
        var storage = new ItemStack[36]; storage[9] = source;
        var token = FishToken.issue(new byte[32], UUID.randomUUID(),
                new FishingModel.Catch("pike", FishingModel.Size.NORMAL, 40), 70);
        when(player.getInventory()).thenReturn(bottom); when(bottom.getStorageContents()).thenReturn(storage);
        when(codec.read(source)).thenReturn(Optional.of(token)); when(view.getBottomInventory()).thenReturn(bottom);
        var add = click(ClickType.SHIFT_LEFT);
        when(add.getClickedInventory()).thenReturn(bottom); when(add.getSlot()).thenReturn(9); when(add.getRawSlot()).thenReturn(54);
        screen.click(add);
        verify(add).setCancelled(true); assertEquals(java.util.List.of(9), holder.basket.slots());
        assertEquals(70, holder.basket.total()); assertSame(source, storage[9]);
        var remove = click(ClickType.LEFT);
        when(remove.getClickedInventory()).thenReturn(top); when(remove.getRawSlot()).thenReturn(0);
        screen.click(remove);
        verify(remove).setCancelled(true); assertTrue(holder.basket.isEmpty()); assertSame(source, storage[9]);
        verify(bottom, never()).setStorageContents(any()); verify(bottom, never()).setItem(anyInt(), any());
    }
    @Test void allSelectionPreviewsMixedFishWithoutMovingAndRejectsDuplicates() {
        var storage = new ItemStack[36]; var cod = mock(ItemStack.class); var custom = mock(ItemStack.class);
        when(cod.getType()).thenReturn(Material.COD); when(cod.getAmount()).thenReturn(20); when(cod.getMaxStackSize()).thenReturn(64);
        var token = FishToken.issue(new byte[32], UUID.randomUUID(), new FishingModel.Catch("pike", FishingModel.Size.NORMAL, 40), 70);
        when(codec.read(custom)).thenReturn(Optional.of(token)); storage[1] = cod; storage[7] = custom;
        var basket = new FishSaleBasket();
        assertTrue(FishSaleGui.selectAll(basket, storage, codec));
        assertEquals(java.util.List.of(1, 7), basket.slots()); assertEquals(270, basket.total());
        assertSame(cod, storage[1]); assertSame(custom, storage[7]);
        verify(cod, never()).setAmount(anyInt());
        var copy = mock(ItemStack.class); when(codec.read(copy)).thenReturn(Optional.of(token)); storage[10] = copy;
        basket.clear(); assertFalse(FishSaleGui.selectAll(basket, storage, codec)); assertTrue(basket.isEmpty());
    }
    @Test void allDragsAreCancelledIncludingBottomAndTop() {
        var event = mock(InventoryDragEvent.class);
        when(event.getView()).thenReturn(view); when(view.getTopInventory()).thenReturn(top); when(top.getHolder()).thenReturn(holder);
        gui.drag(event); verify(event).setCancelled(true);
    }
}
