package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Правило случайного спавна: только авторизованный игрок, только без своего места,
 * и только для включённого события (первый вход / смерть).
 */
class SpawnPolicyTest {

    private static SpawnSettings settings(boolean enabled, boolean firstJoin, boolean death,
                                          boolean respectClaims, boolean respectHomes) {
        return new SpawnSettings(enabled, firstJoin, death, "", true, 0, 0,
                1000, 100, 8, true, respectClaims, respectHomes, true);
    }

    private static SpawnSettings defaults() {
        return settings(true, true, true, true, true);
    }

    @Test
    void randomSpawnWhenNothingHoldsPlayer() {
        assertTrue(SpawnPolicy.shouldRandomSpawn(defaults(), SpawnTrigger.FIRST_JOIN,
                true, false, false, false));
        assertTrue(SpawnPolicy.shouldRandomSpawn(defaults(), SpawnTrigger.DEATH,
                true, false, false, false));
    }

    @Test
    void disabledByMasterSwitchOrTrigger() {
        assertFalse(SpawnPolicy.shouldRandomSpawn(settings(false, true, true, true, true),
                SpawnTrigger.DEATH, true, false, false, false));
        assertFalse(SpawnPolicy.shouldRandomSpawn(settings(true, false, true, true, true),
                SpawnTrigger.FIRST_JOIN, true, false, false, false));
        assertFalse(SpawnPolicy.shouldRandomSpawn(settings(true, true, false, true, true),
                SpawnTrigger.DEATH, true, false, false, false));
    }

    @Test
    void notAuthenticatedNeverRandomized() {
        assertFalse(SpawnPolicy.shouldRandomSpawn(defaults(), SpawnTrigger.FIRST_JOIN,
                false, false, false, false));
        assertFalse(SpawnPolicy.shouldRandomSpawn(defaults(), SpawnTrigger.DEATH,
                false, false, false, false));
    }

    @Test
    void bedOrSpawnPointAlwaysWins() {
        // Кровать/якорь/·/spawnpoint — личное место игрока, конфигом не отключается.
        assertFalse(SpawnPolicy.shouldRandomSpawn(defaults(), SpawnTrigger.DEATH,
                true, true, false, false));
        assertFalse(SpawnPolicy.shouldRandomSpawn(settings(true, true, true, false, false),
                SpawnTrigger.DEATH, true, true, false, false));
    }

    @Test
    void homeAndClaimRespectSwitches() {
        assertFalse(SpawnPolicy.shouldRandomSpawn(defaults(), SpawnTrigger.DEATH,
                true, false, true, false));
        assertFalse(SpawnPolicy.shouldRandomSpawn(defaults(), SpawnTrigger.DEATH,
                true, false, false, true));
        assertTrue(SpawnPolicy.shouldRandomSpawn(settings(true, true, true, false, false),
                SpawnTrigger.DEATH, true, false, true, true));
    }

    @Test
    void nullSettingsMeansNoSpawn() {
        assertFalse(SpawnPolicy.shouldRandomSpawn(null, SpawnTrigger.DEATH,
                true, false, false, false));
    }
}
