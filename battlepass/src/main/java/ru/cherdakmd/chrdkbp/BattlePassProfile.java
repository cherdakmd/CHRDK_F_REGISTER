package ru.cherdakmd.chrdkbp;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Раздел боевого пропуска в {@code /profile} (1.4.156).
 *
 * <p>Очки сезона считаются по выполненным заданиям — так же, как в рейтинге
 * {@link BattlePassScore}, поэтому цифры в профиле и в таблице лидеров совпадают.
 * Забранные награды показываются отдельной строкой.</p>
 */
final class BattlePassProfile implements ChrdkRegisterApi.ProfileProvider {

    private final BattlePassPlugin plugin;

    BattlePassProfile(BattlePassPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public int order() {
        return 90;
    }

    @Override
    public ChrdkRegisterApi.ProfileSection section(ChrdkRegisterApi.ProfileContext context) {
        if (context == null || plugin.store() == null || plugin.season() == null) {
            return null;
        }
        Map<String, Integer> progress = plugin.store().progressMap(context.playerId());
        int completed = BattlePassScore.completed(plugin.season(), progress);
        int claimed = BattlePassScore.claimed(plugin.season(),
                plugin.store().claimsOf(context.playerId()));
        if (completed <= 0 && claimed <= 0) {
            return null;
        }
        List<Component> lines = new ArrayList<>();
        lines.add(plugin.messages().get("profile-points",
                "points", String.valueOf(BattlePassScore.points(plugin.season(), progress))));
        lines.add(plugin.messages().get("profile-missions",
                "done", String.valueOf(completed),
                "total", String.valueOf(plugin.missionCount())));
        if (claimed > 0) {
            lines.add(plugin.messages().get("profile-claimed",
                    "claimed", String.valueOf(claimed)));
        }
        return new ChrdkRegisterApi.ProfileSection(plugin.messages().raw("profile-title"),
                Material.NETHER_STAR, lines);
    }
}
