package ru.cherdakmd.chrdkguilds;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * События мира для гильдий (1.4.163).
 *
 * <p>Единственное, что гильдии считают сами, — убийства на войне. Очки идут
 * только за участников вражеских гильдий, только в активной войне и только
 * один раз на пару за окно из конфига: иначе два игрока могли бы набивать счёт
 * бесконечно, убивая друг друга у спавна.</p>
 */
final class GuildsListener implements Listener {

    private final GuildsPlugin plugin;

    GuildsListener(GuildsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Очко войны за убийство участника вражеской гильдии. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player killer = victim.getKiller();
        if (killer == null || killer.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }
        boolean claimed = plugin.config().claimedBonus()
                && plugin.claimed(victim.getLocation());
        GuildResult result = plugin.service().kill(killer.getUniqueId(),
                victim.getUniqueId(), claimed, System.currentTimeMillis());
        if (!result.ok()) {
            return;
        }
        plugin.messages().send(killer, result.key(), result.array());
        if (plugin.config().actionbar()) {
            killer.sendActionBar(plugin.messages().get("war-point-actionbar",
                    "points", arg(result, "points"), "score", arg(result, "score")));
        }
        plugin.requestSave();
    }

    /** Напоминание о приглашении: игрок мог не видеть его в чате. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.config().enabled()) {
            return;
        }
        GuildInvite invite = plugin.store().inviteOf(event.getPlayer().getUniqueId());
        if (invite == null || invite.expired(System.currentTimeMillis())) {
            return;
        }
        plugin.messages().send(event.getPlayer(), "invite-reminder",
                "name", invite.guildName(),
                "seconds", String.valueOf(invite.remainingSeconds(System.currentTimeMillis())));
    }

    /** Значение подстановки по имени (сервис возвращает пары «имя, значение»). */
    private static String arg(GuildResult result, String name) {
        var args = result.args();
        for (int index = 0; index + 1 < args.size(); index += 2) {
            if (args.get(index).equals(name)) {
                return args.get(index + 1);
            }
        }
        return "";
    }
}
