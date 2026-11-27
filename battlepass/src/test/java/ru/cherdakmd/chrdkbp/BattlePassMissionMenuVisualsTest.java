package ru.cherdakmd.chrdkbp;

import java.util.HashSet;
import java.util.List;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BattlePassMissionMenuVisualsTest {
    @Test void allTypesAndStatesHaveIndependentModelsAndCustomIconsSurvive() {
        var keys = new HashSet<NamespacedKey>();
        for (var type : BattlePassConfig.MissionType.values()) {
            var mission = new BattlePassConfig.MissionDef("mission", "desc", type, "any", 10, 1, 1);
            for (var state : BattlePassView.State.values()) assertTrue(keys.add(BattlePassMenuVisuals.mission(mission, true, state)));
            assertEquals(new NamespacedKey("chrdk_battlepass", "menu/mission_future"), BattlePassMenuVisuals.mission(mission, false, BattlePassView.State.READY));
            var custom = new BattlePassConfig.MissionDef("custom", "desc", type, "any", 10, 1, 1, 10, List.of(), "", "DIAMOND");
            assertNull(BattlePassMenuVisuals.mission(custom, true, BattlePassView.State.READY));
            assertEquals(BattlePassMenuVisuals.mission(mission, false, BattlePassView.State.READY), BattlePassMenuVisuals.mission(custom, false, BattlePassView.State.READY));
        }
        assertEquals(40, keys.size()); keys.add(BattlePassMenuVisuals.mission(null, false, null));
        for (var filter : BattlePassFilter.values()) assertTrue(keys.add(BattlePassMenuVisuals.filter(filter)));
        for (String button : new String[]{"menu-filter-empty","menu-prev","menu-next","menu-claim-all","menu-back","menu-refresh","menu-premium-name","menu-close"}) assertTrue(keys.add(BattlePassMenuVisuals.button(button)));
        assertTrue(keys.add(BattlePassMenuVisuals.summary())); assertEquals(55, keys.size());
        assertNull(BattlePassMenuVisuals.button("not-a-button")); assertNull(BattlePassMenuVisuals.filter(null));
    }
    @Test void disabledAndForeignModelsRemainUntouched() {
        var meta=mock(ItemMeta.class); var key=BattlePassMenuVisuals.summary();
        BattlePassMenuVisuals.apply(meta,key,false); BattlePassMenuVisuals.apply(null,key,true); BattlePassMenuVisuals.apply(meta,null,true); verifyNoInteractions(meta);
        when(meta.getItemModel()).thenReturn(new NamespacedKey("other","art")); BattlePassMenuVisuals.apply(meta,key,true); verify(meta,never()).setItemModel(any());
        when(meta.getItemModel()).thenReturn(null); when(meta.hasCustomModelData()).thenReturn(true); BattlePassMenuVisuals.apply(meta,key,true); verify(meta,never()).setItemModel(any());
        when(meta.hasCustomModelData()).thenReturn(false); BattlePassMenuVisuals.apply(meta,key,true); verify(meta).setItemModel(key); verify(meta,never()).getPersistentDataContainer();
    }
}
