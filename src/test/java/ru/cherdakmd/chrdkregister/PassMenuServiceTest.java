package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GUI-меню /pass menu (1.4.5): раскладка иконок по стадии игрока и правам.
 */
class PassMenuServiceTest {

    private static Set<Integer> slotsOf(List<PassMenuService.MenuEntry> entries) {
        return entries.stream().map(PassMenuService.MenuEntry::slot).collect(Collectors.toSet());
    }

    @Test
    void noPassPlayerSeesOnlyPaymentRelatedItems() {
        List<PassMenuService.MenuEntry> layout = PassMenuService.layout(false, false);
        assertEquals(3, layout.size());
        Set<PassMenuService.Action> actions = layout.stream()
                .map(PassMenuService.MenuEntry::action).collect(Collectors.toSet());
        assertTrue(actions.contains(PassMenuService.Action.PAYMENT));
        assertTrue(actions.contains(PassMenuService.Action.TIERS));
        assertTrue(actions.contains(PassMenuService.Action.STATUS));
        assertFalse(actions.contains(PassMenuService.Action.TWO_FACTOR),
                "без авторизации пункт 2FA не показываем");
        assertFalse(actions.contains(PassMenuService.Action.SESSIONS));
    }

    @Test
    void noPassPlayerNeverSeesAdminStats() {
        List<PassMenuService.MenuEntry> layout = PassMenuService.layout(false, true);
        assertFalse(layout.stream().anyMatch(e -> e.action() == PassMenuService.Action.STATS),
                "статистику показываем только авторизованным администраторам");
    }

    @Test
    void authenticatedPlayerGetsFullMenu() {
        List<PassMenuService.MenuEntry> layout = PassMenuService.layout(true, false);
        assertEquals(6, layout.size());
        assertFalse(layout.stream().anyMatch(e -> e.action() == PassMenuService.Action.STATS));
    }

    @Test
    void adminGetsStatsTileOnTop() {
        List<PassMenuService.MenuEntry> layout = PassMenuService.layout(true, true);
        assertEquals(7, layout.size());
        assertTrue(layout.stream().anyMatch(e -> e.action() == PassMenuService.Action.STATS));
    }

    @Test
    void slotsAreUniqueAndInsideInventory() {
        for (boolean authenticated : new boolean[]{false, true}) {
            for (boolean admin : new boolean[]{false, true}) {
                List<PassMenuService.MenuEntry> layout = PassMenuService.layout(authenticated, admin);
                assertEquals(layout.size(), slotsOf(layout).size().intValue(),
                        "слоты не должны пересекаться (auth=" + authenticated + ", admin=" + admin + ")");
                for (PassMenuService.MenuEntry entry : layout) {
                    assertTrue(entry.slot() >= 0 && entry.slot() < PassMenuService.INVENTORY_SIZE,
                            "слот вне инвентаря: " + entry.slot());
                }
            }
        }
    }

    @Test
    void everyActionHasIconAndMessageKey() {
        for (PassMenuService.Action action : PassMenuService.Action.values()) {
            assertFalse(PassMenuService.keyOf(action).isBlank(), action.name());
            assertTrue(PassMenuService.materialOf(action).isItem(), action.name());
        }
    }
}
