package ru.cherdakmd.chrdkvk;

import java.util.HashMap;
import java.util.Map;
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
import org.bukkit.event.inventory.InventoryDragEvent;
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

class VkMenuGuiVisualsTest {
    @Test void linkTogglesAndUnlinkConfirmationBehaveTheSameWithAndWithoutModels() throws Exception {
        for (boolean enabled : new boolean[]{false, true}) {
            var plugin = mock(VkPlugin.class); var cfg = new YamlConfiguration(); cfg.set("textures.menu", enabled);
            when(plugin.getConfig()).thenReturn(cfg); var parsed = VkConfig.parse(cfg); when(plugin.config()).thenReturn(parsed);
            var messages = Messages.from(VkMenuGuiVisualsTest.class.getResourceAsStream("/messages.yml"), null, Logger.getAnonymousLogger());
            when(plugin.messages()).thenReturn(messages);
            var api = mock(ChrdkRegisterApi.class); when(plugin.coreApi()).thenReturn(api);
            var service = mock(VkService.class); var store = mock(VkStore.class); when(service.store()).thenReturn(store);
            var commands = mock(VkCommandHandler.class); var player = mock(Player.class);
            when(player.getName()).thenReturn("Player");
            // Standalone Paper API has no sound registry; stop at the sound call and render explicitly below.
            when(player.getLocation()).thenThrow(new UnsupportedOperationException("No server sound registry"));
            var id = UUID.randomUUID(); when(player.getUniqueId()).thenReturn(id);
            when(api.isAuthenticated(player)).thenReturn(true); when(store.byPlayer("Player")).thenReturn(Optional.empty());
            var slots = new HashMap<Integer, ItemStack>(); var holder = new AtomicReference<InventoryHolder>();
            var inventory = mock(Inventory.class); when(inventory.getHolder()).thenAnswer(c -> holder.get());
            var view = mock(InventoryView.class); when(view.getTopInventory()).thenReturn(inventory); when(player.getOpenInventory()).thenReturn(view);
            doAnswer(c -> { slots.put(c.getArgument(0), c.getArgument(1)); return null; }).when(inventory).setItem(anyInt(), any(ItemStack.class));
            var gui = new VkMenuGui(plugin, service, commands);
            try (var bukkit = mockStatic(Bukkit.class); var items = mockConstruction(ItemStack.class, (item, context) -> {
                var meta = mock(ItemMeta.class); when(item.getItemMeta()).thenReturn(meta);
            })) {
                bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(27), any(Component.class))).thenAnswer(c -> {
                    holder.set(c.getArgument(0)); return inventory;
                });
                gui.open(player);
                check(slots.get(10), VkMenuVisuals.Icon.LINK_UNLINKED, enabled);
                check(slots.get(12), VkMenuVisuals.Icon.NOTIFY_UNLINKED, enabled);
                check(slots.get(13), VkMenuVisuals.Icon.CHAT_HIDDEN, enabled);
                check(slots.get(14), VkMenuVisuals.Icon.BADGE, enabled);
                check(slots.get(16), VkMenuVisuals.Icon.SECURITY_ACTIVE, enabled);
                check(slots.get(22), VkMenuVisuals.Icon.CLOSE, enabled);
                gui.onClick(click(inventory, player, 10, ClickType.LEFT)); verify(commands).issueLinkCode(player, parsed);
                var drag = mock(InventoryDragEvent.class); when(drag.getInventory()).thenReturn(inventory); gui.onDrag(drag); verify(drag).setCancelled(true);
                var blocked = click(inventory, player, 12, ClickType.DOUBLE_CLICK); gui.onClick(blocked); verify(blocked).setCancelled(true);
                gui.onClick(click(inventory, player, 27, ClickType.LEFT)); verify(commands, never()).toggleNotify(player);
                when(api.isAuthenticated(player)).thenReturn(false); gui.onClick(click(inventory, player, 12, ClickType.LEFT));
                verify(commands, never()).toggleNotify(player); when(api.isAuthenticated(player)).thenReturn(true);
                var link = new VkStore.LinkSnapshot("player", "Player", 12345L, 0L, true, false, 0L, true, true);
                when(store.byPlayer("Player")).thenReturn(Optional.of(link)); when(store.receivesGroupChat("Player")).thenReturn(true);
                when(api.isLoginFrozen("Player")).thenReturn(true); gui.open(player);
                check(slots.get(10), VkMenuVisuals.Icon.LINK_LINKED, enabled);
                check(slots.get(12), VkMenuVisuals.Icon.NOTIFY_ON, enabled);
                check(slots.get(13), VkMenuVisuals.Icon.CHAT_VISIBLE, enabled);
                check(slots.get(16), VkMenuVisuals.Icon.SECURITY_FROZEN, enabled);
                verify(slots.get(10).getItemMeta()).displayName(messages.get("vk.gui-link-linked-name", "vk_id", "12345"));
                gui.onClick(click(inventory, player, 10, ClickType.LEFT)); verify(commands).showStatus(player, api, parsed);
                assertThrows(UnsupportedOperationException.class, () -> gui.onClick(click(inventory, player, 12, ClickType.LEFT))); verify(commands).toggleNotify(player);
                assertThrows(UnsupportedOperationException.class, () -> gui.onClick(click(inventory, player, 13, ClickType.LEFT))); verify(commands).toggleGroupChatVisibility(player);
                assertThrows(UnsupportedOperationException.class, () -> gui.onClick(click(inventory, player, 13, ClickType.RIGHT))); verify(commands).toggleMentions(player);
                gui.onClick(click(inventory, player, 16, ClickType.LEFT)); verify(commands, never()).performUnlinkConfirmed(any(), any());
                var arm = click(inventory, player, 16, ClickType.SHIFT_LEFT);
                assertThrows(UnsupportedOperationException.class, () -> gui.onClick(arm));
                var render = VkMenuGui.class.getDeclaredMethod("render", Player.class, Inventory.class); render.setAccessible(true); render.invoke(gui, player, inventory);
                check(slots.get(16), VkMenuVisuals.Icon.UNLINK_CONFIRM, enabled);
                verify(slots.get(16).getItemMeta()).displayName(messages.get("vk.gui-unlink-confirm-name"));
                verify(commands, never()).performUnlinkConfirmed(any(), any());
                gui.onClick(click(inventory, player, 16, ClickType.LEFT)); verify(commands).performUnlinkConfirmed(player, api);
                // An expired armed prompt must not execute an unlink.
                assertThrows(UnsupportedOperationException.class, () -> gui.onClick(click(inventory, player, 16, ClickType.SHIFT_LEFT)));
                var field = holder.get().getClass().getDeclaredField("armedUntil"); field.setAccessible(true); field.setLong(holder.get(), 1L);
                gui.onClick(click(inventory, player, 16, ClickType.LEFT)); verify(commands, times(1)).performUnlinkConfirmed(any(), any());
                when(store.byPlayer("Player")).thenReturn(Optional.of(new VkStore.LinkSnapshot("player", "Player", 12345L, 0L, true, false, 0L, false, false)));
                gui.open(player); check(slots.get(12), VkMenuVisuals.Icon.NOTIFY_OFF, enabled);
                gui.onClick(click(inventory, player, 14, ClickType.LEFT)); verify(player).performCommand("labels");
                verify(player, never()).getInventory();
                for (var item : items.constructed()) verify(item.getItemMeta(), never()).getPersistentDataContainer();
            }
        }
    }
    private static void check(ItemStack item, VkMenuVisuals.Icon icon, boolean enabled) {
        assertNotNull(item); if (enabled) verify(item.getItemMeta()).setItemModel(icon.model());
        else verify(item.getItemMeta(), never()).setItemModel(any());
    }
    private static InventoryClickEvent click(Inventory inventory, Player player, int slot, ClickType type) {
        var e = mock(InventoryClickEvent.class); when(e.getInventory()).thenReturn(inventory); when(e.getWhoClicked()).thenReturn(player);
        when(e.getRawSlot()).thenReturn(slot); when(e.getClick()).thenReturn(type);
        when(e.isShiftClick()).thenReturn(type == ClickType.SHIFT_LEFT || type == ClickType.SHIFT_RIGHT);
        when(e.isRightClick()).thenReturn(type == ClickType.RIGHT || type == ClickType.SHIFT_RIGHT); return e;
    }
}
