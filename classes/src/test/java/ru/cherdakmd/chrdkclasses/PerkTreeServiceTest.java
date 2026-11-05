package ru.cherdakmd.chrdkclasses;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkskills.tree.PerkTreeRegistry;
import ru.cherdakmd.chrdkskills.tree.PerkTreeState;
import ru.cherdakmd.chrdkskills.tree.PerkTreeStore;
import ru.cherdakmd.chrdkskills.tree.TreeDomain;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Единый сервис: покупка, выбор приёма, двухшаговый сброс и аудит. */
class PerkTreeServiceTest {

    private static final UUID PLAYER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final int CLASS_LEVEL = 25;

    @TempDir
    Path dir;

    private PerkTreeStore store;
    private PerkTreeAudit audit;
    private PerkTreeService service;

    @BeforeEach
    void setUp() {
        store = new PerkTreeStore(dir, null);
        store.load();
        audit = new PerkTreeAudit(dir, null);
        service = new PerkTreeService(store, audit);
    }

    private void fund(long powder) {
        long cap = PerkTreeRegistry.of(TreeDomain.COMBAT).orElseThrow().powderCap();
        store.putState(PLAYER, PerkTreeState.empty(TreeDomain.COMBAT)
                .withLevel(50).creditPowder(powder, cap));
    }

    @Test
    void unlockCreditsNodeAndWritesAudit() {
        fund(500L);
        PerkTreeService.Result result = service.unlock(PLAYER, TreeDomain.COMBAT,
                "combat-threat-hint", CLASS_LEVEL);
        assertTrue(result.ok(), result.reason());
        assertTrue(result.state().isUnlocked("combat-threat-hint"));
        assertEquals(1, audit.lines());
        assertTrue(Files.exists(dir.resolve("audit").resolve("perk-trees.jsonl")));
    }

    @Test
    void invalidUnlockDoesNotChangeStateOrWriteAudit() {
        fund(500L);
        PerkTreeService.Result result = service.unlock(PLAYER, TreeDomain.COMBAT,
                "ghost-node", CLASS_LEVEL);
        assertFalse(result.ok());
        assertFalse(result.state().isUnlocked("ghost-node"));
        assertEquals(0, audit.lines());
    }

    @Test
    void abilityCanBeSelectedOnlyAfterUnlock() {
        fund(1_000L);
        assertFalse(service.select(PLAYER, TreeDomain.COMBAT, "combat-focus", CLASS_LEVEL).ok());
        assertTrue(service.unlock(PLAYER, TreeDomain.COMBAT, "combat-focus", CLASS_LEVEL).ok());
        PerkTreeService.Result selected = service.select(PLAYER, TreeDomain.COMBAT,
                "combat-focus", CLASS_LEVEL);
        assertTrue(selected.ok());
        assertEquals("combat-focus", selected.state().selectedAbility());
        assertEquals(2, audit.lines());
    }

    @Test
    void resetRequiresFreshSingleUseConfirmation() throws Exception {
        fund(1_000L);
        service.unlock(PLAYER, TreeDomain.COMBAT, "combat-threat-hint", CLASS_LEVEL);
        PerkTreeService.Result request = service.requestReset(PLAYER, TreeDomain.COMBAT,
                CLASS_LEVEL, 1_000L);
        assertTrue(request.ok());
        String token = request.reason();
        assertNotNull(token);
        assertEquals(8, token.length());

        assertFalse(service.confirmReset(PLAYER, "wrong", 1_100L).ok());
        PerkTreeService.Result expired = service.confirmReset(PLAYER, token,
                1_000L + PerkTreeService.RESET_TTL_MILLIS + 1L);
        assertFalse(expired.ok());
        // Неверный токен и истёкший срок закрывают квоту: нужен новый запрос.
        PerkTreeService.Result again = service.requestReset(PLAYER, TreeDomain.COMBAT,
                CLASS_LEVEL, 2_000L);
        assertTrue(service.confirmReset(PLAYER, again.reason(), 2_100L).ok());
        assertFalse(service.confirmReset(PLAYER, again.reason(), 2_200L).ok(),
                "повторное подтверждение не создаёт новый сброс");
    }

