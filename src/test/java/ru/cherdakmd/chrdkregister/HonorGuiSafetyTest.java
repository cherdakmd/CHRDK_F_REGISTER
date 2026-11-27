package ru.cherdakmd.chrdkregister;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HonorGuiSafetyTest {
    @Test void actionsUseDisplayedRecordsAndRevalidateChatInputs() {
        var plugin=mock(RegisterPlugin.class);var messages=Messages.from(getClass().getResourceAsStream("/messages.yml"),null,Logger.getAnonymousLogger());when(plugin.messages()).thenReturn(messages);
        var input=mock(GuiChatInput.class);when(plugin.chatInput()).thenReturn(input);var ledger=HonorLedger.withDefaults();var service=mock(HonorService.class);when(service.ledger()).thenReturn(ledger);when(service.canViewGui(any())).thenReturn(true);when(service.canEdit(any())).thenReturn(true);
        var player=mock(Player.class);when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(player.isOnline()).thenReturn(true);when(player.getName()).thenReturn("Admin");var inv=mock(Inventory.class);var view=mock(InventoryView.class);when(view.getTopInventory()).thenReturn(inv);var holder=new AtomicReference<InventoryHolder>();when(inv.getHolder()).thenAnswer(c->holder.get());
        var event=mock(InventoryClickEvent.class);when(event.getView()).thenReturn(view);when(event.getWhoClicked()).thenReturn(player);when(event.getClick()).thenReturn(ClickType.LEFT);
        var first=new HonorGrant("Alice","","old-citrus","Admin",2,"original");var second=new HonorGrant("Bob","","old-citrus","Admin",1,"second");ledger.grant(first);ledger.grant(second);
        try(var bukkit=mockStatic(Bukkit.class);var items=mockConstruction(ItemStack.class,(item,ctx)->when(item.getItemMeta()).thenReturn(mock(ItemMeta.class)))) {
            bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),eq(54),any(Component.class))).thenAnswer(c->{holder.set(c.getArgument(0));return inv;});var gui=new HonorGui(plugin,service);
            gui.open(player);when(event.getRawSlot()).thenReturn(1);gui.onClick(event); // grants
            ledger.grant(first.withReason("changed"));when(event.getRawSlot()).thenReturn(10);gui.onClick(event);verify(service,never()).revoke(any(),anyString(),anyString());
            // Updated display: a valid click still targets the displayed grant, not a newly inserted row.
            ledger.grant(new HonorGrant("Aaron","","old-citrus","Admin",3,"new"));gui.onClick(event);verify(service).revoke(player,"Alice","old-citrus");
            // Reopen and test shift-click followed by a concurrent change during chat input.
            gui.open(player);when(event.getRawSlot()).thenReturn(1);gui.onClick(event);when(event.getRawSlot()).thenReturn(11);when(event.getClick()).thenReturn(ClickType.SHIFT_LEFT);gui.onClick(event);
            @SuppressWarnings("unchecked") var callback=(org.mockito.ArgumentCaptor<Consumer<String>>)(Object)org.mockito.ArgumentCaptor.forClass(Consumer.class);verify(input).begin(eq(player),anyString(),callback.capture());ledger.grant(first.withReason("changed again"));callback.getValue().accept("new reason");verify(service,never()).editGrantReason(any(),anyString(),anyString());
            // Unsupported and previously cancelled clicks do not start actions.
            when(event.getClick()).thenReturn(ClickType.NUMBER_KEY);gui.onClick(event);when(event.getClick()).thenReturn(ClickType.LEFT);when(event.isCancelled()).thenReturn(true);gui.onClick(event);verifyNoMoreInteractions(input);
        }
    }
}
