package ru.cherdakmd.chrdkachievements;

import org.bukkit.entity.Player;

/**
 * Публичный API достижений для других аддонов (регистрируется в ServicesManager).
 *
 * <p>Нужен, чтобы механики вне ванильных событий — например, выдача артефакта
 * или победа в событии — могли двигать счётчик, не завися от внутренних классов.
 * Метрики адресуются строкой из {@link AchievementMetric#id()}, неизвестная
 * метрика возвращает 0 и ничего не ломает.</p>
 */
public interface ChrdkAchievementsApi {

    /**
     * Прибавляет прогресс и проверяет достижения.
     *
     * @return сколько достижений открылось
     */
    int addProgress(Player player, String metricId, long amount);

    /** Сколько достижений открыто у игрока. */
    int unlockedCount(Player player);

    boolean isUnlocked(Player player, String achievementId);

    /** Админская выдача достижения; {@code true} — открылось впервые. */
    boolean grant(Player player, String achievementId);
}
