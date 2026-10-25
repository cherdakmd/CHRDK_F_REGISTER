package ru.cherdakmd.chrdkartifacts;

import org.bukkit.event.block.Action;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactUseRulesTest {

    @Test
    void onlyRightClicksAreArtifactClicks() {
        assertTrue(ArtifactUseRules.isRightClick(Action.RIGHT_CLICK_AIR));
        assertTrue(ArtifactUseRules.isRightClick(Action.RIGHT_CLICK_BLOCK));
        assertFalse(ArtifactUseRules.isRightClick(Action.LEFT_CLICK_AIR));
        assertFalse(ArtifactUseRules.isRightClick(Action.LEFT_CLICK_BLOCK));
        assertFalse(ArtifactUseRules.isRightClick(Action.PHYSICAL));
    }

    @Test
    void airClickAlwaysActivates() {
        assertTrue(ArtifactUseRules.shouldActivate(Action.RIGHT_CLICK_AIR, false, false));
        assertTrue(ArtifactUseRules.shouldActivate(Action.RIGHT_CLICK_AIR, false, true));
        // флаги «блок интерактивный» к воздуху не относятся
        assertTrue(ArtifactUseRules.shouldActivate(Action.RIGHT_CLICK_AIR, true, true));
    }

    @Test
    void interactableBlockKeepsVanillaBehaviour() {
        assertTrue(ArtifactUseRules.passesToBlock(Action.RIGHT_CLICK_BLOCK, true, false));
        assertFalse(ArtifactUseRules.shouldActivate(Action.RIGHT_CLICK_BLOCK, true, false));
    }

    @Test
    void sneakingOnBlockKeepsVanillaBehaviour() {
        assertTrue(ArtifactUseRules.passesToBlock(Action.RIGHT_CLICK_BLOCK, false, true));
        assertFalse(ArtifactUseRules.shouldActivate(Action.RIGHT_CLICK_BLOCK, false, true));
    }

    @Test
    void plainBlockActivatesArtifact() {
        assertFalse(ArtifactUseRules.passesToBlock(Action.RIGHT_CLICK_BLOCK, false, false));
        assertTrue(ArtifactUseRules.shouldActivate(Action.RIGHT_CLICK_BLOCK, false, false));
    }

    @Test
    void leftClicksNeverActivateAndNeverPassToBlock() {
        assertFalse(ArtifactUseRules.shouldActivate(Action.LEFT_CLICK_BLOCK, false, false));
        assertFalse(ArtifactUseRules.passesToBlock(Action.LEFT_CLICK_BLOCK, true, true));
        assertFalse(ArtifactUseRules.shouldActivate(Action.PHYSICAL, true, false));
    }
}
