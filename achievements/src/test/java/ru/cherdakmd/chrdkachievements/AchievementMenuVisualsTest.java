package ru.cherdakmd.chrdkachievements;

import java.util.HashSet;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AchievementMenuVisualsTest {
    @Test void allCategoriesAndNavigationHaveDistinctModels() {
        var models = new HashSet<NamespacedKey>();
        for (var category : AchievementDef.Category.values()) {
            assertTrue(models.add(AchievementMenuVisuals.entry(category, false)));
            assertTrue(models.add(AchievementMenuVisuals.entry(category, true)));
            assertTrue(models.add(AchievementMenuVisuals.tab("tab-" + category.id(), false)));
            assertTrue(models.add(AchievementMenuVisuals.tab("tab-" + category.id(), true)));
        }
        assertTrue(models.add(AchievementMenuVisuals.tab("tab-all", false)));
        assertTrue(models.add(AchievementMenuVisuals.tab("tab-all", true)));
        for (var icon : AchievementMenuVisuals.Control.values()) assertTrue(models.add(icon.model()));
        assertEquals(23, models.size());
        assertNull(AchievementMenuVisuals.tab("unknown", true));
        assertNull(AchievementMenuVisuals.page("unknown"));
        assertNull(AchievementMenuVisuals.button("unknown"));
        assertEquals(AchievementMenuVisuals.Control.ATLAS, AchievementMenuVisuals.button("atlas-title-lifetime"));
        assertEquals(AchievementMenuVisuals.Control.PREV, AchievementMenuVisuals.page("page-prev"));
        assertEquals(AchievementMenuVisuals.Control.NEXT, AchievementMenuVisuals.page("page-next"));
    }
    @Test void disabledAndForeignMetadataAreNotOverwritten() {
        var meta = mock(ItemMeta.class); var model = AchievementMenuVisuals.Control.ATLAS.model();
        AchievementMenuVisuals.apply(meta, model, false); verifyNoInteractions(meta);
        AchievementMenuVisuals.apply(null, model, true);
        when(meta.getItemModel()).thenReturn(new NamespacedKey("other", "model"));
        AchievementMenuVisuals.apply(meta, model, true); verify(meta, never()).setItemModel(any());
        when(meta.getItemModel()).thenReturn(null); when(meta.hasCustomModelData()).thenReturn(true);
        AchievementMenuVisuals.apply(meta, model, true); verify(meta, never()).setItemModel(any());
        when(meta.hasCustomModelData()).thenReturn(false);
        AchievementMenuVisuals.apply(meta, model, true); verify(meta).setItemModel(model);
        verify(meta, never()).getPersistentDataContainer();
    }
}
