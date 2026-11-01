package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.UUID;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class VkSecondFactorTest {
    private VkSecondFactor broker(Path folder) { return new VkSecondFactor(folder, Logger.getAnonymousLogger()); }
    @Test void enableNeedsDeliveryAndConfirmationAndPersistsWithoutCodes(@TempDir Path folder) throws Exception {
        var factor = broker(folder); var session = UUID.randomUUID();
        var issued = factor.issue("Steve", session, 77, VkSecondFactor.Purpose.ENABLE, 1000);
        assertNotNull(issued); assertFalse(issued.toString().contains(issued.code));
        assertNull(factor.verify("Steve", session, VkSecondFactor.Purpose.ENABLE, issued.code, 1001));
        factor.delivered(issued.ticket, true);
        var ticket = factor.verify("Steve", session, VkSecondFactor.Purpose.ENABLE, issued.code, 1002);
        assertNotNull(ticket); assertTrue(factor.commit(ticket));
        assertTrue(broker(folder).required("STEVE")); assertEquals(77, broker(folder).recipient("steve"));
        String json = Files.readString(folder.resolve("vk-second-factor.json"));
        assertFalse(json.contains(issued.code)); assertFalse(json.contains("hash"));
        assertNull(factor.verify("Steve", session, VkSecondFactor.Purpose.ENABLE, issued.code, 1003));
    }
    @Test void tokensAreBoundToUserSessionRecipientPurposeAndExpiry(@TempDir Path folder) {
        var factor = broker(folder); var session = UUID.randomUUID();
        var issued = factor.issue("Steve", session, 77, VkSecondFactor.Purpose.LOGIN, 1000);
        factor.delivered(issued.ticket, true);
        assertNull(factor.verify("Alex", session, VkSecondFactor.Purpose.LOGIN, issued.code, 1001));
        assertNull(factor.verify("Steve", UUID.randomUUID(), VkSecondFactor.Purpose.LOGIN, issued.code, 1001));
        assertNull(factor.verify("Steve", session, VkSecondFactor.Purpose.DISABLE, issued.code, 1001));
        assertNull(factor.verify("Steve", session, VkSecondFactor.Purpose.LOGIN, issued.code, 121000));
        assertNull(factor.issue("Steve", session, 2_000_000_001L, VkSecondFactor.Purpose.ENABLE, 130000));
    }
    @Test void attemptsAndResendsAreBoundedAcrossReconnects(@TempDir Path folder) {
        var factor = broker(folder); var session = UUID.randomUUID();
        var issued = factor.issue("Steve", session, 77, VkSecondFactor.Purpose.LOGIN, 1000);
        factor.delivered(issued.ticket, true);
        for (int i=0; i<5; i++) assertNull(factor.verify("Steve", session, VkSecondFactor.Purpose.LOGIN, "not-a-code", 1001));
        assertNull(factor.verify("Steve", session, VkSecondFactor.Purpose.LOGIN, issued.code, 1001));
        factor.forget(session);
        assertNull(factor.issue("STEVE", UUID.randomUUID(), 77, VkSecondFactor.Purpose.LOGIN, 1100));
        var nextSession = UUID.randomUUID();
        var next = factor.issue("Steve", nextSession, 77, VkSecondFactor.Purpose.LOGIN, 31000);
        factor.delivered(issued.ticket, true);
        assertNull(factor.verify("Steve", nextSession, VkSecondFactor.Purpose.LOGIN, next.code, 31001));
        factor.delivered(next.ticket, true);
        assertNotNull(factor.verify("Steve", nextSession, VkSecondFactor.Purpose.LOGIN, next.code, 31001));
    }
    @Test void disableNeedsNewConfirmedChallengeAndDoesNotChangeOtherPlayers(@TempDir Path folder) {
        var factor = broker(folder); var session = UUID.randomUUID();
        var on = factor.issue("Steve", session, 77, VkSecondFactor.Purpose.ENABLE, 1000);
        factor.delivered(on.ticket, true);
        assertTrue(factor.commit(factor.verify("Steve", session, VkSecondFactor.Purpose.ENABLE, on.code, 1001)));
        var off = factor.issue("Steve", session, 77, VkSecondFactor.Purpose.DISABLE, 31000);
        assertTrue(factor.required("Steve"));
        factor.delivered(off.ticket, true);
        assertTrue(factor.commit(factor.verify("Steve", session, VkSecondFactor.Purpose.DISABLE, off.code, 31001)));
        assertFalse(broker(folder).required("Steve"));
    }
    @Test void corruptStorageBlocksEvenWithoutAddonAndCannotBeResetByChallenge(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("vk-second-factor.json"), "broken");
        var factor = broker(folder); assertFalse(factor.healthy()); assertTrue(factor.required("Anyone"));
        assertNull(factor.issue("Steve", UUID.randomUUID(), 77, VkSecondFactor.Purpose.DISABLE, 1000));
    }
    @Test void invalidPersistedRecipientCannotTurnIntoChatOrRoundedUser(@TempDir Path folder) throws Exception {
        for (String id : new String[]{"77.5", "2000000001", "-77", "\"77\""}) {
            Files.writeString(folder.resolve("vk-second-factor.json"), "{\"schema\":1,\"accounts\":{\"steve\":" + id + "}}");
            var factor = broker(folder); assertFalse(factor.healthy()); assertTrue(factor.required("Steve"));
        }
    }

    @Test void codeSettingsAreMaskedLikeLoginCommands() {
        assertFalse(LogMasker.mask("Steve issued server command: /vk 2fa confirm 123456").contains("123456"));
        assertFalse(LogMasker.mask("Steve issued server command: /вк 2fa confirm 123456").contains("123456"));
        assertFalse(LogMasker.mask("Steve issued server command: /vkontakte 2fa confirm 123456").contains("123456"));
        assertFalse(LogMasker.mask("Steve issued server command: /chrdk_core:2fa 123456").contains("123456"));
        assertFalse(LogMasker.mask("Steve issued server command: /chrdk_vk:vk 2fa confirm 123456").contains("123456"));
    }
}
