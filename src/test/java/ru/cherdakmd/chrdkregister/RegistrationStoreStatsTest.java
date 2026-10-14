package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Контракт {@link RegistrationStore#statsSnapshot(long)}: агрегаты, которые уходят
 * в графики bStats (игроки с проходкой, аккаунты с 2FA), считаются корректно.
 */
class RegistrationStoreStatsTest {

    private static final Logger LOGGER = Logger.getLogger("RegistrationStoreStatsTest");

    @TempDir
    Path tempDir;

    @Test
    void emptyStoreGivesZeros() {
        RegistrationStore store = new RegistrationStore(tempDir, LOGGER);
        RegistrationStore.StatsSnapshot stats = store.statsSnapshot(System.currentTimeMillis());
        assertEquals(0, stats.accounts());
        assertEquals(0, stats.registered());
        assertEquals(0, stats.activePass());
        assertEquals(0, stats.totpEnabled());
    }

    @Test
    void countsRegisteredPassAndTotp() {
        RegistrationStore store = new RegistrationStore(tempDir, LOGGER);
        long now = System.currentTimeMillis();
        UUID alpha = UUID.randomUUID();
        UUID beta = UUID.randomUUID();

        store.recordPlayerSeen(alpha, "Alpha");
        store.recordPlayerSeen(beta, "Beta");
        store.recordPlayerSeen(UUID.randomUUID(), "Gamma");

        RegistrationStore.StatsSnapshot initial = store.statsSnapshot(now);
        assertEquals(3, initial.accounts(), "троих увидели");
        assertEquals(0, initial.registered());
        assertEquals(0, initial.activePass());
        assertEquals(0, initial.totpEnabled());

        assertTrue(store.registerPassword("Beta", beta, "secret123", "127.0.0.1", now));
        store.grantPassManual("Alpha", now, 24L * 3600_000L); // сутки проходки
        assertTrue(store.enableTotp("Beta", "JBSWY3DPEHPK3PXP", 123456L, now));

        RegistrationStore.StatsSnapshot stats = store.statsSnapshot(now);
        assertEquals(3, stats.accounts());
        assertEquals(1, stats.registered(), "зарегистрирован только Beta");
        assertEquals(1, stats.activePass(), "проходка только у Alpha");
        assertEquals(1, stats.totpEnabled(), "2FA только у Beta");
    }

    @Test
    void expiredPassIsNotCounted() {
        RegistrationStore store = new RegistrationStore(tempDir, LOGGER);
        long now = System.currentTimeMillis();
        UUID alpha = UUID.randomUUID();

        store.recordPlayerSeen(alpha, "Alpha");
        store.grantPassManual("Alpha", now - 2 * 24L * 3600_000L, 24L * 3600_000L); // истекла вчера

        RegistrationStore.StatsSnapshot stats = store.statsSnapshot(now);
        assertEquals(1, stats.accounts());
        assertEquals(0, stats.activePass(), "истекшая проходка не должна попадать в счётчик");
    }
}
