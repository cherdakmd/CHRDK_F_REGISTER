package ru.cherdakmd.chrdkfishing;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static ru.cherdakmd.chrdkfishing.FishingModel.*;

class FishTokenTest {
    final byte[] key = new byte[32];
    final Catch fish = new Catch("pike", Size.NORMAL, 40);
    @Test void signedFieldsCannotBeChanged() {
        UUID id = UUID.randomUUID();
        var token = FishToken.issue(key, id, fish, 100);
        assertTrue(FishToken.verify(key, token));
        assertFalse(FishToken.verify(key, new FishToken.Token(id, fish, 101, token.mac())));
        assertFalse(FishToken.verify(key, new FishToken.Token(UUID.randomUUID(), fish, 100, token.mac())));
        assertFalse(FishToken.verify(key, new FishToken.Token(id, new Catch("pike", Size.NORMAL, 41), 100, token.mac())));
        var otherKey = key.clone(); otherKey[0] = 1;
        assertFalse(FishToken.verify(otherKey, token));
    }
    @Test void signedTokenDoesNotItselfPreventDuplication() {
        var token = FishToken.issue(key, UUID.randomUUID(), fish, 100);
        assertTrue(FishToken.verify(key, token)); assertTrue(FishToken.verify(key, token));
    }
    @Test void malformedDataAndKeysAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> FishToken.issue(new byte[8], UUID.randomUUID(), fish, 1));
        assertThrows(IllegalArgumentException.class, () -> FishToken.issue(key, UUID.randomUUID(), new Catch("pike", Size.SMALL, 100), 1));
        assertThrows(IllegalArgumentException.class, () -> FishToken.issue(key, UUID.randomUUID(), fish, 0));
        assertThrows(IllegalArgumentException.class, () -> new FishToken.Token(UUID.randomUUID(), fish, 1, "bad"));
    }
}
