package ru.cherdakmd.chrdkclaims;

import ru.cherdakmd.chrdkregister.api.HomeClaimPolicy;

import java.util.function.Supplier;

/**
 * Ответ приватов на запрос точки дома (1.4.282). Владелец и действующий
 * арендатор — свои; участники, клан и гости с правом BUILD — нет.
 *
 * <p>Неизвестный мир, пустой файл приватов или ещё не загруженный store не
 * считаются запретом: отсутствие данных не должно ломать дома игроков.</p>
 */
public final class ClaimsHomePolicy implements HomeClaimPolicy {

    private final Supplier<ClaimStore> store;

    public ClaimsHomePolicy(Supplier<ClaimStore> store) {
        this.store = store;
    }

    @Override
    public boolean canUseHome(String playerName, String worldName, int blockX, int blockZ) {
        if (worldName == null || playerName == null) return true;
        ClaimStore source = store == null ? null : store.get();
        if (source == null) return true;
        Claim claim = source.claimAt(worldName, blockX >> 4, blockZ >> 4);
        if (claim == null) return true; // свободный чанк
        if (claim.isOwner(playerName)) return true;
        long now = System.currentTimeMillis();
        return claim.rentedAt(now) && claim.tenant != null
                && claim.tenant.equalsIgnoreCase(playerName);
    }
}
