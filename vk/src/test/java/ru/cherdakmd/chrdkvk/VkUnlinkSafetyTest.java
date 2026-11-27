package ru.cherdakmd.chrdkvk;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VkUnlinkSafetyTest {
    @Test void destructiveOperationAlwaysRechecksTwoFactor() {
        var plugin = mock(VkPlugin.class); var service = mock(VkService.class); var store = mock(VkStore.class);
        when(service.store()).thenReturn(store); var api = mock(ChrdkRegisterApi.class); var player = mock(Player.class);
        when(player.getName()).thenReturn("Player"); when(api.vkTwoFactorEnabled("Player")).thenReturn(true);
        when(plugin.messages()).thenReturn(Messages.from(getClass().getResourceAsStream("/messages.yml"), null, Logger.getAnonymousLogger()));
        var handler = new VkCommandHandler(plugin, service);
        handler.performUnlinkConfirmed(player, api); handler.performUnlinkConfirmed(player, null);
        verify(store, never()).unlink(anyString()); verify(store, never()).saveNow();
        when(api.vkTwoFactorEnabled("Player")).thenReturn(false); when(store.unlink("Player")).thenReturn(Optional.empty());
        handler.performUnlinkConfirmed(player, api); verify(store).unlink("Player");
        var snapshot = new VkStore.LinkSnapshot("player", "Player", 111L, 0, false, false, 0, false, false);
        when(store.unlink("Player")).thenReturn(Optional.of(snapshot)); when(api.isLoginFrozen("Player")).thenReturn(true);
        handler.performUnlinkConfirmed(player, api);
        verify(store, times(2)).unlink("Player"); verify(store).saveNow(); verify(api).setLoginFrozen("Player", false, VkService.PROVIDER_ID);
    }

    @Test void ownerCancellationSessionAndTwoFactorAreCheckedBeforeActions() {
        var plugin = mock(VkPlugin.class); var config = new YamlConfiguration(); when(plugin.getConfig()).thenReturn(config);
        when(plugin.config()).thenReturn(VkConfig.parse(config));
        when(plugin.messages()).thenReturn(Messages.from(getClass().getResourceAsStream("/messages.yml"), null, Logger.getAnonymousLogger()));
        var service = mock(VkService.class); var store = mock(VkStore.class); when(service.store()).thenReturn(store);
        var handler = mock(VkCommandHandler.class); var api = mock(ChrdkRegisterApi.class); when(plugin.coreApi()).thenReturn(api);
        var owner = mock(Player.class); var id = UUID.randomUUID(); when(owner.getUniqueId()).thenReturn(id); when(owner.getName()).thenReturn("Player");
        when(api.isAuthenticated(owner)).thenReturn(true);
        // Avoid initializing the server-only sound registry; the action has already armed the holder at this point.
        when(owner.getLocation()).thenThrow(new UnsupportedOperationException("no server sound registry"));
        var snapshot = new VkStore.LinkSnapshot("player", "Player", 111L, 0, false, false, 0, false, false);
        when(store.byPlayer("Player")).thenReturn(Optional.of(snapshot));
        var inventory = mock(Inventory.class); var currentHolder = new AtomicReference<InventoryHolder>();
        when(inventory.getHolder()).thenAnswer(c -> currentHolder.get());
        var view = mock(InventoryView.class); when(view.getTopInventory()).thenReturn(inventory); when(owner.getOpenInventory()).thenReturn(view);
        var gui = new VkMenuGui(plugin, service, handler);
        try (var bukkit = mockStatic(Bukkit.class); var items = mockConstruction(ItemStack.class, (item, context) -> {
            when(item.getItemMeta()).thenReturn(mock(ItemMeta.class));
        })) {
            bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(27), any(Component.class))).thenAnswer(c -> {
                currentHolder.set(c.getArgument(0)); return inventory;
            });
            gui.open(owner);
            var foreign = mock(Player.class); when(foreign.getUniqueId()).thenReturn(UUID.randomUUID());
            var foreignClick = click(inventory, foreign, ClickType.SHIFT_LEFT); gui.onClick(foreignClick); verify(foreignClick).setCancelled(true);
            var cancelled = click(inventory, owner, ClickType.SHIFT_LEFT); when(cancelled.isCancelled()).thenReturn(true); gui.onClick(cancelled);
            var oldView = mock(InventoryView.class); var other = mock(Inventory.class); when(oldView.getTopInventory()).thenReturn(other);
            when(owner.getOpenInventory()).thenReturn(oldView); gui.onClick(click(inventory, owner, ClickType.SHIFT_LEFT));
            when(owner.getOpenInventory()).thenReturn(view); gui.onClick(click(inventory, owner, ClickType.LEFT));
            verify(handler, never()).performUnlinkConfirmed(any(), any());
            when(api.vkTwoFactorEnabled("Player")).thenReturn(true); gui.onClick(click(inventory, owner, ClickType.SHIFT_LEFT));
            verify(handler, never()).performUnlinkConfirmed(any(), any());
            when(api.vkTwoFactorEnabled("Player")).thenReturn(false);
            assertThrows(UnsupportedOperationException.class, () -> gui.onClick(click(inventory, owner, ClickType.SHIFT_LEFT)));
            // Enabling 2FA between preparation and confirmation must prevent the destructive call.
            when(api.vkTwoFactorEnabled("Player")).thenReturn(true); gui.onClick(click(inventory, owner, ClickType.LEFT));
            when(api.vkTwoFactorEnabled("Player")).thenReturn(false); gui.onClick(click(inventory, owner, ClickType.LEFT));
            verify(handler, never()).performUnlinkConfirmed(any(), any());
            assertThrows(UnsupportedOperationException.class, () -> gui.onClick(click(inventory, owner, ClickType.SHIFT_LEFT)));
            // A link changed externally is not covered by a confirmation for the old identity.
            when(store.byPlayer("Player")).thenReturn(Optional.of(new VkStore.LinkSnapshot("player", "Player", 222L, 0, false, false, 0, false, false)));
            gui.onClick(click(inventory, owner, ClickType.LEFT)); verify(handler, never()).performUnlinkConfirmed(any(), any());
            assertThrows(UnsupportedOperationException.class, () -> gui.onClick(click(inventory, owner, ClickType.SHIFT_LEFT)));
            var closed = mock(InventoryCloseEvent.class); when(closed.getInventory()).thenReturn(inventory); when(closed.getPlayer()).thenReturn(owner); gui.onClose(closed);
            gui.onClick(click(inventory, owner, ClickType.LEFT)); verify(handler, never()).performUnlinkConfirmed(any(), any());
            assertThrows(UnsupportedOperationException.class, () -> gui.onClick(click(inventory, owner, ClickType.SHIFT_LEFT)));
            gui.open(owner); // replacing the window discards its confirmation even if still within the time limit
            gui.onClick(click(inventory, owner, ClickType.LEFT)); verify(handler, never()).performUnlinkConfirmed(any(), any());
            assertThrows(UnsupportedOperationException.class, () -> gui.onClick(click(inventory, owner, ClickType.SHIFT_LEFT)));
            gui.onClick(click(inventory, owner, ClickType.LEFT)); verify(handler).performUnlinkConfirmed(owner, api);
        }
    }
    private static InventoryClickEvent click(Inventory inventory, Player player, ClickType type) {
        var e = mock(InventoryClickEvent.class); when(e.getInventory()).thenReturn(inventory); when(e.getWhoClicked()).thenReturn(player);
        when(e.getRawSlot()).thenReturn(16); when(e.getClick()).thenReturn(type); when(e.isShiftClick()).thenReturn(type == ClickType.SHIFT_LEFT); return e;
    }
}
