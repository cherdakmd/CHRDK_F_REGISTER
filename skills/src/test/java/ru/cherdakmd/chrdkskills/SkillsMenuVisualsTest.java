package ru.cherdakmd.chrdkskills;

import java.util.HashSet;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SkillsMenuVisualsTest {
    @Test void tabAndStateIconsAreDistinctAndSemanticallyBound() {
        var models = new HashSet<NamespacedKey>();
        for (var tab : SkillsMenuApi.Tab.values()) {
            assertTrue(models.add(SkillsMenuVisuals.tab(tab, false)));
            assertTrue(models.add(SkillsMenuVisuals.tab(tab, true)));
        }
        for (var icon : SkillsMenuVisuals.Control.values()) assertTrue(models.add(icon.model()));
        assertEquals(23, models.size());
        assertEquals(SkillsMenuVisuals.Control.PERK_ACTIVE, SkillsMenuVisuals.perk(true));
        assertEquals(SkillsMenuVisuals.Control.PERK_LOCKED, SkillsMenuVisuals.perk(false));
        assertEquals(SkillsMenuVisuals.Control.RESET_ATTRIBUTES, SkillsMenuVisuals.reset(false, false));
        assertEquals(SkillsMenuVisuals.Control.CONFIRM_ATTRIBUTES, SkillsMenuVisuals.reset(false, true));
        assertEquals(SkillsMenuVisuals.Control.RESET_BUILDS, SkillsMenuVisuals.reset(true, false));
        assertEquals(SkillsMenuVisuals.Control.CONFIRM_BUILDS, SkillsMenuVisuals.reset(true, true));
        assertEquals(SkillsMenuVisuals.Control.BACK, SkillsMenuVisuals.button("menu-rebirth-back"));
        assertNull(SkillsMenuVisuals.button("potion-shop-info"));
        assertNull(SkillsMenuVisuals.button("top-line"));
    }
    @Test void everySkillRebirthAttributeAndBranchHasItsOwnModel() {
        var models = new HashSet<NamespacedKey>();
        for (var skill : SkillType.values()) {
            assertEquals("menu/skill/" + skill.id(), SkillsMenuVisuals.skill(skill).getKey());
            assertTrue(models.add(SkillsMenuVisuals.skill(skill)));
            assertTrue(models.add(SkillsMenuVisuals.rebirth(skill, false)));
            assertTrue(models.add(SkillsMenuVisuals.rebirth(skill, true)));
        }
        for (var attribute : PlayerAttribute.values()) assertTrue(models.add(SkillsMenuVisuals.attribute(attribute)));
        for (var branch : BuildBranch.values()) assertTrue(models.add(SkillsMenuVisuals.branch(branch)));
        assertEquals(41, models.size());
        assertEquals("menu/rebirth/mining_ready", SkillsMenuVisuals.rebirth(SkillType.MINING, true).getKey());
        assertEquals("menu/rebirth/mining_waiting", SkillsMenuVisuals.rebirth(SkillType.MINING, false).getKey());
    }
    @Test void disabledOrForeignMetadataIsNotOverwritten() {
        var meta = mock(ItemMeta.class); var model = SkillsMenuVisuals.Control.GUIDE.model();
        SkillsMenuVisuals.apply(meta, model, false); verifyNoInteractions(meta);
        SkillsMenuVisuals.apply(null, model, true);
        when(meta.getItemModel()).thenReturn(new NamespacedKey("other", "item"));
        SkillsMenuVisuals.apply(meta, model, true); verify(meta, never()).setItemModel(any());
        when(meta.getItemModel()).thenReturn(null); when(meta.hasCustomModelData()).thenReturn(true);
        SkillsMenuVisuals.apply(meta, model, true); verify(meta, never()).setItemModel(any());
        when(meta.hasCustomModelData()).thenReturn(false);
        SkillsMenuVisuals.apply(meta, model, true); verify(meta).setItemModel(model);
        verify(meta, never()).getPersistentDataContainer();
    }
}
