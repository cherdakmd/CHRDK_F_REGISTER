package ru.cherdakmd.chrdkenchants;

import org.bukkit.entity.Player;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.view.AnvilView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnvilPreviewSyncTest {
    @Test void onlyOverVanillaLevelIncreaseSchedulesSync() {
        assertTrue(VanillaMergeListener.needsPreviewSync(Map.of("efficiency",5), Map.of("efficiency",6), k->5));
        assertFalse(VanillaMergeListener.needsPreviewSync(Map.of("efficiency",4), Map.of("efficiency",5), k->5));
        assertFalse(VanillaMergeListener.needsPreviewSync(Map.of("efficiency",6), Map.of("efficiency",6), k->5));
    }
    @Test void multipleEventsCoalesceAndSyncOnlyAfterEventProcessing() {
        List<Runnable> tasks = new ArrayList<>();
        var sync = new AnvilPreviewSync(tasks::add);
        var player = mock(Player.class); var view = mock(AnvilView.class); var top = mock(AnvilInventory.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true); when(player.getOpenInventory()).thenReturn(view);
        when(view.getPlayer()).thenReturn(player); when(view.getTopInventory()).thenReturn(top);
        sync.queue(view); sync.queue(view);
        assertEquals(1,tasks.size()); verify(player,never()).updateInventory();
        tasks.get(0).run(); verify(player,times(1)).updateInventory();
        verify(top,never()).setItem(anyInt(),any());
        verify(view,never()).setRepairCost(anyInt());
        verify(view,never()).setRepairItemCountCost(anyInt());
        sync.queue(view); assertEquals(2,tasks.size());
    }
    @Test void closedOrDifferentAnvilAndOfflinePlayerAreNeverSynced() {
        List<Runnable> tasks = new ArrayList<>(); var sync = new AnvilPreviewSync(tasks::add);
        var player = mock(Player.class); var original = mock(AnvilView.class); var other = mock(AnvilView.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID()); when(original.getPlayer()).thenReturn(player);
        when(player.isOnline()).thenReturn(true); when(player.getOpenInventory()).thenReturn(other);
        when(original.getTopInventory()).thenReturn(mock(AnvilInventory.class));
        when(other.getTopInventory()).thenReturn(mock(AnvilInventory.class));
        sync.queue(original); tasks.remove(0).run(); verify(player,never()).updateInventory();
        when(player.isOnline()).thenReturn(false);
        sync.queue(original); tasks.remove(0).run(); verify(player,never()).updateInventory();
    }
    @Test void replacementViewBeforeTaskUsesTheLatestWindow() {
        List<Runnable> tasks = new ArrayList<>(); var sync = new AnvilPreviewSync(tasks::add);
        var player = mock(Player.class); var first = mock(AnvilView.class); var second = mock(AnvilView.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID()); when(player.isOnline()).thenReturn(true);
        when(first.getPlayer()).thenReturn(player); when(second.getPlayer()).thenReturn(player);
        var firstInventory = mock(AnvilInventory.class); var secondInventory = mock(AnvilInventory.class);
        when(first.getTopInventory()).thenReturn(firstInventory); when(second.getTopInventory()).thenReturn(secondInventory);
        when(player.getOpenInventory()).thenReturn(second);
        sync.queue(first); sync.queue(second); assertEquals(1,tasks.size());
        tasks.get(0).run(); verify(player,times(1)).updateInventory();
    }
    @Test void schedulerFailureDoesNotLeaveStuckPendingEntry() {
        List<Runnable> tasks = new ArrayList<>(); int[] calls = {0};
        var sync = new AnvilPreviewSync(task -> { if (calls[0]++ == 0) throw new IllegalStateException(); tasks.add(task); });
        var player = mock(Player.class); var view = mock(AnvilView.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID()); when(view.getPlayer()).thenReturn(player);
        assertThrows(IllegalStateException.class, () -> sync.queue(view));
        sync.queue(view); assertEquals(1,tasks.size());
    }
}
