package ru.cherdakmd.chrdkfishing.bukkit;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FishCookingVisualsTest {
    @Test void recipeStatesAndControlsHaveDistinctModels() {
        var names = new HashSet<NamespacedKey>();
        for (var recipe : FishCookingPlanner.Recipe.values()) for (boolean selected : new boolean[]{false, true}) {
            var key = FishCookingVisuals.recipe(recipe, selected);
            assertEquals("chrdk_fishing", key.getNamespace());
            assertEquals("menu/cooking/" + recipe.name().toLowerCase(java.util.Locale.ROOT) + (selected ? "_selected" : ""), key.getKey());
            assertTrue(names.add(key));
        }
        for (var icon : FishCookingVisuals.Icon.values()) assertTrue(names.add(icon.model()));
        assertEquals(10, names.size());
        assertThrows(IllegalArgumentException.class, () -> FishCookingVisuals.recipe(null, false));
    }
    @Test void disabledOrForeignMetadataIsNotOverwritten() {
        var meta = mock(ItemMeta.class);
        FishCookingVisuals.fish(meta, "pike", false); FishCookingVisuals.recipe(meta, FishCookingPlanner.Recipe.SOUP, true, false);
        FishCookingVisuals.button(meta, FishCookingVisuals.Icon.CONFIRM, false); verifyNoInteractions(meta);
        when(meta.getItemModel()).thenReturn(new NamespacedKey("other", "model"));
        FishCookingVisuals.fish(meta, "pike", true); FishCookingVisuals.recipe(meta, FishCookingPlanner.Recipe.SOUP, true, true);
        FishCookingVisuals.button(meta, FishCookingVisuals.Icon.CONFIRM, true); verify(meta, never()).setItemModel(any());
        when(meta.getItemModel()).thenReturn(null); when(meta.hasCustomModelData()).thenReturn(true);
        FishCookingVisuals.fish(meta, "pike", true); FishCookingVisuals.button(meta, FishCookingVisuals.Icon.CONFIRM, true);
        verify(meta, never()).setItemModel(any());
        when(meta.hasCustomModelData()).thenReturn(false); FishCookingVisuals.fish(meta, "pike", true);
        verify(meta).setItemModel(FishingVisuals.fish("pike"));
    }
}
