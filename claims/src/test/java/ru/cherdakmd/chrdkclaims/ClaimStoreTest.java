package ru.cherdakmd.chrdkclaims;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Хранилище приватов без Bukkit: запись/чтение, резервная копия и карантин. */
class ClaimStoreTest {

    @TempDir
    Path dir;

    private ClaimStore store() {
        return new ClaimStore(dir, Logger.getAnonymousLogger());
    }

    @Test
    void roundTripKeepsClaimsTreasuryNoticesAndStats() {
        ClaimStore store = store();
        Claim claim = new Claim("world", 3, -7, "Steve", 1_700_000_000_000L);
        claim.members.add("Alex");
        claim.debtDays = 2;
        claim.salePrice = 32;
        store.add(claim);
        store.addTreasury("Steve", 5);
        store.addNotice("Steve", "тестовая весть");
        store.addDiamondsSpent(16);
        store.addTaxBurned(2);
        store.save();

        ClaimStore loaded = store();
        loaded.load();
        Claim back = loaded.claimAt("world", 3, -7);
        assertNotNull(back, "чанк должен читаться с теми же координатами");
        assertEquals("Steve", back.owner);
        assertEquals(1_700_000_000_000L, back.paidUntil);
        assertEquals(2, back.debtDays);
        assertEquals(32, back.salePrice);
        assertEquals(List.of("Alex"), back.members);
        assertEquals(5, loaded.treasuryOf("Steve"));
        assertEquals(List.of("тестовая весть"), loaded.pollNotices("Steve"));
        assertEquals(1, loaded.chunksClaimedTotal());
        assertEquals(16, loaded.diamondsSpent());
        assertEquals(2, loaded.taxBurned());
        assertNull(loaded.claimAt("world", 4, -7));
    }

    @Test
    void saveKeepsPreviousVersionAsBackup() {
        ClaimStore store = store();
        store.addTreasury("Steve", 5);
        store.save();
        store.addTreasury("Steve", 1);
        store.save();
        assertTrue(Files.isRegularFile(dir.resolve("claims-data.json.bak")),
                "предыдущая версия должна оставаться в .bak");
    }

