package ru.cherdakmd.chrdkregister.pve;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Единый валидатор и общий бюджет: причины отказов, квитанции, потолки. */
class PveAwardContractTest {

    private static final UUID PLAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEATH = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String DAY = "2026-11-05";

    private static PveDeathEvidence evidence(boolean auth, boolean mode, boolean natural,
                                             boolean temporary, boolean spawner,
                                             boolean pvp, boolean ready, double contribution) {
        return new PveDeathEvidence(PLAYER, DEATH, 1_000L, DAY,
                auth, mode, natural, temporary, spawner, pvp, ready, contribution, 20.0);
    }

    private static PveDeathEvidence valid() {
        return evidence(true, true, true, false, false, false, true, 10.0);
    }

    private static PveVerdict.Reason reject(PveDeathEvidence value) {
        PveVerdict verdict = PveAwardContract.verify(value);
        assertFalse(verdict.allowed());
        return verdict.reason();
    }

    @Test
    void validEvidenceIsAccepted() {
        PveVerdict verdict = PveAwardContract.verify(valid());
        assertTrue(verdict.allowed());
        assertEquals(PveVerdict.Reason.OK, verdict.reason());
        assertEquals(0.0, verdict.required());
    }

    @Test
    void everyRejectionHasItsOwnReason() {
        assertEquals(PveVerdict.Reason.NOT_AUTHENTICATED,
                reject(evidence(false, true, true, false, false, false, true, 10.0)));
        assertEquals(PveVerdict.Reason.WRONG_GAME_MODE,
                reject(evidence(true, false, true, false, false, false, true, 10.0)));
        assertEquals(PveVerdict.Reason.TEMPORARY_RAID_MOB,
                reject(evidence(true, true, true, true, false, false, true, 10.0)));
        assertEquals(PveVerdict.Reason.NOT_NATURAL,
                reject(evidence(true, true, false, false, false, false, true, 10.0)));
        assertEquals(PveVerdict.Reason.SPAWNER_OR_SUMMONED,
                reject(evidence(true, true, true, false, true, false, true, 10.0)));
        assertEquals(PveVerdict.Reason.PVP_RECENT,
                reject(evidence(true, true, true, false, false, true, true, 10.0)));
        assertEquals(PveVerdict.Reason.PLUGIN_NOT_READY,
                reject(evidence(true, true, true, false, false, false, false, 10.0)));
        assertEquals(PveVerdict.Reason.INSUFFICIENT_CONTRIBUTION,
                reject(evidence(true, true, true, false, false, false, true, 1.0)));
        assertEquals(PveVerdict.Reason.PLUGIN_NOT_READY,
                PveAwardContract.verify(null).reason());
    }

    @Test
    void contributionThresholdScalesWithHealthAndCapsAtTwenty() {
        assertEquals(4.0, PveAwardContract.requiredContribution(20.0), 1e-9);
        assertEquals(20.0, PveAwardContract.requiredContribution(200.0), 1e-9);
        assertEquals(0.0, PveAwardContract.requiredContribution(0.0), 1e-9);
        PveVerdict verdict = PveAwardContract.verify(new PveDeathEvidence(PLAYER, DEATH,
                1_000L, DAY, true, true, true, false, false, false, true, 3.0, 20.0));
        assertFalse(verdict.allowed());
        assertEquals(4.0, verdict.required(), 1e-9);
    }

    @Test
    void duplicateReceiptIsRejectedButOtherRecipientIsNot() {
        List<PveReceipt> today = List.of(PveAwardContract.receipt(DEATH, PveRecipient.CLASS_XP, DAY));
        assertTrue(PveAwardContract.canAward(today,
                PveAwardContract.receipt(DEATH, PveRecipient.PERK_POWDER, DAY), 100));
        assertFalse(PveAwardContract.canAward(today,
                PveAwardContract.receipt(DEATH, PveRecipient.CLASS_XP, DAY), 100));
        assertTrue(PveAwardContract.canAward(today,
                PveAwardContract.receipt(DEATH, PveRecipient.CLASS_XP, "2026-11-06"), 100));
        assertTrue(PveAwardContract.canAward(today,
                PveAwardContract.receipt(UUID.randomUUID(), PveRecipient.CLASS_XP, DAY), 100));
    }

    @Test
    void dailyBudgetIsSharedAcrossAllRecipients() {
        List<PveReceipt> today = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            today.add(PveAwardContract.receipt(UUID.randomUUID(), PveRecipient.COMMON_GOALS, DAY));
        }
        assertTrue(PveAwardContract.canAward(today,
                PveAwardContract.receipt(UUID.randomUUID(), PveRecipient.PERK_POWDER, DAY), 6));
        assertFalse(PveAwardContract.canAward(today,
                PveAwardContract.receipt(UUID.randomUUID(), PveRecipient.PERK_POWDER, DAY), 5));
        assertFalse(PveAwardContract.canAward(today,
                PveAwardContract.receipt(UUID.randomUUID(), PveRecipient.PERK_POWDER, DAY), 0));
    }

    @Test
    void remainingDailyPowderNeverGoesNegative() {
        assertEquals(1_500L, PveAwardContract.remainingDailyPowder(2_000L, 500L));
        assertEquals(0L, PveAwardContract.remainingDailyPowder(2_000L, 2_000L));
        assertEquals(0L, PveAwardContract.remainingDailyPowder(2_000L, 9_999L));
        assertEquals(0L, PveAwardContract.remainingDailyPowder(0L, 0L));
    }

    @Test
    void evidenceAndReceiptRejectBadIds() {
        assertThrows(IllegalArgumentException.class, () -> new PveDeathEvidence(null, DEATH,
                1_000L, DAY, true, true, true, false, false, false, true, 10.0, 20.0));
        assertThrows(IllegalArgumentException.class, () -> new PveDeathEvidence(PLAYER, DEATH,
                1_000L, " ", true, true, true, false, false, false, true, 10.0, 20.0));
        assertThrows(IllegalArgumentException.class, () -> new PveReceipt(DEATH, PveRecipient.CLASS_XP, DAY, 0));
    }

    @Test
    void recipientsCoverAllPveSystems() {
        assertEquals(5, PveRecipient.values().length);
        assertTrue(List.of(PveRecipient.values()).contains(PveRecipient.PERK_POWDER));
        assertEquals(2_000, PveAwardContract.DAILY_AWARD_CAP);
    }
}
