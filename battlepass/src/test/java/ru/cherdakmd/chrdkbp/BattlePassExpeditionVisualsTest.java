package ru.cherdakmd.chrdkbp;

import java.util.HashSet;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BattlePassExpeditionVisualsTest {
    @Test void independentModelsRepresentLicensesEffectsTitlesAndActions() {
        var models=new HashSet<NamespacedKey>();
        for(boolean premium:new boolean[]{false,true}) for(boolean owned:new boolean[]{false,true}) assertTrue(models.add(BattlePassExpeditionVisuals.license(premium,owned)));
        for(boolean value:new boolean[]{false,true}) assertTrue(models.add(BattlePassExpeditionVisuals.effect(value)));
        for(String title:new String[]{"off","free","premium"}) assertTrue(models.add(BattlePassExpeditionVisuals.title(title)));
        for(String name:new String[]{"exp-mark-current","exp-marks","exp-routes","exp-journal","exp-labels","exp-claim","guide-back","atlas-back","menu-close","exp-route-create"}) assertTrue(models.add(BattlePassExpeditionVisuals.button(name,null)),name);
        assertTrue(models.add(BattlePassExpeditionVisuals.button("exp-point-name","exp-point-lore")));
        assertTrue(models.add(BattlePassExpeditionVisuals.button("exp-point-name","exp-saved-route-lore")));
        assertEquals(21,models.size()); assertNull(BattlePassExpeditionVisuals.title("unknown")); assertNull(BattlePassExpeditionVisuals.title(null));
        assertNull(BattlePassExpeditionVisuals.button("exp-point-name",null)); assertNull(BattlePassExpeditionVisuals.button(null,null));
    }
}
