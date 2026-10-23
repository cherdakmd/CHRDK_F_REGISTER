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
        if (claims <= 0 && treasury <= 0) {
            return null;
        }
        int limit = plugin.service().limitOf(name);
        List<Component> lines = new ArrayList<>();
        lines.add(plugin.messages().get("profile-claims",
                "used", String.valueOf(claims),
                "limit", String.valueOf(limit)));
        if (treasury > 0) {
            lines.add(plugin.messages().get("profile-treasury",
                    "amount", String.valueOf(treasury)));
        }
        return new ChrdkRegisterApi.ProfileSection(plugin.messages().raw("profile-title"),
                Material.OAK_FENCE, lines);
    }
}
