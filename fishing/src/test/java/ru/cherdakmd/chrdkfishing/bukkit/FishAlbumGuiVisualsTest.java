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
import ru.cherdakmd.chrdkfishing.AlbumRewardJournal;
import ru.cherdakmd.chrdkfishing.FishAlbum;
import ru.cherdakmd.chrdkfishing.FishCatalog;
import ru.cherdakmd.chrdkfishing.FishingModel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FishAlbumGuiVisualsTest {
    @Test void flagsKeepPageNavigationOwnerGuardsAndVirtualEntries() throws Exception {
        for (boolean enabled : new boolean[]{false, true}) {
            var plugin = mock(Plugin.class); var config = new YamlConfiguration(); config.set("textures.album-menu", enabled);
            when(plugin.getConfig()).thenReturn(config);
            var album = mock(FishAlbum.class); var rewards = mock(AlbumRewards.class);
            var first = FishCatalog.all().get(0);
            var player = mock(Player.class); var owner = UUID.randomUUID();
            when(player.getUniqueId()).thenReturn(owner); when(player.isOnline()).thenReturn(true);
            when(album.records(owner)).thenReturn(Map.of(first.id(), new FishAlbum.Record(1, 10, FishingModel.Size.NORMAL)));
            when(rewards.progress(player)).thenReturn(new AlbumRewardJournal.Progress(0, 0, 0, 0));
            var top = mock(Inventory.class); when(top.getSize()).thenReturn(54);
            var view = mock(InventoryView.class); when(view.getTopInventory()).thenReturn(top);
            when(player.getOpenInventory()).thenReturn(view);
            var holder = new AtomicReference<InventoryHolder>(); when(top.getHolder()).thenAnswer(c -> holder.get());
            var scheduler = mock(BukkitScheduler.class);
            doAnswer(c -> { c.getArgument(1, Runnable.class).run(); return null; }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
            var gui = new FishAlbumGui(album); var backs = new ArrayList<Player>();
            gui.setBack(plugin, backs::add); gui.setRewards(rewards);
            List<ItemMeta> metadata = new ArrayList<>();
            try (var bukkit = mockStatic(Bukkit.class);
                 var items = mockConstruction(ItemStack.class, (item, context) -> {
                     var meta = mock(ItemMeta.class); metadata.add(meta); when(item.getItemMeta()).thenReturn(meta);
                 })) {
                bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(54), any(Component.class)))
                        .thenAnswer(c -> { holder.set(c.getArgument(0)); return top; });
                bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
                gui.open(player);
                verify(player).openInventory(top); assertEquals(49, metadata.size());
                for (int i = 0; i < 45; i++) verify(top).setItem(eq(i), same(items.constructed().get(i)));
                verify(top).setItem(eq(48), same(items.constructed().get(45)));
                verify(top).setItem(eq(50), same(items.constructed().get(46)));
                verify(top).setItem(eq(49), same(items.constructed().get(47)));
                verify(top).setItem(eq(52), same(items.constructed().get(48)));
                if (enabled) {
                    verify(metadata.get(0)).setItemModel(FishingVisuals.fish(first.id()));
                    for (int i = 1; i < 45; i++) verify(metadata.get(i)).setItemModel(FishAlbumVisuals.Icon.UNKNOWN.model());
                    verify(metadata.get(45)).setItemModel(FishAlbumVisuals.Icon.REWARD_WAITING.model());
                    verify(metadata.get(46)).setItemModel(FishAlbumVisuals.Icon.NEXT.model());
                    verify(metadata.get(47)).setItemModel(FishAlbumVisuals.Icon.PAGE.model());
                    verify(metadata.get(48)).setItemModel(FishAlbumVisuals.Icon.BACK.model());
                } else for (var meta : metadata) verify(meta, never()).setItemModel(any());
                for (var meta : metadata) verify(meta, never()).getPersistentDataContainer();
                var stranger = mock(Player.class); when(stranger.getUniqueId()).thenReturn(UUID.randomUUID());
                var wrong = click(view, stranger, 48); gui.click(wrong); verify(wrong).setCancelled(true);
                verify(rewards, never()).claim(any());
                var bottom = click(view, player, 70); gui.click(bottom); verify(bottom).setCancelled(true);
                verify(rewards, never()).claim(any());
                var dragTop = mock(InventoryDragEvent.class); when(dragTop.getView()).thenReturn(view); when(dragTop.getRawSlots()).thenReturn(Set.of(1));
                gui.drag(dragTop); verify(dragTop).setCancelled(true);
                var dragBottom = mock(InventoryDragEvent.class); when(dragBottom.getView()).thenReturn(view); when(dragBottom.getRawSlots()).thenReturn(Set.of(56));
                gui.drag(dragBottom); verify(dragBottom, never()).setCancelled(anyBoolean());
                var next = click(view, player, 50); gui.click(next); verify(next).setCancelled(true);
                verify(top).setItem(eq(45), any());
                gui.click(next); int afterLast = metadata.size();
                gui.click(next); assertEquals(afterLast, metadata.size(), "cannot leave last page");
                var previous = click(view, player, 45); gui.click(previous); verify(previous).setCancelled(true);
                var back = click(view, player, 52); gui.click(back); assertEquals(List.of(player), backs);
                verify(rewards, never()).claim(any());
                verify(album, never()).record(any(), any(), anyLong());
            }
        }
    }

    @Test void readyRewardKeepsTheExistingClaimPath() throws Exception {
        for (boolean enabled : new boolean[]{false, true}) {
        var plugin = mock(Plugin.class); var config = new YamlConfiguration(); config.set("textures.album-menu", enabled);
        when(plugin.getConfig()).thenReturn(config);
        var album = mock(FishAlbum.class); var rewards = mock(AlbumRewards.class);
        var player = mock(Player.class); var id = UUID.randomUUID(); when(player.getUniqueId()).thenReturn(id); when(player.isOnline()).thenReturn(true);
        Map<String, FishAlbum.Record> records = new HashMap<>();
        FishCatalog.all().subList(0, 5).forEach(species -> records.put(species.id(), new FishAlbum.Record(1, 10, FishingModel.Size.NORMAL)));
        when(album.records(id)).thenReturn(records);
        when(rewards.progress(player)).thenReturn(new AlbumRewardJournal.Progress(0, 0, 0, 0));
        when(rewards.claim(player)).thenReturn(AlbumRewardJournal.Result.DONE);
        var top = mock(Inventory.class); var view = mock(InventoryView.class); when(view.getTopInventory()).thenReturn(top);
        when(player.getOpenInventory()).thenReturn(view);
        var holder = new AtomicReference<InventoryHolder>(); when(top.getHolder()).thenAnswer(c -> holder.get());
        var scheduler = mock(BukkitScheduler.class);
        doAnswer(c -> { c.getArgument(1, Runnable.class).run(); return null; }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
        var gui = new FishAlbumGui(album); gui.setBack(plugin, p -> {}); gui.setRewards(rewards);
        List<ItemMeta> metadata = new ArrayList<>();
        try (var bukkit = mockStatic(Bukkit.class);
             var items = mockConstruction(ItemStack.class, (item, context) -> {
                 var meta = mock(ItemMeta.class); metadata.add(meta); when(item.getItemMeta()).thenReturn(meta);
             })) {
            bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(54), any(Component.class)))
                    .thenAnswer(c -> { holder.set(c.getArgument(0)); return top; });
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            gui.open(player);
            if (enabled) verify(metadata.get(45)).setItemModel(FishAlbumVisuals.Icon.REWARD_AVAILABLE.model());
            else verify(metadata.get(45), never()).setItemModel(any());
            var click = click(view, player, 48); gui.click(click);
            verify(click).setCancelled(true); verify(rewards, times(1)).claim(player);
            verify(album, never()).record(any(), any(), anyLong());
        }
        }
    }

    private static InventoryClickEvent click(InventoryView view, Player player, int slot) {
        var click = mock(InventoryClickEvent.class); when(click.getView()).thenReturn(view);
        when(click.getWhoClicked()).thenReturn(player); when(click.getRawSlot()).thenReturn(slot); return click;
    }
}
