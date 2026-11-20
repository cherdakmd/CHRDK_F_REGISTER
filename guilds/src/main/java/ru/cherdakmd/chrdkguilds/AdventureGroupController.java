package ru.cherdakmd.chrdkguilds;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.UUID;

/** Команды временных групп; ни этот контроллер, ни модель не меняют PvP. */
final class AdventureGroupController implements Listener {
    private final GuildsPlugin plugin;
    private AdventureGroups groups;

    AdventureGroupController(GuildsPlugin plugin) {
        this.plugin = plugin;
        reset();
    }

    void reset() {
        if (groups != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!groups.snapshot(player.getUniqueId()).isEmpty()) plugin.messages().send(player, "squad-reset");
            }
            groups.clear();
        }
        int configured = plugin.getConfig().getInt("squads.max-members", 16);
        int maximum = Math.clamp(configured, AdventureGroups.MIN_MEMBERS, AdventureGroups.MAX_MEMBERS);
        if (maximum != configured) plugin.getLogger().warning("squads.max-members ограничен диапазоном 2…16: " + maximum);
        groups = new AdventureGroups(maximum, this::relation, this::availablePlayer);
    }

    void shutdown() { if (groups != null) groups.clear(); }

    /** Публичный мост вызывает это на серверном потоке; полное содержимое группы не раскрывается. */
    AdventureGroupRelation groupRelation(UUID first, UUID second) {
        if (!plugin.isEnabled() || plugin.config() == null || !plugin.config().enabled()
                || plugin.payments() == null || plugin.payments().paused() || groups == null
                || first == null || second == null || !availablePlayer(first) || !availablePlayer(second)) {
            return AdventureGroupRelation.UNKNOWN;
        }
        List<UUID> before = groups.snapshot(first);
        if (!before.contains(second)) return AdventureGroupRelation.DIFFERENT;
        AdventureGroups.Result verified = groups.verify(first);
        if (verified == AdventureGroups.Result.AT_WAR) notifyAll(before, "squad-war-disbanded");
        if (verified != AdventureGroups.Result.OK) return AdventureGroupRelation.UNKNOWN;
        return groups.relation(first, second);
    }

    private GuildRelation relation(UUID a, UUID b) {
        if (!plugin.isEnabled() || plugin.config() == null || !plugin.config().enabled()
                || plugin.payments() == null || plugin.payments().paused() || plugin.service() == null) {
            return GuildRelation.UNKNOWN;
        }
        return plugin.service().relation(a, b);
    }

    private boolean availablePlayer(UUID id) {
        Player p = Bukkit.getPlayer(id);
        return p != null && p.isOnline() && !p.isDead() && !p.hasMetadata("NPC") && plugin.visible(p);
    }

    void maintain() {
        for (UUID id : groups.participants()) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline() || !plugin.visible(player) || player.hasMetadata("NPC")) leave(id);
        }
        for (List<UUID> dissolved : groups.reconcile()) notifyAll(dissolved, "squad-war-disbanded");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        leave(event.getPlayer().getUniqueId());
    }

    private void leave(UUID id) {
        List<UUID> before = groups.snapshot(id);
        groups.leave(id);
        if (before.isEmpty()) return;
        List<UUID> after = groups.snapshot(before.get(0));
        if (after.isEmpty()) notifyAll(before, "squad-disbanded");
        else notifyAll(after, "squad-member-left");
    }

    void command(Player player, String[] args) {
        maintain();
        if (args.length < 2) { plugin.messages().send(player, "squad-help"); return; }
        UUID actor = player.getUniqueId();
        switch (args[1].toLowerCase(java.util.Locale.ROOT)) {
            case "invite" -> {
                if (args.length != 3) { plugin.messages().send(player, "squad-help"); return; }
                Player target = Bukkit.getPlayerExact(args[2]);
                if (target == null || !availablePlayer(target.getUniqueId())) {
                    plugin.messages().send(player, "squad-offline"); return;
                }
                AdventureGroups.Result result = groups.invite(actor, target.getUniqueId(), System.currentTimeMillis());
                if (result != AdventureGroups.Result.OK) { error(player, result); return; }
                plugin.messages().send(player, "squad-invite-sent", "player", target.getName());
                plugin.messages().send(target, "squad-invite-received", "player", player.getName());
            }
            case "accept" -> {
                if (args.length != 2) { plugin.messages().send(player, "squad-help"); return; }
                AdventureGroups.Result result = groups.accept(actor, System.currentTimeMillis());
                if (result != AdventureGroups.Result.OK) { error(player, result); return; }
                notifyAll(groups.members(actor), "squad-joined");
            }
            case "decline" -> {
                if (args.length != 2) { plugin.messages().send(player, "squad-help"); return; }
                plugin.messages().send(player, groups.decline(actor) ? "squad-declined" : "squad-no-invite");
            }
            case "leave" -> {
                if (args.length != 2) { plugin.messages().send(player, "squad-help"); return; }
                if (groups.snapshot(actor).isEmpty()) { plugin.messages().send(player, "squad-not-member"); return; }
                leave(actor);
                plugin.messages().send(player, "squad-left");
            }
            case "info" -> {
                if (args.length != 2) { plugin.messages().send(player, "squad-help"); return; }
                AdventureGroups.Result result = groups.verify(actor);
                if (result != AdventureGroups.Result.OK) { error(player, result); return; }
                String names = groups.members(actor).stream().map(Bukkit::getPlayer)
                        .filter(java.util.Objects::nonNull).map(Player::getName)
                        .collect(java.util.stream.Collectors.joining(", "));
                plugin.messages().send(player, "squad-info", "players", names);
            }
            default -> plugin.messages().send(player, "squad-help");
        }
    }

    private void notifyAll(List<UUID> players, String key) {
        for (UUID id : players) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) plugin.messages().send(player, key);
        }
    }

    private void error(Player player, AdventureGroups.Result result) {
        String key = switch (result) {
            case INVALID -> "squad-not-member";
            case OFFLINE -> "squad-offline";
            case ALREADY_JOINED -> "squad-already-joined";
            case NOT_LEADER -> "squad-not-leader";
            case GROUP_FULL -> "squad-full";
            case INVITE_MISSING -> "squad-no-invite";
            case INVITE_EXPIRED -> "squad-invite-expired";
            case AT_WAR -> "squad-at-war";
            case UNAVAILABLE -> "squad-unavailable";
            case OK -> throw new IllegalArgumentException("success is not an error");
        };
        plugin.messages().send(player, key);
    }
}
