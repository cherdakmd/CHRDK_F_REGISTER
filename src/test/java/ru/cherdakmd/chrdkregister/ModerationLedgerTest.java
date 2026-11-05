package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Правила варнов, ролей, банов и журнала без Bukkit и без файловой системы. */
class ModerationLedgerTest {

    @Test
    void keysAreCaseInsensitiveAndNullSafe() {
        assertEquals("steve", ModerationLedger.key("  Steve "));
        assertEquals("", ModerationLedger.key(null));
        assertEquals("Steve", ModerationLedger.display(" Steve "));
        assertEquals("", ModerationLedger.display(null));
        assertNull(new ModerationLedger().issueWarn(" ", "Admin", "причина", 1L));
        assertNull(new ModerationLedger().issueWarn("Steve", "Admin", "  ", 1L));
    }

    @Test
    void fifthWarnReachesLimitAndRemovalKeepsHistory() {
        ModerationLedger ledger = new ModerationLedger();
        for (int i = 1; i <= 4; i++) {
            assertNotNull(ledger.issueWarn("Steve", "Mod_" + i, "причина " + i, 1000L + i));
            assertFalse(ledger.reachedLimit("Steve", 5), i + "-й варн не должен банить");
        }
        assertNotNull(ledger.issueWarn("Steve", "Mod_5", "пятая", 1005L));
        assertTrue(ledger.reachedLimit("STEVE", 5), "регистр ника не должен обнулять счётчик");
        assertEquals(5, ledger.activeCount("Steve"));

        long id = ledger.activeWarns("Steve").get(0).id;
        assertTrue(ledger.removeWarn(id, "Senior", 2000L));
        assertEquals(4, ledger.activeCount("Steve"));
        assertFalse(ledger.reachedLimit("Steve", 5), "снятый варн не считается");
        assertEquals(5, ledger.warnHistory("Steve").size(), "история не удаляется");
        ModerationLedger.Warn removed = ledger.warnHistory("Steve").get(4);
        assertEquals("senior", removed.removedBy);
        assertEquals(2000L, removed.removedAt);
        assertFalse(ledger.removeWarn(id, "Senior", 2001L), "повторное снятие невозможно");
    }

    @Test
    void publicSummaryCountsOnlyActiveWarnsAndKeepsStoredKeys() {
        ModerationLedger ledger = new ModerationLedger();
        ledger.issueWarn("Ivan", "Mod", "причина", 1L);
        ledger.issueWarn("IVAN", "Mod", "причина", 2L);
        ledger.issueWarn("Petr", "Mod", "причина", 3L);
        assertTrue(ledger.removeWarn(ledger.activeWarns("Petr").get(0).id, "Senior", 4L));
        List<PublicWarnSummary.Row> rows = ledger.activeWarnSummary();
        assertEquals(1, rows.size(), "снятый варн и повтор ника в другом регистре не дублируются");
        assertEquals(new PublicWarnSummary.Row("ivan", 2), rows.get(0),
                "журнал хранит ключ в нижнем регистре");
        assertEquals("⚠ Ivan 2/5", PublicWarnSummary.format(rows, 3, 5),
                "в TAB ник показывается с заглавной буквы");
    }

    @Test
    void journalRecordsWhoWhomWhenWhyAndIsFilterable() {
        ModerationLedger ledger = new ModerationLedger();
        ledger.issueWarn("Steve", "Mod", "грифинг", 1000L);
        ledger.removeWarn(1L, "Senior", 2000L);
        ledger.addModerator("Mod", "Owner", 3000L);
        ledger.ban("Steve", "5/5 варнов", "Owner", 4000L);

        List<ModerationLedger.JournalEntry> all = ledger.journal("", 100);
        assertEquals(4, all.size(), "варн, снятие, роль, бан — по одной записи на действие");
        assertTrue(all.get(0).id > all.get(all.size() - 1).id, "новые записи сверху");
        assertTrue(ledger.journal("mod", 100).stream()
                .anyMatch(entry -> entry.action.equals(ModerationLedger.ACTION_WARN)));
        assertTrue(ledger.journal("грифинг", 100).stream()
                .anyMatch(entry -> entry.action.equals(ModerationLedger.ACTION_WARN)));
        assertTrue(ledger.journal("unwarn", 100).size() >= 1);
        assertEquals(1, ledger.journal("", 1).size(), "лимит страницы работает");
        assertEquals(0, ledger.journal("несуществующий", 100).size());
    }

    @Test
    void rolesAndBansAreIdempotentAndPersistentInSnapshot() {
        ModerationLedger ledger = new ModerationLedger();
        ledger.issueWarn("Alex", "Owner", "тест", 5L);
        assertTrue(ledger.addModerator("Mod", "Owner", 10L));
        assertFalse(ledger.addModerator("mod", "Owner", 11L), "дубль по регистру");
        assertTrue(ledger.isModerator("MOD"));
        assertEquals(1, ledger.moderators().size());

        assertNotNull(ledger.ban("Steve", "причина", "Owner", 20L));
        assertTrue(ledger.isBanned("steve"));
        assertEquals("причина", ledger.banOf("Steve").reason);
        assertFalse(ledger.unban("Alex", "Owner", 21L));
        assertTrue(ledger.unban("Steve", "Owner", 22L));
        assertFalse(ledger.isBanned("Steve"));

        ModerationLedger copy = new ModerationLedger();
        copy.restore(ledger.snapshot());
        assertTrue(copy.isModerator("mod"));
        assertEquals(4, copy.journal("", 500).size(), "варн, роль, бан и разбан");
        assertEquals(1, copy.activeCount("Alex"));
        assertEquals(2L, copy.issueWarn("Alex", "Mod", "после загрузки", 30L).id,
                "курсоры id продолжаются после загрузки");
    }

    @Test
    void journalIsBoundedAndCorruptedStateCannotBreakThePanel() {
        ModerationLedger ledger = new ModerationLedger();
        for (int i = 0; i < ModerationLedger.MAX_JOURNAL + 25; i++) {
            ledger.record("test." + i, "actor", "target", "detail", i);
        }
        assertEquals(ModerationLedger.MAX_JOURNAL, ledger.journalCount());

        ModerationLedger.State broken = new ModerationLedger.State();
        broken.warns = null;
        broken.moderators = java.util.Collections.singletonList(null);
        broken.bans = null;
        broken.journal = null;
        broken.nextWarnId = -7L;
        ModerationLedger restored = new ModerationLedger();
        restored.restore(broken);
        assertEquals(0, restored.warnTotal());
        assertEquals(1L, restored.issueWarn("Alex", "Mod", "после мусора", 1L).id);
        restored.restore(null);
        assertEquals(0, restored.warnTotal());
    }
}
