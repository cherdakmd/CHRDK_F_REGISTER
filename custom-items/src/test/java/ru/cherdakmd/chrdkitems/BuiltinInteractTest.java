package ru.cherdakmd.chrdkitems;

import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuiltinInteractTest {
    @Test void onlyMainHandRightClicksAndProtectedBlocksAreIgnored() {
        assertTrue(BuiltinInteract.allowed(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, true, true, true));
        assertTrue(BuiltinInteract.allowed(Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND, false, true, true));
        assertFalse(BuiltinInteract.allowed(Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND, true, true, true));
        assertFalse(BuiltinInteract.allowed(Action.RIGHT_CLICK_AIR, EquipmentSlot.OFF_HAND, false, true, true));
        assertFalse(BuiltinInteract.allowed(Action.LEFT_CLICK_AIR, EquipmentSlot.HAND, false, true, true));
        assertFalse(BuiltinInteract.allowed(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, false, false, true));
        assertFalse(BuiltinInteract.allowed(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, false, true, false));
        assertFalse(BuiltinInteract.allowed(Action.RIGHT_CLICK_AIR, null, false, true, true));
    }
    @Test void onlyTheThreeUtilityDefinitionsCanActivate() {
        for (var def : java.util.List.of(BuiltinItems.CAT_FINDER, BuiltinItems.SPAWN_GUIDE, BuiltinItems.WEATHER_GAUGE)) {
            assertTrue(BuiltinUse.supports(def));
            assertTrue(PrototypeEffects.identityMatchesFields(def.material(), 1, def.id(), 1, def));
            assertFalse(PrototypeEffects.identityMatchesFields(Material.DIAMOND, 1, def.id(), 1, def));
            assertFalse(PrototypeEffects.identityMatchesFields(def.material(), 1, def.id(), 2, def));
            assertFalse(PrototypeEffects.identityMatchesFields(def.material(), 0, def.id(), 1, def));
        }
        assertFalse(BuiltinUse.supports(BuiltinItems.LITTER));
        assertFalse(BuiltinUse.supports(BuiltinItems.SHOVEL));
        assertFalse(BuiltinUse.supports(null));
    }
}