    @Test
    void confirmResetRefundsAndAudits() {
        fund(1_000L);
        service.unlock(PLAYER, TreeDomain.COMBAT, "combat-threat-hint", CLASS_LEVEL);
        PerkTreeState before = store.state(PLAYER, TreeDomain.COMBAT);
        long refund = before.refundablePowder();
        int tokens = before.tokensSpent();
        PerkTreeService.Result request = service.requestReset(PLAYER, TreeDomain.COMBAT,
                CLASS_LEVEL, 1_000L);
        PerkTreeService.Result done = service.confirmReset(PLAYER, request.reason(), 1_100L);
        assertTrue(done.ok());
        assertEquals(50, done.state().level(), "уровень класса сохраняется");
        assertEquals(0, done.state().tokensSpent());
        assertTrue(done.state().levels().isEmpty());
        assertTrue(done.state().powder() >= before.powder() + refund);
        assertTrue(tokens > 0);
        assertEquals(2, audit.lines(), "unlock + reset");
    }

    @Test
    void resetOfAFreshTreeSucceedsWithoutSavedState() {
        // Раньше confirmReset читал store.state() и падал: requestReset состояние
        // не сохранял, поэтому сброс нетронутого дерева был невозможен.
        PerkTreeService.Result request = service.requestReset(PLAYER, TreeDomain.COMBAT,
                CLASS_LEVEL, 1_000L);
        assertTrue(request.ok());
        PerkTreeService.Result done = service.confirmReset(PLAYER, request.reason(), 1_100L);
        assertTrue(done.ok(), done.reason());
        assertEquals(50, done.state().level());
        assertEquals(1, audit.lines(), "сброс без возврата тоже пишется в аудит");
    }

    @Test
    void forgetClosesPendingReset() {
        PerkTreeService.Result request = service.requestReset(PLAYER, TreeDomain.COMBAT,
                CLASS_LEVEL, 1_000L);
        assertEquals(1, service.pendingCount());
        service.forget(PLAYER);
        assertEquals(0, service.pendingCount());
        assertFalse(service.confirmReset(PLAYER, request.reason(), 1_100L).ok());
    }

    @Test
    void expiredRequestsDoNotAccumulate() {
        String oldToken = service.requestReset(PLAYER, TreeDomain.COMBAT,
                CLASS_LEVEL, 0L).reason();
        long later = PerkTreeService.RESET_TTL_MILLIS + 1L;
        service.requestReset(PLAYER, TreeDomain.COMBAT, CLASS_LEVEL, later);
        assertEquals(1, service.pendingCount(), "просроченный запрос вычищен");
        assertFalse(service.confirmReset(PLAYER, oldToken, later).ok());
    }

    @Test
    void externalDomainWithoutSavedStateIsUnavailable() {
        assertNull(service.state(PLAYER, TreeDomain.MINING, CLASS_LEVEL),
                "MINING не получает уровень домена из класса");
        assertFalse(service.unlock(PLAYER, TreeDomain.MINING, "любой-узел", CLASS_LEVEL).ok());
        assertFalse(service.requestReset(PLAYER, TreeDomain.MINING, CLASS_LEVEL, 1L).ok());
        assertEquals(0, audit.lines());
    }

    @Test
    void brokenStoreBlocksAllOperations() throws Exception {
        Files.writeString(dir.resolve("perk-trees.json"), "{broken");
        PerkTreeStore broken = new PerkTreeStore(dir, null);
        broken.load();
        PerkTreeService blocked = new PerkTreeService(broken, audit);
        assertFalse(blocked.unlock(PLAYER, TreeDomain.COMBAT, "combat-threat-hint", CLASS_LEVEL).ok());
        assertFalse(blocked.requestReset(PLAYER, TreeDomain.COMBAT, CLASS_LEVEL, 1L).ok());
        assertEquals(0, audit.lines());
    }
}
