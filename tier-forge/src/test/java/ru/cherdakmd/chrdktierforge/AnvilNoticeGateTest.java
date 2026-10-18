package ru.cherdakmd.chrdktierforge;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Анти-спам подсказок наковальни (1.4.80). */
class AnvilNoticeGateTest {

    @Test
    void sameSignatureIsShownOnlyOnce() {
        AnvilNoticeGate gate = new AnvilNoticeGate();
        UUID player = UUID.randomUUID();
        assertTrue(gate.shouldNotify(player, "mismatch:DIAMOND_SWORD:rare"));
        for (int i = 0; i < 20; i++) {
            assertFalse(gate.shouldNotify(player, "mismatch:DIAMOND_SWORD:rare"),
                    "повтор подсказки №" + i + " должен молчать");
        }
    }

    @Test
    void differentSignatureIsHeardAgain() {
        AnvilNoticeGate gate = new AnvilNoticeGate();
        UUID player = UUID.randomUUID();
        assertTrue(gate.shouldNotify(player, "mismatch:A"));
        assertTrue(gate.shouldNotify(player, "mismatch:B"));
        assertFalse(gate.shouldNotify(player, "mismatch:B"));
    }

    @Test
    void resetAndClearForgetHistory() {
        AnvilNoticeGate gate = new AnvilNoticeGate();
        UUID player = UUID.randomUUID();
        assertTrue(gate.shouldNotify(player, "max:legendary"));
        gate.reset(player);
        assertTrue(gate.shouldNotify(player, "max:legendary"), "после успеха — снова слышно");
        gate.clear(player);
        assertTrue(gate.shouldNotify(player, "max:legendary"), "после выхода — снова слышно");
    }

    @Test
    void playersDoNotShareHistory() {
        AnvilNoticeGate gate = new AnvilNoticeGate();
        assertTrue(gate.shouldNotify(UUID.randomUUID(), "anvil-unidentified"));
        assertTrue(gate.shouldNotify(UUID.randomUUID(), "anvil-unidentified"));
    }

    @Test
    void nullInputsNeverSuppress() {
        AnvilNoticeGate gate = new AnvilNoticeGate();
        assertTrue(gate.shouldNotify(null, "x"));
        assertTrue(gate.shouldNotify(UUID.randomUUID(), null));
    }
}
