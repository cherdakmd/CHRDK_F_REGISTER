package ru.cherdakmd.chrdkfishing.bukkit;

import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkfishing.FishToken;
import ru.cherdakmd.chrdkfishing.FishingModel;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DishItemVisualsTest {
    @Test void modelsCoverRecipesAndRespectTheOptInAndForeignMetadata() {
        var keys = new HashSet<NamespacedKey>();
        for (var recipe : FishCookingPlanner.Recipe.values()) {
            assertEquals("dish/" + recipe.name().toLowerCase(java.util.Locale.ROOT), FishingVisuals.dish(recipe).getKey());
            assertTrue(keys.add(FishingVisuals.dish(recipe)));
        }
        assertEquals(3, keys.size());
        var meta = mock(ItemMeta.class);
        FishingVisuals.applyNewDish(meta, FishCookingPlanner.Recipe.SOUP, false); verifyNoInteractions(meta);
        when(meta.getItemModel()).thenReturn(new NamespacedKey("other", "dish"));
        FishingVisuals.applyNewDish(meta, FishCookingPlanner.Recipe.SOUP, true); verify(meta, never()).setItemModel(any());
        when(meta.getItemModel()).thenReturn(null); when(meta.hasCustomModelData()).thenReturn(true);
        FishingVisuals.applyNewDish(meta, FishCookingPlanner.Recipe.SOUP, true); verify(meta, never()).setItemModel(any());
        when(meta.hasCustomModelData()).thenReturn(false);
        FishingVisuals.applyNewDish(meta, FishCookingPlanner.Recipe.SOUP, true);
        verify(meta).setItemModel(FishingVisuals.dish(FishCookingPlanner.Recipe.SOUP));
    }

    @Test void planningOnlyModelsTheNewDishAndKeepsTheInputSnapshot() {
        var token = FishToken.issue(new byte[32], UUID.randomUUID(), new FishingModel.Catch("pike", FishingModel.Size.NORMAL, 40), 70);
        for (var recipe : FishCookingPlanner.Recipe.values()) for (boolean enabled : new boolean[]{false, true}) {
            var fish = mock(ItemStack.class); when(fish.clone()).thenReturn(fish);
            var bowl = mock(ItemStack.class); when(bowl.clone()).thenReturn(bowl);
            when(bowl.getType()).thenReturn(Material.BOWL); when(bowl.getAmount()).thenReturn(1); when(bowl.getMaxStackSize()).thenReturn(64);
            var contents = new ItemStack[36]; contents[0] = fish; if (recipe.needsBowl()) contents[1] = bowl;
            var codec = mock(FishItemCodec.class); when(codec.read(fish)).thenReturn(Optional.of(token));
            when(codec.read(any())).thenReturn(Optional.empty()); when(codec.read(fish)).thenReturn(Optional.of(token));
            var meta = mock(ItemMeta.class); var arguments = new java.util.ArrayList<Object>();
            try (var created = mockConstruction(ItemStack.class, (item, context) -> {
                arguments.add(context.arguments().get(0));
                when(item.getItemMeta()).thenReturn(meta); when(item.clone()).thenReturn(item);
            })) {
                var plan = FishCookingPlanner.plan(contents, 0, codec, recipe, enabled);
                var dish = created.constructed().get(0);
                assertEquals(recipe.material(), arguments.get(0));
                assertSame(fish, plan.before()[0]); assertSame(dish, plan.after()[0]);
                assertSame(fish, contents[0]); assertEquals(token, plan.fish()); assertEquals(recipe, plan.recipe());
                if (recipe.needsBowl()) { assertSame(bowl, plan.before()[1]); assertNull(plan.after()[1]); assertSame(bowl, contents[1]); }
                if (enabled) verify(meta).setItemModel(FishingVisuals.dish(recipe));
                else verify(meta, never()).setItemModel(any());
                verify(dish).setItemMeta(meta); verify(fish, never()).setItemMeta(any()); verify(bowl, never()).setItemMeta(any());
            }
        }
    }
}
