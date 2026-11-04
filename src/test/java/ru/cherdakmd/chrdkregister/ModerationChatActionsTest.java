package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Мут, кик и IP-бан попадают в тот же фильтруемый журнал админ-панели. */
class ModerationChatActionsTest {

    @Test
    void chatActionsAreRecordedWithReasonAndFilterableByPlayerOrAction() {
        ModerationLedger ledger = new ModerationLedger();
        ledger.record(ModerationLedger.ACTION_MUTE, "Senior", "Steve", "спам (10 мин.)", 1000L);
        ledger.record(ModerationLedger.ACTION_UNMUTE, "Senior", "Steve", "", 1001L);
        ledger.record(ModerationLedger.ACTION_KICK, "Senior", "Alex", "проверка", 1002L);
        ledger.record(ModerationLedger.ACTION_IP_BAN, "Senior", "Steve", "1.2.3.4 · обход", 1003L);
        ledger.record(ModerationLedger.ACTION_IP_UNBAN, "Senior", "1.2.3.4", "1.2.3.4", 1004L);

        List<ModerationLedger.JournalEntry> all = ledger.journal("", 100);
        assertEquals(5, all.size());
        assertTrue(all.get(0).id > all.get(4).id);
        assertEquals(3, ledger.journal("steve", 100).size(), "мут, размут и IP-бан одного игрока");
        assertEquals(1, ledger.journal("kick", 100).size());
        assertEquals(2, ledger.journal("un", 100).size(), "unmute и ip_unban по подстроке действия");
        assertTrue(ledger.journal("mute", 100).stream()
                .anyMatch(entry -> entry.action.equals(ModerationLedger.ACTION_MUTE)));
    }

    @Test
    void actionNamesAreStableForTheAuditContract() {
        assertEquals("mute", ModerationLedger.ACTION_MUTE);
        assertEquals("unmute", ModerationLedger.ACTION_UNMUTE);
        assertEquals("kick", ModerationLedger.ACTION_KICK);
        assertEquals("ip_ban", ModerationLedger.ACTION_IP_BAN);
        assertEquals("ip_unban", ModerationLedger.ACTION_IP_UNBAN);
        assertEquals("moderation.mute", AuditLog.MODERATION_MUTE);
        assertEquals("moderation.ip_ban", AuditLog.MODERATION_IP_BAN);
    }
}
