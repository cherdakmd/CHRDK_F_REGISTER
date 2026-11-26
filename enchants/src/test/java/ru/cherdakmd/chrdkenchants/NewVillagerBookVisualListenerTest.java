package ru.cherdakmd.chrdkenchants;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Villager;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.VillagerAcquireTradeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NewVillagerBookVisualListenerTest {
    @Test void newOfferCopiesEveryTradeFieldAndOnlyDecoratesItsResult() throws Exception {
        var event = mock(VillagerAcquireTradeEvent.class);
        when(event.getEntity()).thenReturn(mock(Villager.class));
        var original = mock(MerchantRecipe.class); when(event.getRecipe()).thenReturn(original);
        var result = mock(ItemStack.class); var meta = mock(ItemMeta.class);
        when(original.getResult()).thenReturn(result);
        when(result.getType()).thenReturn(Material.ENCHANTED_BOOK); when(result.hasItemMeta()).thenReturn(true); when(result.getItemMeta()).thenReturn(meta);
        when(result.clone()).thenReturn(result);
        var ingredient1 = mock(ItemStack.class); var ingredient2 = mock(ItemStack.class);
        when(ingredient1.clone()).thenReturn(ingredient1); when(ingredient2.clone()).thenReturn(ingredient2);
        when(original.getIngredients()).thenReturn(List.of(ingredient1, ingredient2));
        when(original.getUses()).thenReturn(2); when(original.getMaxUses()).thenReturn(12); when(original.hasExperienceReward()).thenReturn(true);
        when(original.getVillagerExperience()).thenReturn(7); when(original.getPriceMultiplier()).thenReturn(0.2f);
        when(original.getDemand()).thenReturn(3); when(original.getSpecialPrice()).thenReturn(-2); when(original.shouldIgnoreDiscounts()).thenReturn(true);
        var listener = new NewVillagerBookVisualListener(() -> true, book -> { assertSame(result, book); return true; });
        listener.onAcquire(event);
        var captor = org.mockito.ArgumentCaptor.forClass(MerchantRecipe.class); verify(event).setRecipe(captor.capture());
        var changed = captor.getValue(); assertNotSame(original, changed); assertSame(result, changed.getResult());
        assertEquals(List.of(ingredient1, ingredient2), changed.getIngredients());
        assertEquals(2, changed.getUses()); assertEquals(12, changed.getMaxUses()); assertTrue(changed.hasExperienceReward());
        assertEquals(7, changed.getVillagerExperience()); assertEquals(0.2f, changed.getPriceMultiplier());
        assertEquals(3, changed.getDemand()); assertEquals(-2, changed.getSpecialPrice()); assertTrue(changed.shouldIgnoreDiscounts());
        verify(original, never()).setIngredients(any()); verify(result, never()).setItemMeta(any());
        var annotation = NewVillagerBookVisualListener.class.getDeclaredMethod("onAcquire", VillagerAcquireTradeEvent.class).getAnnotation(EventHandler.class);
        assertEquals(EventPriority.HIGHEST, annotation.priority()); assertTrue(annotation.ignoreCancelled());
    }
    @Test void disabledOtherMerchantsAndForeignOrUnknownBooksAreUntouched() {
        var event = mock(VillagerAcquireTradeEvent.class); var villager = mock(Villager.class); when(event.getEntity()).thenReturn(villager);
        var recipe = mock(MerchantRecipe.class); when(event.getRecipe()).thenReturn(recipe);
        var result = mock(ItemStack.class); var meta = mock(ItemMeta.class); when(recipe.getResult()).thenReturn(result);
        when(result.getType()).thenReturn(Material.ENCHANTED_BOOK); when(result.hasItemMeta()).thenReturn(true); when(result.getItemMeta()).thenReturn(meta);
        var enabled = new AtomicBoolean(false); var calls = new int[]{0};
        var listener = new NewVillagerBookVisualListener(enabled::get, item -> { calls[0]++; return false; });
        listener.onAcquire(event); enabled.set(true);
        when(event.getEntity()).thenReturn(mock(WanderingTrader.class)); listener.onAcquire(event);
        when(event.getEntity()).thenReturn(villager);
        when(meta.getItemModel()).thenReturn(new NamespacedKey("other", "book")); listener.onAcquire(event);
        when(meta.getItemModel()).thenReturn(null); when(meta.hasCustomModelData()).thenReturn(true); listener.onAcquire(event);
        when(meta.hasCustomModelData()).thenReturn(false); when(meta.hasDisplayName()).thenReturn(true); listener.onAcquire(event);
        when(meta.hasDisplayName()).thenReturn(false); when(meta.hasLore()).thenReturn(true); listener.onAcquire(event);
        when(meta.hasLore()).thenReturn(false); listener.onAcquire(event);
        assertEquals(1, calls[0]); verify(event, never()).setRecipe(any()); verify(result, never()).setItemMeta(any());
    }
}
