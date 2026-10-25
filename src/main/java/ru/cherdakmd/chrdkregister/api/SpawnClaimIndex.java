package ru.cherdakmd.chrdkregister.api;

/**
 * Мост «приваты → ядро» для случайного спавна (1.4.106). Реализацию регистрирует
 * <b>CHRDK_Claims</b> через {@code ServicesManager}; ядро спрашивает её при выборе
 * случайной точки и, если аддона нет, просто не учитывает приваты.
 *
 * <pre>{@code
 * Bukkit.getServicesManager().register(SpawnClaimIndex.class, new ClaimsSpawnIndex(this),
 *         this, ServicePriority.Normal);
 * }</pre>
 */
public interface SpawnClaimIndex {

    /** Занят ли чанк, в который попадает блок {@code (blockX, blockZ)} мира {@code worldName}. */
    boolean isClaimed(String worldName, int blockX, int blockZ);

    /** Есть ли у игрока хотя бы один приват (владелец, не участник). */
    boolean ownsAnyClaim(String playerName);
}
