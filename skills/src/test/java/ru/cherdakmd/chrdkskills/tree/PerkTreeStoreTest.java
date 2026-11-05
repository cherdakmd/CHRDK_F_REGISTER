package ru.cherdakmd.chrdkskills.tree;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Схема 1: круговая запись, строгая загрузка, fail-closed, смена дня. */
class PerkTreeStoreTest {

    private static final UUID PLAYER = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID DEATH = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final String DAY = "2026-11-05";
    private static final String NEXT = "2026-11-06";

    private static PerkTreeStore store(Path folder) {
        return new PerkTreeStore(folder, null);
    }

    private static PerkTreeState openedState() {
        PerkTreeDefinition definition = PerkTreeRegistry.of(TreeDomain.COMBAT).orElseThrow();
        return PerkTreeState.empty(TreeDomain.COMBAT).withLevel(50)
                .creditPowder(120L, definition.powderCap());
    }

    @Test
    void statesReceiptsAndCountersRoundTrip(@TempDir Path dir) {
        PerkTreeStore store = store(dir);
        store.beginDay(DAY);
        store.putState(PLAYER, openedState());
        store.addReceipt(PLAYER, DAY, DEATH);
        store.addCredited(PLAYER, DAY, 5L);
        store.saveNow();
        assertTrue(Files.exists(dir.resolve("perk-trees.json")));

        PerkTreeStore loaded = store(dir);
        loaded.load();
        assertTrue(loaded.available());
        assertEquals(1, loaded.stateCount());
        PerkTreeState state = loaded.state(PLAYER, TreeDomain.COMBAT);
        assertEquals(50, state.level());
        assertEquals(120L, state.powder());
        assertTrue(loaded.hasReceipt(PLAYER, DAY, DEATH));
        assertFalse(loaded.hasReceipt(PLAYER, DAY, UUID.randomUUID()));
        assertEquals(5L, loaded.creditedToday(PLAYER, DAY));
    }

    @Test
    void corruptedJsonBlocksStoreAndIsNotOverwritten(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("perk-trees.json");
        Files.writeString(file, "{broken json");
        PerkTreeStore store = store(dir);
        store.load();
        assertFalse(store.available());
        store.putState(PLAYER, openedState());
        store.saveNow();
        assertEquals("{broken json", Files.readString(file));
    }

    @Test
    void unknownNodeOrDomainBlocksStore(@TempDir Path dir) throws Exception {
        String template = "{\"version\":1,\"day\":\"%s\",\"states\":{\"%s|combat\":{"
                + "\"level\":50,\"coreLevel\":0,\"powder\":120,\"powderSpent\":0,"
                + "\"corePowderSpent\":0,\"tokensSpent\":0,\"levels\":{\"%s\":1}}},"
                + "\"receipts\":{},\"credited\":{}}";
        Path file = dir.resolve("perk-trees.json");
        Files.writeString(file, String.format(template, DAY, PLAYER, "ghost-node"));
        PerkTreeStore badNode = store(dir);
        badNode.load();
        assertFalse(badNode.available());

        Files.writeString(file, String.format(template, DAY, PLAYER, "combat-hud")
                .replace("|combat\"", "|fishing\""));
        PerkTreeStore badDomain = store(dir);
        badDomain.load();
        assertFalse(badDomain.available());
    }

    @Test
    void abilityThatIsNotUnlockedIsRejected(@TempDir Path dir) throws Exception {
        String json = "{\"version\":1,\"day\":\"" + DAY + "\",\"states\":{\""
                + PLAYER + "|combat\":{\"level\":50,\"coreLevel\":0,\"powder\":0,"
                + "\"powderSpent\":0,\"corePowderSpent\":0,\"tokensSpent\":0,"
                + "\"levels\":{},\"selectedAbility\":\"combat-focus\"}},"
                + "\"receipts\":{},\"credited\":{}}";
        Files.writeString(dir.resolve("perk-trees.json"), json);
        PerkTreeStore store = store(dir);
        store.load();
        assertFalse(store.available());
    }

    @Test
    void newDayDropsReceiptsAndCountersButKeepsTrees(@TempDir Path dir) {
        PerkTreeStore store = store(dir);
        store.beginDay(DAY);
        store.putState(PLAYER, openedState());
        store.addReceipt(PLAYER, DAY, DEATH);
        store.addCredited(PLAYER, DAY, 5L);
        store.beginDay(NEXT);
        assertFalse(store.hasReceipt(PLAYER, DAY, DEATH));
        assertEquals(0L, store.creditedToday(PLAYER, DAY));
        assertEquals(120L, store.state(PLAYER, TreeDomain.COMBAT).powder());
        assertNull(store.state(PLAYER, TreeDomain.DEFENSE));
    }

    @Test
    void forgetRemovesOnlyThatPlayer(@TempDir Path dir) {
        PerkTreeStore store = store(dir);
        UUID other = UUID.randomUUID();
        store.beginDay(DAY);
        store.putState(PLAYER, openedState());
        store.putState(other, PerkTreeState.empty(TreeDomain.COMBAT).withLevel(20));
        store.addReceipt(PLAYER, DAY, DEATH);
        store.forget(PLAYER);
        assertNull(store.state(PLAYER, TreeDomain.COMBAT));
        assertEquals(20, store.state(other, TreeDomain.COMBAT).level());
        assertFalse(store.hasReceipt(PLAYER, DAY, DEATH));
    }
}
