package ru.cherdakmd.chrdkquests;

import java.util.HashSet;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class QuestMenuVisualsTest {
    @Test void everyTypeAndStatusHasItsOwnModel() {
        var keys = new HashSet<NamespacedKey>();
        for (var type : QuestType.values()) for (var state : QuestService.Availability.values()) {
            var key = QuestMenuVisuals.quest(type, state);
            assertEquals("chrdk_quests", key.getNamespace());
            assertEquals("menu/quest/" + type.id() + "_" + state.name().toLowerCase(java.util.Locale.ROOT), key.getKey());
            assertTrue(keys.add(key));
        }
        assertEquals(21, keys.size());
        for (var icon : QuestMenuVisuals.Control.values()) assertTrue(keys.add(icon.model()));
        assertEquals(24, keys.size());
    }

    @Test void optInAndForeignMetadataAreRespected() {
        var meta = mock(ItemMeta.class);
        var model = QuestMenuVisuals.quest(QuestType.GATHER, QuestService.Availability.READY);
        QuestMenuVisuals.apply(meta, model, false); verifyNoInteractions(meta);
        QuestMenuVisuals.apply(null, model, true);
        when(meta.getItemModel()).thenReturn(new NamespacedKey("other", "model"));
        QuestMenuVisuals.apply(meta, model, true); verify(meta, never()).setItemModel(any());
        when(meta.getItemModel()).thenReturn(null); when(meta.hasCustomModelData()).thenReturn(true);
        QuestMenuVisuals.apply(meta, model, true); verify(meta, never()).setItemModel(any());
        when(meta.hasCustomModelData()).thenReturn(false);
        QuestMenuVisuals.apply(meta, model, true); verify(meta).setItemModel(model);
    }
}
