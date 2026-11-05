package ru.cherdakmd.chrdkskills.tree;

import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.pve.PveVerdict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Пыль приходит только из подтверждённого PvE-события и остаётся QoL. */
class PerkTreePowderSourceTest {

    private static final long DAILY_CAP = 2_000L;

    private static PerkTreeState state(TreeDomain domain) {
        return PerkTreeState.empty(domain).withLevel(20);
    }

    private static PerkTreePowderSource.Result credit(PerkTreeState state, TreeDomain domain,
                                                      PveVerdict verdict, long creditedToday) {
        return PerkTreePowderSource.credit(state, domain, verdict,
                PerkTreePowderSource.BASE_POWDER, DAILY_CAP, creditedToday);
    }

    @Test
    void confirmedDeathCreditsPowderWithoutTouchingProgress() {
        PerkTreeState before = state(TreeDomain.COMBAT);
        PerkTreePowderSource.Result result = credit(before, TreeDomain.COMBAT, PveVerdict.yes(), 0L);
        assertTrue(result.applied());
        assertEquals(5L, result.credited());
        assertEquals(5L, result.state().powder());
        assertEquals(20, result.state().level(), "пыль не даёт уровни домена");
        assertEquals(0, result.state().tokensSpent(), "пыль не даёт токены");
        assertTrue(result.state().levels().isEmpty(), "пыль не открывает узлы");
    }

    @Test
    void rejectedVerdictCreditsNothing() {
        PerkTreeState before = state(TreeDomain.COMBAT);
        for (PveVerdict.Reason reason : PveVerdict.Reason.values()) {
            if (reason == PveVerdict.Reason.OK) {
                continue;
            }
            PveVerdict verdict = PveVerdict.no(reason);
            PerkTreePowderSource.Result result = credit(before, TreeDomain.COMBAT, verdict, 0L);
            assertFalse(result.applied(), reason.toString());
            assertEquals(0L, result.credited());
            assertEquals(before, result.state());
        }
        assertFalse(credit(before, TreeDomain.COMBAT, null, 0L).applied());
    }

    @Test
    void wrongDomainAndUnregisteredTreeAreRefused() {
        assertFalse(credit(state(TreeDomain.MINING), TreeDomain.COMBAT,
                PveVerdict.yes(), 0L).applied());
        assertFalse(credit(state(TreeDomain.FISHING), TreeDomain.FISHING,
                PveVerdict.yes(), 0L).applied());
        assertFalse(credit(null, TreeDomain.COMBAT, PveVerdict.yes(), 0L).applied());
    }

    @Test
    void dailyCapStopsCredits() {
        assertFalse(credit(state(TreeDomain.COMBAT), TreeDomain.COMBAT,
                PveVerdict.yes(), DAILY_CAP).applied());
        PerkTreePowderSource.Result partial = credit(state(TreeDomain.COMBAT), TreeDomain.COMBAT,
                PveVerdict.yes(), DAILY_CAP - 3L);
        assertTrue(partial.applied());
        assertEquals(3L, partial.credited());
    }

    @Test
    void powderStockCapStopsCredits() {
        PerkTreeDefinition definition = PerkTreeRegistry.of(TreeDomain.COMBAT).orElseThrow();
        PerkTreeState full = state(TreeDomain.COMBAT)
                .creditPowder(definition.powderCap(), definition.powderCap());
        assertEquals(definition.powderCap(), full.powder());
        assertFalse(credit(full, TreeDomain.COMBAT, PveVerdict.yes(), 0L).applied());
    }

    @Test
    void powderIsQolOnlyAndDoesNotCreateBalanceEffects() {
        PerkTreePowderSource.Result result = credit(state(TreeDomain.DEFENSE),
                TreeDomain.DEFENSE, PveVerdict.yes(), 0L);
        assertTrue(result.applied());
        assertEquals(0L, result.state().powderSpent());
        assertEquals(0, result.state().coreLevel());
        PerkTreeRegistry.validateAll();
        for (TreeDomain domain : PerkTreeRegistry.pveDomains()) {
            assertTrue(PerkTreeRegistry.of(domain).orElseThrow().nodes().stream()
                    .noneMatch(node -> node.effect().balanceSensitive()));
        }
    }
}
