package ru.cherdakmd.chrdkclaims;

import ru.cherdakmd.chrdkregister.api.SpawnClaimIndex;

/**
 * Мост «приваты → ядро» для случайного спавна (ядро 1.4.106+): ядро не ставит новичка
 * в чужой приват и не переносит игрока, у которого уже есть своя территория.
 * Регистрируется в {@code ServicesManager}; при отсутствии ядра просто не используется.
 */
public final class ClaimsSpawnIndex implements SpawnClaimIndex {

    private final ClaimsPlugin plugin;

    public ClaimsSpawnIndex(ClaimsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean isClaimed(String worldName, int blockX, int blockZ) {
        ClaimStore store = plugin.store();
        return worldName != null && store != null
                && store.claimAt(worldName, blockX >> 4, blockZ >> 4) != null;
    }

    @Override
    public boolean ownsAnyClaim(String playerName) {
        ClaimStore store = plugin.store();
        return playerName != null && store != null && store.count(playerName) > 0;
    }
}
