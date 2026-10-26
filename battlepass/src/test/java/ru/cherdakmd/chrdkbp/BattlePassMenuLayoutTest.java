package ru.cherdakmd.chrdkbp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Страницы меню и действия по слотам — без Bukkit. */
class BattlePassMenuLayoutTest {

    @Test
    void pageCountNeverZero() {
        assertEquals(1, BattlePassMenuLayout.pageCount(0));
        assertEquals(1, BattlePassMenuLayout.pageCount(1));
        assertEquals(1, BattlePassMenuLayout.pageCount(45));
        assertEquals(2, BattlePassMenuLayout.pageCount(46));
        assertEquals(3, BattlePassMenuLayout.pageCount(91));
    }

    @Test
    void clampPageStaysInRange() {
        assertEquals(0, BattlePassMenuLayout.clampPage(-5, 46));
        assertEquals(1, BattlePassMenuLayout.clampPage(99, 46));
        assertEquals(0, BattlePassMenuLayout.clampPage(3, 0));
        assertEquals(1, BattlePassMenuLayout.clampPage(1, 91));
        assertEquals(2, BattlePassMenuLayout.clampPage(9, 91), "не дальше последней страницы");
    }

    @Test
    void missionSlotsFollowPage() {
        assertEquals(0, BattlePassMenuLayout.missionIndex(0, 0, 46));
        assertEquals(44, BattlePassMenuLayout.missionIndex(44, 0, 46));
        assertEquals(-1, BattlePassMenuLayout.missionIndex(45, 0, 46), "нижний ряд — кнопки");
        assertEquals(45, BattlePassMenuLayout.missionIndex(0, 1, 46));
        assertEquals(-1, BattlePassMenuLayout.missionIndex(1, 1, 46), "вторая страница кончилась");
        assertEquals(-1, BattlePassMenuLayout.missionIndex(10, 0, 5));
    }

    @Test
    void actionsMatchButtons() {
        assertEquals(BattlePassMenuLayout.Action.MISSION,
                BattlePassMenuLayout.actionFor(10, 0, 1, 20));
        assertEquals(BattlePassMenuLayout.Action.NONE,
                BattlePassMenuLayout.actionFor(10, 0, 1, 5), "пустой слот");
        assertEquals(BattlePassMenuLayout.Action.NONE,
                BattlePassMenuLayout.actionFor(BattlePassMenuLayout.SLOT_PREV, 0, 2, 46),
                "на первой странице «назад» не нужна");
        assertEquals(BattlePassMenuLayout.Action.PREV,
                BattlePassMenuLayout.actionFor(BattlePassMenuLayout.SLOT_PREV, 1, 2, 46));
        assertEquals(BattlePassMenuLayout.Action.NEXT,
                BattlePassMenuLayout.actionFor(BattlePassMenuLayout.SLOT_NEXT, 0, 2, 46));
        assertEquals(BattlePassMenuLayout.Action.NONE,
                BattlePassMenuLayout.actionFor(BattlePassMenuLayout.SLOT_NEXT, 1, 2, 46),
                "на последней странице «вперёд» не нужна");
        assertEquals(BattlePassMenuLayout.Action.CLOSE,
                BattlePassMenuLayout.actionFor(BattlePassMenuLayout.SLOT_CLOSE, 0, 1, 0));
        assertEquals(BattlePassMenuLayout.Action.SUMMARY,
                BattlePassMenuLayout.actionFor(BattlePassMenuLayout.SLOT_SUMMARY, 0, 1, 0));
        assertEquals(BattlePassMenuLayout.Action.NONE,
                BattlePassMenuLayout.actionFor(51, 0, 1, 0), "пустая кнопка нижнего ряда");
    }
    @Test
    void toolbarActionsNeverOverlapMissionsOnAnyPage() {
        int[] slots = {46, 47, 48, 49, 50, 52};
        BattlePassMenuLayout.Action[] actions = {
                BattlePassMenuLayout.Action.CLAIM_ALL, BattlePassMenuLayout.Action.BACK,
                BattlePassMenuLayout.Action.SUMMARY, BattlePassMenuLayout.Action.CLOSE,
                BattlePassMenuLayout.Action.PREMIUM, BattlePassMenuLayout.Action.REFRESH};
        for (int count : new int[]{0, 11, 45, 46, 91}) {
            for (int page = 0; page < BattlePassMenuLayout.pageCount(count); page++) {
                for (int i = 0; i < slots.length; i++) {
                    assertEquals(-1, BattlePassMenuLayout.missionIndex(slots[i], page, count));
                    assertEquals(actions[i], BattlePassMenuLayout.actionFor(
                            slots[i], page, BattlePassMenuLayout.pageCount(count), count));
                }
                assertEquals(BattlePassMenuLayout.Action.NONE,
                        BattlePassMenuLayout.actionFor(-999, page, 3, count));
                assertEquals(BattlePassMenuLayout.Action.NONE,
                        BattlePassMenuLayout.actionFor(54, page, 3, count));
            }
        }
    }

}
