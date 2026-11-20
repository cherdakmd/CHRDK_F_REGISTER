package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static ru.cherdakmd.chrdkregister.FriendRequests.Result.*;

class FriendRequestsTest {
    @TempDir Path folder;
    final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
    FriendStore store() { return new FriendStore(folder, Logger.getAnonymousLogger()); }

    @Test void consentCreatesSymmetricDurableRelationshipAndRemoveWorksOffline() {
        FriendStore store = store();
        FriendRequests requests = new FriendRequests(store);
        assertEquals(SELF, requests.invite(a, a, 1));
        assertEquals(OK, requests.invite(a, b, 1));
        assertFalse(store.areFriends(a, b));
        assertEquals(REVERSE_PENDING, requests.invite(b, a, 2));
        assertEquals(NO_REQUEST, requests.accept(a, b, 3));
        assertEquals(OK, requests.accept(b, a, 4));
        assertTrue(store.areFriends(a, b));
        assertTrue(store.areFriends(b, a));
        assertEquals(FRIENDS, requests.invite(b, a, 5));
        FriendStore restored = store();
        assertTrue(restored.areFriends(a, b));
        assertEquals(FriendStore.Result.OK, restored.remove(b, a));
        assertFalse(restored.areFriends(a, b));
        assertFalse(store().areFriends(b, a));
    }

    @Test void declineExpirationQuitRateLimitAndBoundedRequests() {
        FriendRequests requests = new FriendRequests(store());
        assertEquals(OK, requests.invite(a, b, 1_000));
        assertEquals(PENDING, requests.invite(a, b, 1_001));
        assertEquals(COOLDOWN, requests.invite(a, c, 1_001));
        assertEquals(OK, requests.decline(b, a, 1_002));
        assertEquals(NO_REQUEST, requests.accept(b, a, 1_003));
        assertEquals(OK, requests.invite(a, b, 11_000));
        assertEquals(NO_REQUEST, requests.accept(b, a, 71_000));
        assertEquals(OK, requests.invite(a, b, 72_000));
        requests.quit(b);
        assertEquals(NO_REQUEST, requests.accept(b, a, 72_001));
        for (int i = 0; i < FriendRequests.MAX_PENDING; i++) {
            assertEquals(OK, requests.invite(a, UUID.randomUUID(), 90_000 + i * 10_000L));
        }
        assertEquals(LIMIT, requests.invite(a, UUID.randomUUID(), 140_000));
    }

    @Test void friendLimitIsEnforcedWithoutPartialUpdates() {
        FriendStore store = store();
        for (int i = 0; i < FriendStore.MAX_FRIENDS; i++) {
            assertEquals(FriendStore.Result.OK, store.add(a, UUID.randomUUID()));
        }
        assertEquals(FriendStore.Result.LIMIT, store.add(a, b));
        assertFalse(store.areFriends(a, b));
        assertFalse(store.areFriends(b, a));
        assertEquals(FriendStore.MAX_FRIENDS, store().friendsOf(a).size());
    }

    @Test void damagedStorageFailsClosedAndNeverOverwrites() throws Exception {
        Path file = folder.resolve("players/friends.json");
        Files.createDirectories(file.getParent());
        for (String bad : new String[]{"broken", "{\"version\":2,\"pairs\":[]}",
                "{\"version\":1,\"pairs\":[[\"" + a + "\",\"" + a + "\"]]}",
                "{\"version\":1,\"pairs\":[[\"" + a + "\",\"" + b + "\"],[\"" + b + "\",\"" + a + "\"]]}"}) {
            Files.writeString(file, bad);
            FriendStore broken = store();
            assertFalse(broken.available());
            assertEquals(UNAVAILABLE, new FriendRequests(broken).invite(a, b, 100));
            assertEquals(FriendStore.Result.UNAVAILABLE, broken.add(a, b));
            assertEquals(bad, Files.readString(file));
        }
    }

    @Test void failedSaveNeverCreatesAsymmetricOrInMemoryFriendship() throws Exception {
        Files.writeString(folder.resolve("players"), "not a directory");
        FriendStore store = store();
        assertEquals(FriendStore.Result.UNAVAILABLE, store.add(a, b));
        assertFalse(store.areFriends(a, b));
        assertFalse(store.areFriends(b, a));
    }
}
