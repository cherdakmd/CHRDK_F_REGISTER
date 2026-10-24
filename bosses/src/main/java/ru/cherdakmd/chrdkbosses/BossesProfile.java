package ru.cherdakmd.chrdkbosses;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Раздел «Боссы» в {@code /profile} (1.4.162).
 *
 * <p>Общие победы над всеми боссами, текущий бой и зона, в которой игрок стоит.
 * Раздел не пустует у новичка: там написано, где искать босса и что арена
 * опасна, — иначе о событии узнают только те, кто случайно наткнулся.</p>
 */
final class BossesProfile implements ChrdkRegisterApi.ProfileProvider {

    private final BossesPlugin plugin;

    BossesProfile(BossesPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public int order() {
        return 45;
    }

    @Override
    public ChrdkRegisterApi.ProfileSection section(ChrdkRegisterApi.ProfileContext context) {
        if (context == null || plugin.service() == null) {
            return null;
        }
        Messages messages = plugin.messages();
        UUID id = context.playerId();
        List<Component> lines = new ArrayList<>();
        lines.add(messages.get("profile-kills",
                "kills", String.valueOf(plugin.store().killsAll()),
                "total", String.valueOf(plugin.config().bossList().size())));
        lines.add(messages.get("profile-alive",
                "count", String.valueOf(plugin.service().aliveCount())));

        Player online = context.online() ? Bukkit.getPlayer(id) : null;
        if (online != null) {
            ActiveBoss facing = null;
            for (ActiveBoss boss : plugin.service().active()) {
                if (boss.definition().arena().contains(
                        boss.entity().getWorld().getName(),
                        online.getLocation().getX(), online.getLocation().getY(),
                        online.getLocation().getZ())) {
                    facing = boss;
                    break;
                }
            }
            if (facing != null) {
                lines.add(messages.get("profile-fighting",
                        "name", facing.definition().name(),
                        "phase", facing.phase().name(),
                        "percent", String.valueOf(Math.round(facing.progress() * 100))));
            }
            DangerZone zone = plugin.service().zoneAt(online);
            if (zone != null) {
                lines.add(messages.get("profile-zone",
                        "zone", zone.name(),
                        "bonus", String.valueOf(zone.xpBonusPercent())));
            }
        }
        BossStore.Entry last = lastKill();
        if (last != null) {
            lines.add(messages.get("profile-last",
                    "name", last.name(),
                    "time", last.time()));
        }
        int best = bestKills();
        if (best > 0) {
            lines.add(messages.get("profile-top", "kills", String.valueOf(best)));
        }
        return new ChrdkRegisterApi.ProfileSection(messages.raw("profile-title"),
                Material.WITHER_SKELETON_SKULL, lines);
    }

    private BossStore.Entry lastKill() {
        List<BossStore.Entry> history = plugin.store().history();
        return history.isEmpty() ? null : history.get(history.size() - 1);
    }

    /** Наибольшее число побед над одним боссом; −1 — если не убивали никого. */
    private int bestKills() {
        int best = -1;
        for (BossDefinition definition : plugin.config().bossList()) {
            int kills = plugin.store().kills(definition.id());
            if (kills > best) {
                best = kills;
            }
        }
        return best;
    }
}
