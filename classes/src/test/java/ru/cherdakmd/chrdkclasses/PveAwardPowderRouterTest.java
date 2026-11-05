package ru.cherdakmd.chrdkclasses;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.pve.PveAwardContract;
import ru.cherdakmd.chrdkregister.pve.PveAwardLedger;
import ru.cherdakmd.chrdkregister.pve.PveDeathEvidence;
import ru.cherdakmd.chrdkregister.pve.PveVerdict;
import ru.cherdakmd.chrdkskills.tree.PerkTreePowderSource;
import ru.cherdakmd.chrdkskills.tree.PerkTreeState;
import ru.cherdakmd.chrdkskills.tree.PerkTreeStore;
import ru.cherdakmd.chrdkskills.tree.TreeDomain;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Фаза 2: роутер пишет в PerkTreeStore, дубликаты защищены после рестарта. */
class PveAwardPowderRouterTest {

    private static final UUID PLAYER = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String DAY = "2026-11-05";
    private static final String NEXT_DAY = "2026-11-06";

    @TempDir
    Path dir;

    private PveAwardPowderRouter router;
    private PerkTreeStore store;

    @BeforeEach
    void setUp() {
        PveAwardLedger.shared().clear();
        store = new PerkTreeStore(dir, null);
        store.load();
        router = new PveAwardPowderRouter(store);
    }

    private static PveDeathEvidence evidence(UUID death, String day) {
        return new PveDeathEvidence(PLAYER, death, 1_000L, day,
                true, true, true, false, false, false, true, 10.0, 20.0);
    }

    private static long accept(PveAwardPowderRouter target, PveDeathEvidence event,
                               PveVerdict verdict, TreeDomain domain) {
        return target.accept(event, verdict, domain, 25);
    }

    @Test
    void confirmedDeathCreditsOnce() {
        PveDeathEvidence event = evidence(UUID.randomUUID(), DAY);
        assertEquals(PerkTreePowderSource.BASE_POWDER,
                accept(router, event, PveAwardContract.verify(event), TreeDomain.COMBAT));
        PerkTreeState state = router.stateOf(PLAYER, TreeDomain.COMBAT);
        assertNotNull(state);
        assertEquals(5L, state.powder());
        assertEquals(50, state.level(), "уровень домена берётся из класса, не из пыли");
        assertEquals(1, router.receipts(PLAYER, DAY));
        assertEquals(5L, router.creditedToday(PLAYER, DAY));
    }

    @Test
    void sameDeathCannotBeCreditedTwice() {
        PveDeathEvidence event = evidence(UUID.randomUUID(), DAY);
        PveVerdict verdict = PveAwardContract.verify(event);
        assertEquals(5L, accept(router, event, verdict, TreeDomain.COMBAT));
        assertEquals(0L, accept(router, event, verdict, TreeDomain.COMBAT));
        assertEquals(1, router.receipts(PLAYER, DAY));
        assertEquals(5L, router.stateOf(PLAYER, TreeDomain.COMBAT).powder());
    }

    @Test
    void duplicateIsRejectedAfterReload() {
        UUID death = UUID.randomUUID();
        PveDeathEvidence event = evidence(death, DAY);
        assertEquals(5L, accept(router, event, PveAwardContract.verify(event), TreeDomain.COMBAT));
        router.flush();
        // Имитируем рестарт: in-memory реестр чист, на диске осталась квитанция.
        PveAwardLedger.shared().clear();

        PerkTreeStore reloaded = new PerkTreeStore(dir, null);
        reloaded.load();
        PveAwardPowderRouter afterRestart = new PveAwardPowderRouter(reloaded);
        assertEquals(5L, afterRestart.stateOf(PLAYER, TreeDomain.COMBAT).powder());
        assertEquals(0L, accept(afterRestart, event, PveAwardContract.verify(event), TreeDomain.COMBAT),
                "долговечная квитанция не даёт второй награды после рестарта");
        assertEquals(5L, afterRestart.stateOf(PLAYER, TreeDomain.COMBAT).powder());
    }

    @Test
    void rejectedVerdictAndExternalDomainCreditNothing() {
        PveDeathEvidence event = evidence(UUID.randomUUID(), DAY);
        assertEquals(0L, accept(router, event, PveVerdict.no(PveVerdict.Reason.PVP_RECENT), TreeDomain.COMBAT));
        assertEquals(0L, accept(router, event, PveAwardContract.verify(event), TreeDomain.HUNT));
        assertEquals(0, router.receipts(PLAYER, DAY));
        assertNull(router.stateOf(PLAYER, TreeDomain.COMBAT));
    }

    @Test
    void dailyPowderCapIsRespected() {
        for (int i = 0; i < 400; i++) {
            PveDeathEvidence event = evidence(UUID.randomUUID(), DAY);
            assertEquals(5L, accept(router, event, PveAwardContract.verify(event), TreeDomain.COMBAT));
        }
        assertEquals(PveAwardPowderRouter.DAILY_POWDER_CAP, router.creditedToday(PLAYER, DAY));
        PveDeathEvidence extra = evidence(UUID.randomUUID(), DAY);
        assertEquals(0L, accept(router, extra, PveAwardContract.verify(extra), TreeDomain.COMBAT));
        assertEquals(400, router.receipts(PLAYER, DAY));
    }

    @Test
    void dayRolloverResetsCountersButKeepsPowder() {
        PveDeathEvidence first = evidence(UUID.randomUUID(), DAY);
        accept(router, first, PveAwardContract.verify(first), TreeDomain.COMBAT);
        PveDeathEvidence second = evidence(UUID.randomUUID(), NEXT_DAY);
        assertEquals(5L, accept(router, second, PveAwardContract.verify(second), TreeDomain.COMBAT));
        assertEquals(10L, router.stateOf(PLAYER, TreeDomain.COMBAT).powder());
        assertEquals(0, router.receipts(PLAYER, DAY), "квитанции старого дня не копились");
        assertEquals(1, router.receipts(PLAYER, NEXT_DAY));
        assertEquals(5L, router.creditedToday(PLAYER, NEXT_DAY));
    }

    @Test
    void differentDomainsKeepSeparateStates() {
        PveDeathEvidence first = evidence(UUID.randomUUID(), DAY);
        PveDeathEvidence second = evidence(UUID.randomUUID(), DAY);
        accept(router, first, PveAwardContract.verify(first), TreeDomain.COMBAT);
        accept(router, second, PveAwardContract.verify(second), TreeDomain.DEFENSE);
        assertNotNull(router.stateOf(PLAYER, TreeDomain.COMBAT));
        assertNotNull(router.stateOf(PLAYER, TreeDomain.DEFENSE));
        assertEquals(5L, router.stateOf(PLAYER, TreeDomain.COMBAT).powder());
        assertEquals(5L, router.stateOf(PLAYER, TreeDomain.DEFENSE).powder());
    }

    @Test
    void forgetDropsAllPlayerData() {
        PveDeathEvidence event = evidence(UUID.randomUUID(), DAY);
        accept(router, event, PveAwardContract.verify(event), TreeDomain.COMBAT);
        router.forget(PLAYER);
        assertNull(router.stateOf(PLAYER, TreeDomain.COMBAT));
        assertEquals(0, router.receipts(PLAYER, DAY));
        assertEquals(0L, router.creditedToday(PLAYER, DAY));
    }
}
