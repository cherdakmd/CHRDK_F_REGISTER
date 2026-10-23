package ru.cherdakmd.chrdktreasures;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.ArrayList;
import java.util.List;

/**
 * Раздел кладов в {@code /profile} (1.4.156).
 *
 * <p>Сколько кладов найдено, сколько предметов коллекции собрано и какая была
 * лучшая серия находок. Данные лежат в хранилище по UUID и доступны оффлайн.</p>
 */
final class TreasuresProfile implements ChrdkRegisterApi.ProfileProvider {

    private final TreasuresPlugin plugin;

    TreasuresProfile(TreasuresPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public int order() {
        return 80;
    }

    @Override
    public ChrdkRegisterApi.ProfileSection section(ChrdkRegisterApi.ProfileContext context) {
        if (context == null || plugin.store() == null || plugin.treasures() == null) {
            return null;
        }
        String key = context.playerId().toString();
        long found = plugin.store().found(key);
        int items = plugin.store().foundItems(key);
        if (found <= 0L && items <= 0) {
            return null;
        }
        int total = Math.max(items, plugin.treasures().totalItems());
        int streak = plugin.store().bestStreak(key);
        List<Component> lines = new ArrayList<>();
        lines.add(plugin.messages().get("profile-found", "found", String.valueOf(found)));
        lines.add(plugin.messages().get("profile-collection",
                "items", String.valueOf(items),
                "total", String.valueOf(total)));
        if (streak > 0) {
            lines.add(plugin.messages().get("profile-streak",
                    "streak", String.valueOf(streak)));
        }
        return new ChrdkRegisterApi.ProfileSection(plugin.messages().raw("profile-title"),
                Material.COMPASS, lines);
    }
}
