package ru.cherdakmd.chrdkbp;

import java.util.HashSet;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BattlePassGuideVisualsTest {
    @Test void allControlsAndRewardStatesUseDistinctFixedModels() {
        var keys = new HashSet<NamespacedKey>();
        for (String title : new String[]{"guide-season", "guide-tasks", "guide-rewards", "guide-help", "menu-claim-all", "menu-premium-name", "atlas-title-season", "exp-menu", "guide-bank", "guide-recommended", "menu-close", "guide-reward-page", "guide-free", "guide-vip", "guide-premium", "guide-mission-name", "guide-back", "menu-prev", "menu-next", "guide-claim-one", "guide-fair", "guide-targets"}) assertTrue(keys.add(BattlePassGuideVisuals.button(title)), title);
        for (String track : new String[]{"FREE", "VIP", "PREMIUM"}) for (String state : new String[]{"no-reward", "received", "future", "access-locked", "in-progress", "ready"})
            assertTrue(keys.add(BattlePassGuideVisuals.track(track, "guide-" + state)), track + state);
        assertEquals(40,keys.size()); assertNull(BattlePassGuideVisuals.button("guide-track-reward"));
        assertNull(BattlePassGuideVisuals.button(null)); assertNull(BattlePassGuideVisuals.track("FREE","guide-bogus")); assertNull(BattlePassGuideVisuals.track("OTHER","guide-ready")); assertNull(BattlePassGuideVisuals.track(null,null));
    }
}
