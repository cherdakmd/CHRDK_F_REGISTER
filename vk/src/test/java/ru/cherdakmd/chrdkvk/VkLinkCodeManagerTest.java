package ru.cherdakmd.chrdkvk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VkLinkCodeManagerTest {

    @Test
    void codeIsOneTimeCaseInsensitiveAndHonorsCooldownAndExpiry() {
        VkLinkCodeManager manager = new VkLinkCodeManager();
        long now = 1_700_000_000_000L;

        VkLinkCodeManager.IssueResult first = manager.issue("Steve", 6, 300, 15, now);
        assertEquals(VkLinkCodeManager.Status.ISSUED, first.status());
        assertEquals(6, first.code().length());

        VkLinkCodeManager.IssueResult throttled = manager.issue("steve", 6, 300, 15, now + 5_000L);
        assertEquals(VkLinkCodeManager.Status.COOLDOWN, throttled.status());
        assertTrue(throttled.remainingCooldownSeconds() >= 10L);

        // Повторная выдача после паузы отзывает старый код.
        VkLinkCodeManager.IssueResult second = manager.issue("Steve", 6, 300, 15, now + 20_000L);
        assertEquals(VkLinkCodeManager.Status.ISSUED, second.status());
        assertTrue(manager.consume(first.code(), now + 21_000L).isEmpty(), "прежний код отозван");

        assertEquals("Steve", manager.consume("  " + second.code().toLowerCase() + " ", now + 25_000L).orElseThrow());
        assertTrue(manager.consume(second.code(), now + 26_000L).isEmpty(), "код строго одноразовый");

        VkLinkCodeManager.IssueResult expiring = manager.issue("Alex", 6, 60, 0, now);
        assertTrue(manager.consume(expiring.code(), now + 61_000L).isEmpty(), "просроченный код не срабатывает");
    }
}
