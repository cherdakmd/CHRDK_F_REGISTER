package ru.cherdakmd.chrdkdaily;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.ArrayList;
import java.util.List;

/**
 * Раздел ежедневных наград в {@code /profile} (1.4.156).
 *
 * <p>Стрик и число полученных наград. Хранилище ключуется по UUID, поэтому
 * раздел одинаково читается для онлайн- и оффлайн-игрока.</p>
 */
final class DailyProfile implements ChrdkRegisterApi.ProfileProvider {

    private final DailyRewardsPlugin plugin;

    DailyProfile(DailyRewardsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public int order() {
        return 110;
    }

    @Override
    public ChrdkRegisterApi.ProfileSection section(ChrdkRegisterApi.ProfileContext context) {
        if (context == null || plugin.store() == null) {
            return null;
        }
        DailyStore.Progress progress = plugin.store().progressOf(context.playerId());
        if (progress == null || progress.totalClaims <= 0) {
            return null;
        }
        List<Component> lines = new ArrayList<>();
        lines.add(plugin.messages().get("daily.profile-streak",
                "streak", String.valueOf(progress.streak)));
        lines.add(plugin.messages().get("daily.profile-total",
                "total", String.valueOf(progress.totalClaims)));
        if (plugin.store().canClaim(context.playerId(), System.currentTimeMillis())) {
            lines.add(plugin.messages().get("daily.profile-ready"));
        }
        return new ChrdkRegisterApi.ProfileSection(plugin.messages().raw("daily.profile-title"),
                Material.CAKE, lines);
    }
}
