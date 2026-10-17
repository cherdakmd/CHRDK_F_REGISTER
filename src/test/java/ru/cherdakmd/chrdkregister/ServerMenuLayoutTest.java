package ru.cherdakmd.chrdkregister;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Серверное меню /menu (1.4.49): авто-раскладка слотов, явные слоты и размер инвентаря.
 */
class ServerMenuLayoutTest {

    private static ServerMenuSettings.Entry entry(int slot, String name) {
        return new ServerMenuSettings.Entry(slot, Material.PAPER, name, "", "pass");
    }

    @Test
    void emptyListGivesSmallEmptyChest() {
        ServerMenuService.MenuLayout layout = ServerMenuService.layout(List.of());
        assertEquals(27, layout.size());
        assertTrue(layout.slots().isEmpty());
    }

    @Test
    void autoEntriesFillCenterRow() {
        List<ServerMenuSettings.Entry> entries = List.of(
                entry(-1, "a"), entry(-1, "b"), entry(-1, "c"));
        ServerMenuService.MenuLayout layout = ServerMenuService.layout(entries);
        assertEquals(27, layout.size());
        assertEquals(List.of(10, 11, 12), layout.slots());
    }

    @Test
    void explicitSlotsWinOverAuto() {
        List<ServerMenuSettings.Entry> entries = List.of(
                entry(20, "explicit"), entry(-1, "auto"));
        ServerMenuService.MenuLayout layout = ServerMenuService.layout(entries);
        assertEquals(27, layout.size());
        assertEquals(20, layout.slots().get(0));
        assertEquals(10, layout.slots().get(1)); // авто не наезжает на занятое явным
    }

    @Test
    void duplicateExplicitSlotSecondFallsToAuto() {
        List<ServerMenuSettings.Entry> entries = List.of(
                entry(10, "first"), entry(10, "dupe"), entry(-1, "tail"));
        ServerMenuService.MenuLayout layout = ServerMenuService.layout(entries);
        assertEquals(27, layout.size());
        assertEquals(new HashSet<>(layout.slots()).size(), layout.slots().size(),
                "слоты не должны совпадать: " + layout.slots());
        assertTrue(layout.slots().contains(10));
    }

    @Test
    void explicitHighSlotGrowsInventory() {
        List<ServerMenuSettings.Entry> entries = List.of(entry(30, "deep"));
        ServerMenuService.MenuLayout layout = ServerMenuService.layout(entries);
        assertEquals(36, layout.size());
        assertEquals(30, layout.slots().get(0));
    }

    @Test
    void manyAutoEntriesGrowUpToFullChest() {
        List<ServerMenuSettings.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            entries.add(entry(-1, "auto" + i));
        }
        ServerMenuService.MenuLayout layout = ServerMenuService.layout(entries);
        assertTrue(layout.size() >= 45, "тоpp 30 иконок не влезает в 27: " + layout.size());
        assertFalse(layout.slots().contains(-1), "все иконки разместились: " + layout.slots());
        assertEquals(new HashSet<>(layout.slots()).size(), layout.slots().size(),
                "слоты не должны совпадать");
    }
}
