package ru.cherdakmd.chrdkregister;

/**
 * Правило «пора переносить игрока в случайную точку». Чистая логика без Bukkit:
 * сервис собирает факты (вошёл ли игрок, есть ли у него кровать/точка возрождения,
 * дом, свой приват) и спрашивает политику.
 *
 * <p>Рандом не применяется, если у игрока есть <b>личная точка возрождения</b>
 * (кровать, якорь возрождения, {@code /spawnpoint}) — это не отключается конфигом,
 * это смысл функции: случайный спавн только для тех, у кого своего места ещё нет.
 * Дом и приват отключаются ключами {@code spawn.respect-homes} и
 * {@code spawn.respect-claims}.</p>
 */
public final class SpawnPolicy {

    private SpawnPolicy() {
    }

    /**
     * @param trigger          первое событие или смерть
     * @param authenticated    игрок полностью авторизован (прошёл пароль и 2FA)
     * @param hasRespawnPoint  у игрока есть кровать / якорь / {@code /spawnpoint}
     * @param hasHome          у игрока есть хотя бы одна точка дома в CHRDK_Teleports
     * @param ownsClaim        у игрока есть хотя бы один приват в CHRDK_Claims
     */
    public static boolean shouldRandomSpawn(SpawnSettings settings, SpawnTrigger trigger,
                                            boolean authenticated, boolean hasRespawnPoint,
                                            boolean hasHome, boolean ownsClaim) {
        if (settings == null || !settings.enabled() || !authenticated) {
            return false;
        }
        if (trigger == SpawnTrigger.FIRST_JOIN && !settings.onFirstJoin()) {
            return false;
        }
        if (trigger == SpawnTrigger.DEATH && !settings.onDeath()) {
            return false;
        }
        if (hasRespawnPoint) {
            return false;
        }
        if (settings.respectHomes() && hasHome) {
            return false;
        }
        if (settings.respectClaims() && ownsClaim) {
            return false;
        }
        return true;
    }
}
