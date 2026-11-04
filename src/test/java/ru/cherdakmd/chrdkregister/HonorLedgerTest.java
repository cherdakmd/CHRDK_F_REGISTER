package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Реестр званий: выдача, замена, снятие, публичность и кругорот состояния. */
class HonorLedgerTest {

    @Test
    void firstRunHasOneTemplateAndNoPlayers() {
        HonorLedger ledger = HonorLedger.withDefaults();
        assertEquals(1, ledger.definitions().size());
        assertEquals("old-citrus", ledger.definitions().get(0).id());
        assertTrue(ledger.allGrants().isEmpty());
        assertTrue(ledger.publicGrants().isEmpty());
        assertEquals(0, ledger.grantLevel("Steve"));
    }

    @Test
    void grantingSetsEffectiveLevelAndReplacingKeepsTheGrant() {
        HonorLedger ledger = HonorLedger.withDefaults();
        assertTrue(ledger.define(new HonorDefinition("veteran", "Ветеран", "%name%",
                "✦", 2, "За выслугу", "", true)));
        assertFalse(ledger.define(new HonorDefinition("veteran", "Дубль", "%name%",
                "", 0, "", "", true)), "дубль id запрещён");

        assertNull(ledger.grant(new HonorGrant("Steve", "", "old-citrus", "Owner", 1_000L, "С 2019 года")));
        assertEquals(5, ledger.grantLevel("Steve"));
        assertEquals("old-citrus", ledger.active("Steve").id());
        assertEquals(1, ledger.publicGrants().size());

        HonorGrant previous = ledger.grant(new HonorGrant("Steve", "", "veteran", "Owner", 2_000L, "Перевод"));
        assertNotNull(previous);
        assertEquals("old-citrus", previous.honorId(), "прежняя выдача возвращается для аудита");
        assertEquals(2, ledger.grantLevel("Steve"));
        assertEquals(1, ledger.allGrants().size(), "одно активное звание на игрока");

        assertTrue(ledger.replace(ledger.definition("veteran").with("level", "4")));
        assertEquals(4, ledger.grantLevel("Steve"), "правка определения сразу меняет привилегии");
        assertEquals(1, ledger.allGrants().size(), "выдача не теряется");
    }

    @Test
    void hiddenDefinitionStaysOutOfPublicRegistryButKeepsPrivileges() {
        HonorLedger ledger = new HonorLedger();
        ledger.define(new HonorDefinition("secret", "Служебное", "%name%", "", 3, "", "", false));
        ledger.grant(new HonorGrant("Alex", "", "secret", "Owner", 1L, "внутреннее"));
        assertTrue(ledger.publicGrants().isEmpty());
        assertEquals(1, ledger.allGrants().size());
        assertEquals(3, ledger.grantLevel("Alex"));
    }

    @Test
    void removingDefinitionRemovesItsGrants() {
        HonorLedger ledger = HonorLedger.withDefaults();
        ledger.grant(new HonorGrant("Steve", "", "old-citrus", "Owner", 1L, "причина"));
        assertTrue(ledger.remove("old-citrus"));
        assertTrue(ledger.definitions().isEmpty());
        assertTrue(ledger.allGrants().isEmpty());
        assertEquals(0, ledger.grantLevel("Steve"));
        assertFalse(ledger.remove("old-citrus"));
    }

    @Test
    void snapshotSurvivesRoundTripAndIgnoresBrokenRecords() {
        HonorLedger ledger = HonorLedger.withDefaults();
        ledger.grant(new HonorGrant("Steve", "uuid-1", "old-citrus", "Owner", 1_234L, "За выслугу лет"));
        HonorLedger.State state = ledger.snapshot();
        assertEquals(HonorLedger.SCHEMA_VERSION, state.version);
        assertEquals(1, state.definitions.size());
        assertEquals(1, state.grants.size());

        state.grants.add(new HonorLedger.SavedGrant());
        state.grants.get(1).player = "  ";
        state.definitions.add(new HonorLedger.SavedDefinition());
        HonorLedger restored = new HonorLedger();
        restored.restore(state);
        assertEquals(1, restored.definitions().size(), "пустое определение отброшено");
        assertEquals(1, restored.allGrants().size(), "запись без ника отброшена");
        assertEquals("За выслугу лет", restored.grantOf("Steve").reason());
        assertEquals("uuid-1", restored.grantOf("Steve").uuid());
    }

    @Test
    void firstRunSeedsConfiguredStaveLimeAndSkipsUnknownHonors() {
        HonorConfig config = new HonorConfig(true, false, 160, HonorConfig.DEFAULT_REASON, true,
                java.util.List.of(
                        new HonorConfig.InitialGrant("StaveLime", "old-citrus", "За выслугу лет"),
                        new HonorConfig.InitialGrant("Alex", "missing", "неизвестное звание")));
        HonorLedger ledger = HonorLedger.withDefaults(config);
        assertEquals(1, ledger.allGrants().size(), "неизвестное звание не выдаётся");
        assertEquals(5, ledger.grantLevel("StaveLime"));
        assertEquals("old-citrus", ledger.active("StaveLime").id());
        assertEquals("настройки сервера", ledger.grantOf("StaveLime").grantedBy());
        assertEquals("За выслугу лет", ledger.grantOf("StaveLime").reason());
        assertEquals(1, ledger.publicGrants().size(), "выдача видна в публичном реестре");
        assertEquals(0, ledger.grantLevel("Alex"));
    }

    @Test
    void revokeIsCaseInsensitiveAndRespectsHonorId() {
        HonorLedger ledger = HonorLedger.withDefaults();
        ledger.grant(new HonorGrant("Steve", "", "old-citrus", "Owner", 1L, "причина"));
        assertNull(ledger.revoke("STEVE", "veteran"), "чужой id не снимает звание");
        assertNotNull(ledger.revoke("steve", ""));
        assertNull(ledger.active("Steve"));
    }
}
