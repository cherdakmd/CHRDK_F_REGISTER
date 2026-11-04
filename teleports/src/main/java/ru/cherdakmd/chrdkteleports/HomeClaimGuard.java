package ru.cherdakmd.chrdkteleports;

import org.bukkit.Bukkit;
import ru.cherdakmd.chrdkregister.api.HomeClaimPolicy;

/**
 * Граница «дом ↔ приват» (1.4.282). Мост публикует CHRDK_Claims через
 * {@code ServicesManager}; если аддона нет, сервис отсутствует и ограничение
 * не действует — дома продолжают работать как раньше.
 *
 * <p>Логика вынесена в чистые статические методы, чтобы её можно было
 * проверить юнит-тестами без запущенного Bukkit-сервера.</p>
 */
public final class HomeClaimGuard {

    private HomeClaimGuard() {
    }

    /** Действующая реализация моста; аддон приватов может включиться позже. */
    public static HomeClaimPolicy policy() {
        return Bukkit.getServicesManager().load(HomeClaimPolicy.class);
    }

    /** {@code false} — точка на чужом привате. Отсутствие моста = разрешить. */
    public static boolean allowed(HomeClaimPolicy policy, String playerName,
                                  String worldName, int blockX, int blockZ) {
        return policy == null || policy.canUseHome(playerName, worldName, blockX, blockZ);
    }

    /** Точка дома хранит центр блока: чанк считаем по полу, а не усечением к нулю. */
    public static int blockOf(double coordinate) {
        return (int) Math.floor(coordinate);
    }
}
