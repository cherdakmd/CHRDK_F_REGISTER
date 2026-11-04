package ru.cherdakmd.chrdkregister.api;

/**
 * Мост «приваты → дома» (1.4.282). Реализацию регистрирует <b>CHRDK_Claims</b>
 * через {@code ServicesManager}; <b>CHRDK_Teleports</b> спрашивает её перед
 * установкой точки дома, перед телепортом к ней и перед запоминанием кровати.
 *
 * <p>Если аддон приватов не установлен, сервиса нет: ограничение не действует,
 * ровно как у {@link SpawnClaimIndex} для случайного спавна. «Своя территория» —
 * владелец привата либо действующий арендатор. Участники, клан и гости с правом
 * BUILD считаются посторонними: точку дома на чужом привате поставить нельзя.</p>
 *
 * <pre>{@code
 * Bukkit.getServicesManager().register(HomeClaimPolicy.class, new ClaimsHomePolicy(plugin::store),
 *         this, ServicePriority.Normal);
 * }</pre>
 */
public interface HomeClaimPolicy {

    /**
     * Можно ли игроку создать или использовать точку дома в этом блоке.
     *
     * @param playerName ник игрока
     * @param worldName  мир точки
     * @param blockX     координата <b>блока</b> X, не чанка
     * @param blockZ     координата <b>блока</b> Z, не чанка
     */
    boolean canUseHome(String playerName, String worldName, int blockX, int blockZ);
}
