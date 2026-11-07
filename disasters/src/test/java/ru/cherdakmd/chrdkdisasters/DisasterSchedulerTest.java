package ru.cherdakmd.chrdkdisasters;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Расписание проверяется без Bukkit: чистые строки и миллисекунды. */
final class DisasterSchedulerTest {

    private static DisasterScheduler fixed(long min, long max, int level, List<DisasterKind> kinds) {
        return new DisasterScheduler(min, max, level, kinds, 42L);
    }

    @Test
    void doesNotFireBeforeThePause() {
        DisasterScheduler scheduler = fixed(1000, 1000, 1, List.of(DisasterKind.DUST_STORM));
        assertTrue(scheduler.poll("Alex", 0).isEmpty());
        assertTrue(scheduler.poll("Alex", 999).isEmpty());
        Optional<DisasterScheduler.Scheduled> due = scheduler.poll("Alex", 1000);
        assertTrue(due.isPresent());
        assertEquals("alex", due.get().player());
        assertEquals(DisasterKind.DUST_STORM, due.get().kind());
        assertEquals(1, due.get().level());
    }

    @Test
    void reschedulesAfterEveryFiringAndForgottenPlayersStartFresh() {
        DisasterScheduler scheduler = fixed(500, 500, 2, List.of(DisasterKind.METEOR_SHOWER));
        // Первый тик только фиксирует паузу; катастрофа приходит через неё.
        assertEquals(500, scheduler.nextFor("Alex", 0));
        assertTrue(scheduler.poll("Alex", 500).isPresent());
        assertEquals(1000, scheduler.nextFor("alex", 500));
        assertTrue(scheduler.poll("Alex", 999).isEmpty(), "новая пауза после срабатывания");
        assertTrue(scheduler.poll("alex", 1000).isPresent(), "ник канонизируется");
        assertEquals(1, scheduler.size());
        scheduler.forget("ALEX");
        assertEquals(0, scheduler.size());
        assertEquals(2500, scheduler.nextFor("Alex", 2000), "после forget пауза начинается заново");
        assertTrue(scheduler.poll("Alex", 2000).isEmpty());
    }

    @Test
    void noEnabledKindsMeansCompleteSilence() {
        DisasterScheduler scheduler = fixed(1, 1, 5, List.of());
        assertTrue(scheduler.poll("Alex", 100_000).isEmpty());
        assertEquals(0, scheduler.size());
    }

    @Test
    void levelNeverExceedsConfiguredMaximum() {
        DisasterScheduler scheduler = fixed(0, 0, 3, List.of(DisasterKind.EARTHQUAKE));
        for (int i = 0; i < 50; i++) {
            var due = scheduler.poll("Alex", i * 10_000L);
            assertTrue(due.isPresent());
            assertTrue(due.get().level() >= 1 && due.get().level() <= 3);
        }
    }

    @Test
    void maxBelowMinIsNormalisedByConfigNotByScheduler() {
        // Движок доверяет конфигу: DisastersConfig поднимает max до min.
        org.bukkit.configuration.file.YamlConfiguration yaml =
                new org.bukkit.configuration.file.YamlConfiguration();
        yaml.set("disasters.min-seconds", 900);
        yaml.set("disasters.max-seconds", 60);
        DisastersConfig config = DisastersConfig.parse(yaml);
        assertEquals(900, config.minSeconds());
        assertEquals(900, config.maxSeconds());
        assertFalse(config.enabled());
    }
}
