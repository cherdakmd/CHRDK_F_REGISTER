package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Раскладка GUI: записи не накладываются на кнопки и не выходят за инвентарь. */
class AdminPanelLayoutTest {

    private static final int[] BUTTONS = {
            AdminPanelGui.SLOT_PLAYERS, AdminPanelGui.SLOT_MODERATORS, AdminPanelGui.SLOT_JOURNAL,
            AdminPanelGui.SLOT_INFO, AdminPanelGui.SLOT_FILTER, AdminPanelGui.SLOT_REFRESH,
            AdminPanelGui.SLOT_PREV, AdminPanelGui.SLOT_NEXT, AdminPanelGui.SLOT_WARN,
            AdminPanelGui.SLOT_BAN, AdminPanelGui.SLOT_ROLE, AdminPanelGui.SLOT_BACK,
            AdminPanelGui.SLOT_CLOSE, 46, 49
    };

    @Test
    void entriesFitSixRowsWithoutCollidingWithButtons() {
        assertEquals(54, AdminPanelGui.SIZE);
        assertEquals(28, AdminPanelGui.ENTRY_SLOTS.length);
        Set<Integer> buttons = new HashSet<>();
        for (int slot : BUTTONS) buttons.add(slot);
        Set<Integer> entries = new HashSet<>();
        for (int slot : AdminPanelGui.ENTRY_SLOTS) {
            assertTrue(slot >= 9 && slot < 45, "запись вне средних рядов: " + slot);
            assertFalse(buttons.contains(slot), "слот записи перекрывает кнопку: " + slot);
            assertTrue(entries.add(slot), "дублирующийся слот: " + slot);
        }
        // Кнопки профиля и страниц лежат в верхнем/нижнем ряду.
        for (int slot : BUTTONS) {
            assertTrue(slot >= 0 && slot < AdminPanelGui.SIZE, "кнопка вне инвентаря: " + slot);
            assertTrue(slot < 9 || slot >= 45, "кнопка в области записей: " + slot);
        }
    }

    @Test
    void everyPageHasADistinctTitleAndNavigation() {
        assertEquals(4, AdminPanelGui.Page.values().length);
        assertNotNull(AdminPanelGui.Page.valueOf("PROFILE"));
        assertNotNull(AdminPanelGui.Page.valueOf("MODERATORS"));
        assertNotNull(AdminPanelGui.Page.valueOf("JOURNAL"));
        assertEquals(AdminPanelGui.Page.PLAYERS, AdminPanelGui.Page.valueOf("PLAYERS"));
    }
}