    @Test
    void corruptFileIsQuarantinedNotOverwritten() throws IOException {
        Path file = dir.resolve("claims-data.json");
        Files.writeString(file, "{ это не json", StandardCharsets.UTF_8);
        ClaimStore store = store();
        store.load();
        assertTrue(store.all().isEmpty());
        assertFalse(Files.exists(file), "повреждённый файл откладывается в сторону");
        try (var files = Files.list(dir)) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString()
                            .startsWith("claims-data.json.corrupt-")),
                    "рядом должен появиться .corrupt-<millis>");
        }
    }

    @Test
    void missingPaidDateIsRepairedToLoadTime() throws IOException {
        long started = System.currentTimeMillis();
        Files.writeString(dir.resolve("claims-data.json"),
                "{\"claims\":[{\"w\":\"world\",\"x\":0,\"z\":0,\"o\":\"A\",\"p\":0}]}",
                StandardCharsets.UTF_8);
        ClaimStore store = store();
        store.load();
        Claim claim = store.claimAt("world", 0, 0);
        assertNotNull(claim);
        assertTrue(claim.paidUntil >= started, "дата оплаты не должна оставаться в 1970 году");
    }

    @Test
    void duplicateChunksAreIgnoredOnLoad() throws IOException {
        Files.writeString(dir.resolve("claims-data.json"), """
                {"claims":[
                  {"w":"world","x":1,"z":1,"o":"A","p":100},
                  {"w":"world","x":1,"z":1,"o":"B","p":200}
                ]}
                """, StandardCharsets.UTF_8);
        ClaimStore store = store();
        store.load();
        assertEquals(1, store.all().size());
        assertEquals("A", store.claimAt("world", 1, 1).owner, "остаётся первая запись");
    }

    @Test
    void noticesAreCapped() {
        ClaimStore store = store();
        for (int index = 0; index < ClaimStore.MAX_NOTICES + 5; index++) {
            store.addNotice("Steve", "весть " + index);
        }
        List<String> notices = store.pollNotices("Steve");
        assertEquals(ClaimStore.MAX_NOTICES, notices.size());
        assertEquals("весть 5", notices.get(0), "старые вести вытесняются");
        assertEquals("весть " + (ClaimStore.MAX_NOTICES + 4), notices.get(notices.size() - 1));
    }

    @Test
    void limitOverridesSurviveReloadAndCanBeCleared() {
        ClaimStore store = store();
        store.setLimitOverride("Steve", 40);
        store.save();

        ClaimStore loaded = store();
        loaded.load();
        assertEquals(Integer.valueOf(40), loaded.limitOverride("Steve"));
        assertEquals(1, loaded.limitOverrides().size());
        assertTrue(loaded.clearLimitOverride("Steve"));
        assertNull(loaded.limitOverride("Steve"));
        assertFalse(loaded.clearLimitOverride("Steve"), "повторное снятие ничего не делает");
    }

    @Test
    void transferAllMovesClaimsTreasuryNoticesAndLimit() {
        ClaimStore store = store();
        store.add(new Claim("world", 0, 0, "OldName", 1));
        store.add(new Claim("world_nether", 2, 2, "OldName", 1));
        store.add(new Claim("world", 5, 5, "Other", 1));
        store.addTreasury("OldName", 12);
        store.addTreasury("NewName", 3);
        store.addNotice("OldName", "весть");
        store.setLimitOverride("OldName", 40);

        ClaimStore.TransferSummary summary = store.transferAll("OldName", "NewName");
        assertEquals(2, summary.claims());
        assertEquals(12, summary.treasury());
        assertEquals(1, summary.notices());
        assertTrue(summary.limitMoved());
        assertEquals(15, store.treasuryOf("NewName"), "казна суммируется с остатком получателя");
        assertEquals(0, store.treasuryOf("OldName"));
        assertEquals(2, store.count("NewName"));
        assertEquals(1, store.count("Other"));
        assertEquals(List.of("весть"), store.pollNotices("NewName"));
        assertEquals(Integer.valueOf(40), store.limitOverride("NewName"));
        assertNull(store.limitOverride("OldName"));
    }

    @Test
    void transferAllIgnoresSameNickAndKeepsData() {
        ClaimStore store = store();
        store.add(new Claim("world", 0, 0, "A", 1));
        store.addTreasury("A", 7);

        ClaimStore.TransferSummary summary = store.transferAll("A", "a");
        assertEquals(0, summary.claims());
        assertEquals(0, summary.treasury());
        assertEquals(1, store.count("A"));
        assertEquals(7, store.treasuryOf("A"));
    }

    @Test
    void transferAllDropsTargetFromMembersOfMovedClaim() {
        ClaimStore store = store();
        Claim claim = new Claim("world", 1, 1, "Old", 1);
        claim.members.add("New");
        claim.members.add("Friend");
        store.add(claim);

        store.transferAll("Old", "New");
        Claim moved = store.claimAt("world", 1, 1);
        assertEquals("New", moved.owner);
        assertEquals(List.of("Friend"), moved.members, "новый владелец больше не участник");
    }

    @Test
    void playerKeysAreCaseInsensitive() {
        ClaimStore store = store();
        store.addTreasury("Steve", 12);
        assertEquals(12, store.treasuryOf("steve"));
        assertEquals(17, store.addTreasury("STEVE", 5), "второй кошелёк не создаётся");
        assertEquals(17, store.treasuryOf("Steve"));
        assertTrue(store.takeTreasury("sTeVe", 7));
        assertEquals(10, store.treasuryOf("Steve"));
        assertFalse(store.takeTreasury("Nobody", 1));

        store.setLimitOverride("Steve", 30);
        assertEquals(Integer.valueOf(30), store.limitOverride("steve"));
        store.setLimitOverride("steve", 35);
        assertEquals(1, store.limitOverrides().size(), "лимит не дублируется по регистру");
        assertEquals(Integer.valueOf(35), store.limitOverride("STEVE"));
        assertTrue(store.clearLimitOverride("sTeVe"));
        assertNull(store.limitOverride("Steve"));

        store.addNotice("Steve", "привет");
        assertEquals(List.of("привет"), store.pollNotices("sTeVe"));
    }

    @Test
    void removingWorldDropsOnlyItsClaims() {
        ClaimStore store = store();
        store.add(new Claim("world", 0, 0, "A", 1));
        store.add(new Claim("world_nether", 0, 0, "A", 1));
        assertEquals(1, store.removeWorld("world"));
        assertEquals(0, store.removeWorld("world"));
        assertEquals(1, store.all().size());
        assertNotNull(store.claimAt("world_nether", 0, 0));
        assertNull(store.claimAt("world", 0, 0));
    }
}
