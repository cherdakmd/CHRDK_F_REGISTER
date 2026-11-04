package ru.cherdakmd.chrdksocial;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SocialLinkCodeManagerTest {

    @Test
    void oneCodePerPlayerAndSharedAcrossMessengers() {
        SocialLinkCodeManager codes = new SocialLinkCodeManager();
        long now = 1_000_000L;
        var issued = codes.issue("StaveLime", 6, 300, 20, now);
        assertEquals(SocialLinkCodeManager.Status.ISSUED, issued.status());
        assertEquals(6, issued.code().length());
        assertEquals(1, codes.activeCount(now));

        // Один и тот же код можно проверить и из ВК, и из MAX — до успешной записи.
        var fromVk = codes.verify(issued.code(), "vk:123", 5, 60, now + 1);
        var fromMax = codes.verify(issued.code(), "max:456", 5, 60, now + 2);
        assertEquals(SocialLinkCodeManager.Verify.FOUND, fromVk.status());
        assertEquals(SocialLinkCodeManager.Verify.FOUND, fromMax.status());
        assertEquals("StaveLime", fromVk.playerName());
        assertEquals("StaveLime", fromMax.playerName());

        assertTrue(codes.consume(issued.code()));
        assertFalse(codes.consume(issued.code()), "код одноразовый");
        assertEquals(SocialLinkCodeManager.Verify.NOT_FOUND,
                codes.verify(issued.code(), "vk:123", 5, 60, now + 3).status());
    }

    @Test
    void cooldownKeepsExistingCodeVisible() {
        SocialLinkCodeManager codes = new SocialLinkCodeManager();
        long now = 2_000_000L;
        String first = codes.issue("Player", 6, 300, 20, now).code();
        var repeat = codes.issue("Player", 6, 300, 20, now + 5_000L);
        assertEquals(SocialLinkCodeManager.Status.COOLDOWN, repeat.status());
        assertEquals(first, repeat.code(), "игрок не теряет код при релоге");
        assertEquals(15, repeat.remainingCooldownSeconds());
        assertEquals(Optional.of(first), codes.peek("player", now + 5_000L));
    }

    @Test
    void expiredCodeIsReissued() {
        SocialLinkCodeManager codes = new SocialLinkCodeManager();
        long now = 3_000_000L;
        String first = codes.issue("Player", 6, 60, 0, now).code();
        String second = codes.issue("Player", 6, 60, 0, now + 61_000L).code();
        assertNotEquals(first, second);
        assertEquals(SocialLinkCodeManager.Verify.NOT_FOUND,
                codes.verify(first, "max:1", 5, 60, now + 61_000L).status());
        assertEquals(1, codes.activeCount(now + 61_000L));
    }

    @Test
    void failedAttemptsLockSenderButDoNotBurnCode() {
        SocialLinkCodeManager codes = new SocialLinkCodeManager();
        long now = 4_000_000L;
        String code = codes.issue("Player", 6, 300, 0, now).code();
        for (int i = 0; i < 4; i++) {
            assertEquals(SocialLinkCodeManager.Verify.NOT_FOUND,
                    codes.verify("ZZZZZZ", "max:777", 5, 60, now).status());
        }
        var locked = codes.verify("ZZZZZZ", "max:777", 5, 60, now);
        assertEquals(SocialLinkCodeManager.Verify.LOCKED, locked.status());
        assertEquals(60L, locked.lockRemainingSeconds());
        assertEquals(SocialLinkCodeManager.Verify.FOUND,
                codes.verify(code, "vk:1", 5, 60, now).status(), "другой отправитель не заблокирован");

        codes.clearFailures("max:777");
        assertEquals(SocialLinkCodeManager.Verify.NOT_FOUND,
                codes.verify("ZZZZZZ", "max:777", 5, 60, now).status());
    }

    @Test
    void normalizationRejectsLookalikesAndLongInput() {
        assertEquals("", SocialLinkCodeManager.normalize("O0I1"));
        assertEquals("", SocialLinkCodeManager.normalize("abc"));
        assertEquals("ABC234", SocialLinkCodeManager.normalize(" abc234 "));
        assertFalse(MaxBotRouter.looksLikeCode("ABC234 ещё текст"));
        assertEquals("ABC234", MaxBotRouter.codeFrom("/link ABC234"));
    }
}
