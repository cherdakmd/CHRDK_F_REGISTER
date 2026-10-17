package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Троттлер окна «1 вызов / 20 сек» (официальное правило DonatePay) — защита от HTTP 429.
 * Интервал в тестах уменьшен до миллисекунд, чтобы прогон оставался мгновенным;
 * отдельный тест сторожит, что боевое окно не опустили ниже двадцати секунд.
 */
class DonatePayClientTest {

    @Test
    void productionIntervalRespectsDonatePayRule() {
        assertTrue(DonatePayClient.API_MIN_INTERVAL_MILLIS >= 20_000L,
                "правило DonatePay: любая API-операция не чаще раза в 20 секунд");
    }

    @Test
    void firstCallTakesTheSlotImmediately() throws IOException {
        DonatePayClient.ApiThrottle throttle = new DonatePayClient.ApiThrottle(150L);
        long started = System.currentTimeMillis();
        throttle.await();
        assertTrue(System.currentTimeMillis() - started < 100L,
                "первый вызов не должен ждать");
        assertTrue(throttle.waitMillis() > 0L,
                "после вызова окно закрыто до конца интервала");
    }

    @Test
    void secondCallWaitsForTheWindow() throws IOException {
        DonatePayClient.ApiThrottle throttle = new DonatePayClient.ApiThrottle(150L);
        long started = System.currentTimeMillis();
        throttle.await();
        throttle.await();
        long elapsed = System.currentTimeMillis() - started;
        assertTrue(elapsed >= 140L,
                "второй вызов обязан дождаться своего слота, а прошло только " + elapsed + " мс");
        assertTrue(elapsed < 4_000L,
                "ожидание вышло из-под контроля: " + elapsed + " мс");
    }

    @Test
    void http429PushesNextSlotAway() throws IOException {
        DonatePayClient.ApiThrottle throttle = new DonatePayClient.ApiThrottle(150L);
        throttle.await();
        throttle.noteRateLimited();
        long wait = throttle.waitMillis();
        assertTrue(wait > 50L && wait <= 150L,
                "после 429 следующий слот сдвинут на полный интервал, а wait=" + wait + " мс");
    }
}
