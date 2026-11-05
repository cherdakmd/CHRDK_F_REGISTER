package ru.cherdakmd.chrdkregister.pve;

import java.util.UUID;

/**
 * Нормализованные признаки смерти моба — вход общего валидатора.
 *
 * <p>Специально не содержит ничего из Bukkit: модули сами превращают свои
 * эвенты в этот снимок, а решение принимается один раз одинаково для всех.
 * Происхождение уже проверено политикой ядра {@code MobRewardPolicy} и
 * PDC-меткой естественного спавна в {@code classes}; здесь фиксируется
 * результат этих проверок, а не их повторение.</p>
 *
 * @param playerId            игрок, чей вклад подтверждён
 * @param deathId             стабильный ид смерти моба (UUID сущности)
 * @param atMillis            момент события
 * @param dayKey              календарный день UTC, например {@code 2026-11-05}
 * @param authenticated       прошёл авторизацию Core
 * @param survivalOrAdventure режим выживания или приключения
 * @param naturalProvenance   метка естественного / патрульного спавна
 * @param temporaryRaidMob    временный участник нашествия
 * @param spawnerOrSummoned   спавнер, призыв или иной искусственный источник
 * @param pvpRecent           игрок недавно участвовал в PvP
 * @param pluginReady         плагин готов и конфиг включён
 * @param contribution        подтверждённый урон игрока по мобу
 * @param maxHealth           максимальное здоровье моба для порога вклада
 */
public record PveDeathEvidence(UUID playerId, UUID deathId, long atMillis, String dayKey,
                              boolean authenticated, boolean survivalOrAdventure,
                              boolean naturalProvenance, boolean temporaryRaidMob,
                              boolean spawnerOrSummoned, boolean pvpRecent,
                              boolean pluginReady, double contribution, double maxHealth) {

    public PveDeathEvidence {
        if (playerId == null || deathId == null || dayKey == null || dayKey.isBlank()) {
            throw new IllegalArgumentException("ид игрока, ид смерти и день обязательны");
        }
        if (contribution < 0 || maxHealth < 0) {
            throw new IllegalArgumentException("урон и здоровье не могут быть отрицательными");
        }
    }
}
