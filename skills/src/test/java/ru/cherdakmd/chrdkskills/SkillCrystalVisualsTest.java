package ru.cherdakmd.chrdkskills;

import java.util.HashSet;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SkillCrystalVisualsTest {
    @Test void eachSkillHasOwnModelAndOptInIsRequired() {
        var keys = new HashSet<NamespacedKey>();
        for (var skill : SkillType.values()) {
            var meta = mock(ItemMeta.class);
            SkillCrystalVisuals.applyNew(meta, skill, false);
            verifyNoInteractions(meta);
            SkillCrystalVisuals.applyNew(meta, skill, true);
            var key = SkillCrystalVisuals.model(skill);
            assertEquals("chrdk_skills", key.getNamespace());
            assertEquals("crystal/" + skill.id(), key.getKey());
            assertTrue(keys.add(key));
            verify(meta).setItemModel(key);
            verify(meta, never()).getPersistentDataContainer();
        }
        assertEquals(10, keys.size());
    }

    @Test void existingOtherModelsAndCustomModelDataAreNotOverwritten() {
        var meta = mock(ItemMeta.class);
        when(meta.getItemModel()).thenReturn(new NamespacedKey("other", "model"));
        SkillCrystalVisuals.applyNew(meta, SkillType.MINING, true);
        verify(meta, never()).setItemModel(any());
        reset(meta);
        when(meta.hasCustomModelData()).thenReturn(true);
        SkillCrystalVisuals.applyNew(meta, SkillType.MINING, true);
        verify(meta, never()).setItemModel(any());
    }
}
