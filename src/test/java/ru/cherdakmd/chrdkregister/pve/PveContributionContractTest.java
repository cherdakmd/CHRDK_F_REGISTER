package ru.cherdakmd.chrdkregister.pve;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code SKILL_XP} подключается к общему реестру, но не вводит отдельную
 * проверку: здесь проверяется именно делегирование и уникальность квитанции.
 */
class PveContributionContractTest {

    private static final UUID PLAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEATH = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String DAY = "2026-11-05";

    @BeforeEach
    void setUp() {
        PveAwardLedger.shared().clear();
    }

    private static PveDeathEvidence evidence(double contribution, boolean authenticated) {
        return new PveDeathEvidence(PLAYER, DEATH, 1_000L, DAY,
                authenticated, true, true, false, false, false, true,
                contribution, 20.0);
    }

    private static PveDeathEvidence accepted() {
        return evidence(20.0, true);
    }

    @Test
    void verifyIsTheSharedContractVerdict() {
        PveDeathEvidence good = accepted();
        assertEquals(PveAwardContract.verify(good), PveContributionContract.verify(good));
        assertTrue(PveContributionContract.verify(good).allowed());

        PveDeathEvidence weak = evidence(3.0, true);
        assertEquals(PveAwardContract.verify(weak), PveContributionContract.verify(weak));
        assertEquals(PveVerdict.Reason.INSUFFICIENT_CONTRIBUTION,
                PveContributionContract.verify(weak).reason());
        assertEquals(4.0, PveContributionContract.verify(weak).required());

        PveDeathEvidence guest = evidence(20.0, false);
        assertEquals(PveVerdict.Reason.NOT_AUTHENTICATED,
                PveContributionContract.verify(guest).reason());
        assertEquals(PveVerdict.Reason.PLUGIN_NOT_READY,
                PveContributionContract.verify(null).reason());
    }

    @Test
    void receiptUsesSharedFormatAndSkillXpRecipient() {
        PveReceipt receipt = PveContributionContract.receipt(accepted());
        assertEquals(PveRecipient.SKILL_XP, receipt.recipient());
        assertEquals(DEATH, receipt.deathId());
        assertEquals(DAY, receipt.dayKey());
        assertEquals(DEATH + "|SKILL_XP|" + DAY, receipt.key());
        assertNull(PveContributionContract.receipt(null));
    }

    @Test
    void onlyConfirmedContributionRegistersReceipt() {
        assertFalse(PveContributionContract.register(null, PveVerdict.yes()));
        assertFalse(PveContributionContract.register(accepted(), null));
        assertFalse(PveContributionContract.register(accepted(),
                PveVerdict.no(PveVerdict.Reason.NOT_AUTHENTICATED)));
        assertFalse(PveContributionContract.register(evidence(3.0, true),
                PveAwardContract.verify(evidence(3.0, true))));
        assertTrue(PveContributionContract.register(accepted(),
                PveContributionContract.verify(accepted())));
    }

    @Test
    void sameDeathIsNotRegisteredTwice() {
        assertTrue(PveContributionContract.register(accepted(),
                PveContributionContract.verify(accepted())));
        assertFalse(PveContributionContract.register(accepted(),
                PveContributionContract.verify(accepted())),
                "один моб не даёт две квитанции SKILL_XP");
    }

    @Test
    void otherRecipientsShareTheSameDailyBudget() {
        // CLASS_XP и SKILL_XP идут в один реестр, а не в два независимых лимита.
        for (int i = 0; i < PveAwardContract.DAILY_AWARD_CAP; i++) {
            PveDeathEvidence each = new PveDeathEvidence(PLAYER, UUID.randomUUID(),
                    1_000L, DAY, true, true, true, false, false, false, true,
                    20.0, 20.0);
            PveAwardLedger.shared().register(PLAYER,
                    PveAwardContract.receipt(each.deathId(), PveRecipient.CLASS_XP, DAY));
        }
        assertFalse(PveContributionContract.register(accepted(),
                PveContributionContract.verify(accepted())),
                "общий дневной бюджет исчерпан");
    }
}
