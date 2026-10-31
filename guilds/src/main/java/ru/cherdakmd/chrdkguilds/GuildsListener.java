package ru.cherdakmd.chrdkguilds;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

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
        if (killer == null || killer.getUniqueId().equals(victim.getUniqueId())
                || !plugin.authenticated(killer) || !plugin.authenticated(victim)) {
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

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAuthenticated(ru.cherdakmd.chrdkregister.api.events.PlayerAuthCompletedEvent event) {
        plugin.payments().recover(event.getPlayer());
        remind(event.getPlayer());
        plugin.peace().deliverNotices(event.getPlayer().getUniqueId());
        if(plugin.visible(event.getPlayer())&&plugin.service().guildOf(event.getPlayer().getUniqueId())!=null)plugin.peace().status(event.getPlayer());
    }

    /** Напоминание о приглашении: игрок мог не видеть его в чате. */
    private void remind(Player player) {
        if (!plugin.visible(player)) return;
        GuildInvite invite = plugin.store().inviteOf(player.getUniqueId());
        if (invite == null || invite.expired(System.currentTimeMillis())) return;
        Guild guild=plugin.store().guild(invite.guildId());
        if(guild!=null&&guild.peaceful())plugin.messages().send(player,"peace-join-terms","cost",GuildPeaceService.DAILY);
        plugin.messages().send(player, "invite-reminder", "name", invite.guildName(),
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
