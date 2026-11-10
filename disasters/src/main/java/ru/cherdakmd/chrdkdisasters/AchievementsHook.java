package ru.cherdakmd.chrdkdisasters;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.cherdakmd.chrdkachievements.ChrdkAchievementsApi;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Единственный класс модуля, который ссылается на бинарный API
 * CHRDK_Achievements. Загружается JVM только после проверки, что софт-зависимость
 * включена, — иначе отсутствие JAR привело бы к ошибке верификации класса.
 *
 * <p>Неизвестная метрика просто возвращает 0 в самом API аддона, поэтому
 * добавлять метрики {@code disasters-*} можно без правки этого класса.</p>
 */
final class AchievementsHook {

    static final String PLUGIN = "CHRDK_Achievements";

    private AchievementsHook() {
    }

    static int progress(Logger logger, Player player, String metricId, long amount) {
        if (player == null || amount <= 0L
                || !Bukkit.getPluginManager().isPluginEnabled(PLUGIN)) {
            return 0;
        }
        try {
            var registration = Bukkit.getServicesManager()
                    .getRegistration(ChrdkAchievementsApi.class);
            if (registration == null) {
                return 0;
            }
            return registration.getProvider().addProgress(player, metricId, amount);
        } catch (LinkageError | RuntimeException error) {
            logger.log(Level.WARNING,
                    "[CHRDK_Disasters] CHRDK_Achievements несовместим: прогресс пропущен", error);
            return 0;
        }
    }
}
