package ru.cherdakmd.chrdkfishing;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RodTokenTest {
    @Test void upgradeIdentityAndLevelAreAuthenticated() {
        byte[] key = new byte[32]; UUID id = UUID.randomUUID();
        var token = RodToken.issue(key, id, 3);
        assertTrue(RodToken.verify(key, token));
        assertFalse(RodToken.verify(key, new RodToken.Token(id, 10, token.mac())));
        assertFalse(RodToken.verify(key, new RodToken.Token(UUID.randomUUID(), 3, token.mac())));
        byte[] wrong = key.clone(); wrong[0] = 1;
        assertFalse(RodToken.verify(wrong, token));
        assertThrows(IllegalArgumentException.class, () -> RodToken.issue(key, id, 11));
    }
    @Test void rodAndFishSignaturesAreDomainSeparated() {
        byte[] key = new byte[32]; UUID id = UUID.randomUUID();
        var fish = FishToken.issue(key, id, new FishingModel.Catch("pike", FishingModel.Size.NORMAL, 40), 70);
        assertFalse(RodToken.verify(key, new RodToken.Token(id, 1, fish.mac())));
    }
}
