package ru.cherdakmd.chrdkclaims;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.ArrayList;
import java.util.List;

/**
 * Раздел приватов в {@code /profile} (1.4.156).
 *
 * <p>Сколько чанков занято, какой лимит и что лежит в казне. Приваты хранятся
 * по нику владельца, поэтому раздел читается и для оффлайн-игрока.</p>
 */
final class ClaimsProfile implements ChrdkRegisterApi.ProfileProvider {

    private final ClaimsPlugin plugin;

    ClaimsProfile(ClaimsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public int order() {
        return 70;
    }

    @Override
    public ChrdkRegisterApi.ProfileSection section(ChrdkRegisterApi.ProfileContext context) {
        if (context == null || plugin.store() == null || plugin.service() == null) {
            return null;
        }
        String name = context.playerName();
        if (name == null || name.isBlank()) {
            return null;
        }
        int claims = plugin.store().count(name);
        int treasury = plugin.store().treasuryOf(name);
        long now = System.currentTimeMillis();
        long rented = plugin.store().all().stream().filter(c -> c.rentedAt(now) && c.tenant.equalsIgnoreCase(name)).count();
        if (claims <= 0 && rented <= 0) {
            return null;
        }
        int limit = plugin.service().limitOf(name);
        List<Component> lines = new ArrayList<>();
        lines.add(plugin.messages().get("profile-claims",
                "used", String.valueOf(claims),
                "limit", String.valueOf(limit)));
        if (rented > 0) lines.add(plugin.messages().get("profile-rented", "count", rented));
        if (plugin.store().fundsReady(name)) {
            lines.add(plugin.messages().get("finance.profile-treasury",
                    "amount", String.valueOf(treasury)));
        }
        else lines.add(plugin.messages().get("finance.profile-unavailable"));
        // Чужие/офлайн-инвентари не сканируем ради просмотра профиля.
        if (context.self() && context.online() && context.playerId() != null) {
            var player = org.bukkit.Bukkit.getPlayer(context.playerId());
            if (player != null) lines.add(plugin.messages().get("finance.profile-inventory",
                    "amount", DiamondItems.count(player.getInventory())));
        }
        return new ChrdkRegisterApi.ProfileSection(plugin.messages().raw("profile-title"),
                Material.OAK_FENCE, lines);
    }
}
