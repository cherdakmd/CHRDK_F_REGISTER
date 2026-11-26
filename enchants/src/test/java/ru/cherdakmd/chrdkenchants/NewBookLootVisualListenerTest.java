package ru.cherdakmd.chrdkenchants;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.loot.LootTable;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NewBookLootVisualListenerTest {
    @Test void onlyNewVanillaLootBooksAreClonedAndDecorated() throws Exception {
        var event = mock(LootGenerateEvent.class); var table = mock(LootTable.class);
        when(event.getLootTable()).thenReturn(table); when(table.getKey()).thenReturn(new NamespacedKey("minecraft", "chests/example"));
        var old = mock(ItemStack.class); var copy = mock(ItemStack.class); var meta = mock(ItemMeta.class);
        when(old.getType()).thenReturn(Material.ENCHANTED_BOOK); when(old.hasItemMeta()).thenReturn(true);
        when(old.getItemMeta()).thenReturn(meta); when(old.clone()).thenReturn(copy);
        var other = mock(ItemStack.class); when(other.getType()).thenReturn(Material.DIAMOND);
        var foreign = mock(ItemStack.class); var foreignMeta = mock(ItemMeta.class);
        when(foreign.getType()).thenReturn(Material.ENCHANTED_BOOK); when(foreign.hasItemMeta()).thenReturn(true);
        when(foreign.getItemMeta()).thenReturn(foreignMeta); when(foreignMeta.getItemModel()).thenReturn(new NamespacedKey("other", "book"));
        var loot = new ArrayList<ItemStack>(List.of(old, other, foreign)); when(event.getLoot()).thenReturn(loot);
        var called = new ArrayList<ItemStack>(); var enabled = new AtomicBoolean(true);
        var listener = new NewBookLootVisualListener(enabled::get, item -> { called.add(item); return true; });
        listener.onLoot(event);
        assertEquals(List.of(copy, other, foreign), loot); assertEquals(List.of(copy), called);
        verify(old, never()).setItemMeta(any()); verify(foreign, never()).clone(); verify(other, never()).clone();
        var annotation = NewBookLootVisualListener.class.getDeclaredMethod("onLoot", LootGenerateEvent.class).getAnnotation(EventHandler.class);
        assertEquals(EventPriority.HIGHEST, annotation.priority()); assertTrue(annotation.ignoreCancelled());
    }
    @Test void disabledPluginAndForeignLootSourcesAreNotTouched() {
        var event = mock(LootGenerateEvent.class); var table = mock(LootTable.class); when(event.getLootTable()).thenReturn(table);
        when(table.getKey()).thenReturn(new NamespacedKey("minecraft", "chests/test"));
        var item = mock(ItemStack.class); when(item.getType()).thenReturn(Material.ENCHANTED_BOOK);
        var loot = new ArrayList<ItemStack>(List.of(item)); when(event.getLoot()).thenReturn(loot);
        var enabled = new AtomicBoolean(false); var called = new ArrayList<ItemStack>();
        var listener = new NewBookLootVisualListener(enabled::get, book -> { called.add(book); return true; });
        listener.onLoot(event); enabled.set(true); when(event.isPlugin()).thenReturn(true); listener.onLoot(event);
        when(event.isPlugin()).thenReturn(false); when(table.getKey()).thenReturn(new NamespacedKey("other", "chests/test")); listener.onLoot(event);
        assertEquals(List.of(item), loot); assertTrue(called.isEmpty()); verify(item, never()).clone();
    }
    @Test void unhandledBooksAndCustomModelDataRemainUnchanged() {
        var event = mock(LootGenerateEvent.class); var table = mock(LootTable.class); when(event.getLootTable()).thenReturn(table);
        when(table.getKey()).thenReturn(new NamespacedKey("minecraft", "chests/test"));
        var first = mock(ItemStack.class); var second = mock(ItemStack.class); var firstMeta = mock(ItemMeta.class); var secondMeta = mock(ItemMeta.class);
        for (var book : List.of(first, second)) { when(book.getType()).thenReturn(Material.ENCHANTED_BOOK); when(book.hasItemMeta()).thenReturn(true); }
        when(first.getItemMeta()).thenReturn(firstMeta); when(second.getItemMeta()).thenReturn(secondMeta);
        when(secondMeta.hasCustomModelData()).thenReturn(true); var clone = mock(ItemStack.class); when(first.clone()).thenReturn(clone);
        var loot = new ArrayList<ItemStack>(List.of(first, second)); when(event.getLoot()).thenReturn(loot);
        new NewBookLootVisualListener(() -> true, item -> false).onLoot(event);
        assertEquals(List.of(first, second), loot); verify(second, never()).clone();
        verify(first, never()).setItemMeta(any()); verify(second, never()).setItemMeta(any());
    }
    @Test void fishingOnlyDecoratesFreshCatchNotHookedEntities() throws Exception {
        var event = mock(org.bukkit.event.player.PlayerFishEvent.class); var entity = mock(org.bukkit.entity.Item.class);
        var original = mock(ItemStack.class); var copy = mock(ItemStack.class); var meta = mock(ItemMeta.class);
        when(original.getType()).thenReturn(Material.ENCHANTED_BOOK); when(original.hasItemMeta()).thenReturn(true);
        when(original.getItemMeta()).thenReturn(meta); when(original.clone()).thenReturn(copy);
        when(entity.getItemStack()).thenReturn(original); when(event.getCaught()).thenReturn(entity);
        var enabled = new AtomicBoolean(true); var calls = new ArrayList<ItemStack>();
        var listener = new NewBookLootVisualListener(enabled::get, item -> { calls.add(item); return true; });
        when(event.getState()).thenReturn(org.bukkit.event.player.PlayerFishEvent.State.CAUGHT_ENTITY);
        listener.onFishing(event); verify(entity, never()).setItemStack(any());
        when(event.getState()).thenReturn(org.bukkit.event.player.PlayerFishEvent.State.CAUGHT_FISH);
        enabled.set(false); listener.onFishing(event); verify(entity, never()).setItemStack(any());
        enabled.set(true); listener.onFishing(event); verify(entity).setItemStack(copy);
        assertEquals(List.of(copy), calls); verify(original, never()).setItemMeta(any());
        var annotation = NewBookLootVisualListener.class.getDeclaredMethod("onFishing", org.bukkit.event.player.PlayerFishEvent.class).getAnnotation(EventHandler.class);
        assertEquals(EventPriority.HIGHEST, annotation.priority()); assertTrue(annotation.ignoreCancelled());
    }
}
