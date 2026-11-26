package ru.cherdakmd.chrdkenchants;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BookVisualListenerTest {
    @Test void scansOnlyPlayerStorageAfterClosingOtherViewsAndRechecksSnapshots() {
        var plugin = mock(EnchantsPlugin.class); var config = new YamlConfiguration(); config.set("textures.books", true);
        when(plugin.getConfig()).thenReturn(config); when(plugin.isEnabled()).thenReturn(true);
        var player = mock(Player.class); var world = mock(World.class); var view = mock(InventoryView.class);
        var inv = mock(PlayerInventory.class); var id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id); when(player.getWorld()).thenReturn(world);
        when(world.getUID()).thenReturn(UUID.randomUUID()); when(player.isOnline()).thenReturn(true);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL); when(player.getOpenInventory()).thenReturn(view);
        when(player.getInventory()).thenReturn(inv);
        var before = mock(ItemStack.class); var changed = mock(ItemStack.class); var copy = mock(ItemStack.class);
        when(before.clone()).thenReturn(copy);
        when(inv.getStorageContents()).thenReturn(new ItemStack[]{before}); when(inv.getItem(0)).thenReturn(before);
        var scheduler = mock(BukkitScheduler.class); var queued = new AtomicReference<Runnable>();
        doAnswer(c -> { queued.set(c.getArgument(1)); return null; }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            var listener = new BookVisualListener(plugin, item -> item == copy);
            when(view.getType()).thenReturn(InventoryType.CHEST);
            listener.queue(player); assertNotNull(queued.get()); queued.get().run();
            verify(inv, never()).setItem(anyInt(), any());
            when(view.getType()).thenReturn(InventoryType.CRAFTING);
            when(inv.getItem(0)).thenReturn(changed); listener.queue(player); queued.get().run();
            verify(inv, never()).setItem(anyInt(), any());
            when(inv.getItem(0)).thenReturn(before); listener.queue(player); queued.get().run();
            verify(inv).setItem(0, copy); verify(inv, never()).setItemInOffHand(any());
            config.set("textures.books", false); listener.queue(player);
            verify(scheduler, times(3)).runTask(eq(plugin), any(Runnable.class));
        }
    }
}
