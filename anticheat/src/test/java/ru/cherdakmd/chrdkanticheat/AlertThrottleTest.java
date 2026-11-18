package ru.cherdakmd.chrdkanticheat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertThrottleTest {

    @Test
    void allowsOneAlertPerPlayerCheckWindow() {
        AlertThrottle throttle = new AlertThrottle(10_000L);
        assertTrue(throttle.shouldAlert("Nick", "Speed", 0));
        assertFalse(throttle.shouldAlert("Nick", "Speed", 9_999), "внутри окна алерт дублировать нельзя");
        assertTrue(throttle.shouldAlert("Nick", "Speed", 10_000), "окно закрылось — алерт снова можно");
    }

    @Test
    void windowIsPerPairAndCaseInsensitive() {
        AlertThrottle throttle = new AlertThrottle(10_000L);
        assertTrue(throttle.shouldAlert("Nick", "Speed", 0));
        assertTrue(throttle.shouldAlert("Nick", "Fly", 0), "другой чек — своя история");
        assertTrue(throttle.shouldAlert("Other", "Speed", 0), "другой игрок — своя история");
        assertFalse(throttle.shouldAlert("NICK", "SPEED", 5), "регистр ключа не важен");
    }

    @Test
    void rejectsNonPositiveWindow() {
        assertThrows(IllegalArgumentException.class, () -> new AlertThrottle(0L));
    }
}
