package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Раскладка GUI: записи не накладываются на глобальные кнопки и не выходят за инвентарь. */
class AdminPanelLayoutTest {

    /** Кнопки, доступные на любой странице: верхний и нижний ряды. */
    private static final int[] GLOBAL_BUTTONS = {
            AdminPanelGui.SLOT_PLAYERS, AdminPanelGui.SLOT_MODERATORS, AdminPanelGui.SLOT_JOURNAL,
            AdminPanelGui.SLOT_MUTES, AdminPanelGui.SLOT_INFO, AdminPanelGui.SLOT_FILTER,
            AdminPanelGui.SLOT_REFRESH, AdminPanelGui.SLOT_PREV, AdminPanelGui.SLOT_NEXT,
            AdminPanelGui.SLOT_WARN, AdminPanelGui.SLOT_PUNISH, AdminPanelGui.SLOT_BAN,
            AdminPanelGui.SLOT_ROLE, AdminPanelGui.SLOT_BACK, AdminPanelGui.SLOT_CLOSE, 46
    };
    /** Кнопки страницы «Наказания»: там нет списка, поэтому слоты записей отданы действиям. */
    private static final int[] PUNISH_BUTTONS = {
            AdminPanelGui.PUNISH_UNMUTE, AdminPanelGui.PUNISH_KICK,
            AdminPanelGui.PUNISH_IP_BAN, AdminPanelGui.PUNISH_IP_UNBAN, AdminPanelGui.PUNISH_CHAT
    };

    @Test
    void entriesFitSixRowsWithoutCollidingWithButtons() {
        assertEquals(54, AdminPanelGui.SIZE);
        assertEquals(28, AdminPanelGui.ENTRY_SLOTS.length);
        Set<Integer> entries = new HashSet<>();
        for (int slot : AdminPanelGui.ENTRY_SLOTS) {
            assertTrue(slot >= 9 && slot < 45, "запись вне средних рядов: " + slot);
            assertTrue(entries.add(slot), "дублирующийся слот: " + slot);
        }
        for (int slot : GLOBAL_BUTTONS) {
            assertTrue(slot >= 0 && slot < AdminPanelGui.SIZE, "кнопка вне инвентаря: " + slot);
            assertTrue(slot < 9 || slot >= 45, "глобальная кнопка в области записей: " + slot);
            assertFalse(entries.contains(slot), "глобальная кнопка перекрывает запись: " + slot);
        }
        for (int slot : PUNISH_BUTTONS) {
            assertTrue(entries.contains(slot), "кнопка наказаний вне сетки записей: " + slot);
        }
    }

    @Test
    void everyPageHasADistinctTitleAndNavigation() {
        assertEquals(7, AdminPanelGui.Page.values().length);
        for (String name : new String[]{"PLAYERS", "PROFILE", "MODERATORS", "JOURNAL",
                "MUTES", "PUNISH", "CHAT_HISTORY"}) {
            assertNotNull(AdminPanelGui.Page.valueOf(name));
        }
        assertEquals(AdminPanelGui.Page.PLAYERS, AdminPanelGui.Page.valueOf("PLAYERS"));
        // Слот 49 переиспользуется: «Наказания» в профиле и «Добавить» у модераторов.
        assertEquals(AdminPanelGui.SLOT_PUNISH, AdminPanelGui.SLOT_ADD_MODERATOR);
    }
}
