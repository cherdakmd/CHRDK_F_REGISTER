package ru.cherdakmd.chrdkvk;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Обработчик игровых и административных команд {@code /vk} и {@code /vkadmin}.
 */
public final class VkCommandHandler implements CommandExecutor, TabCompleter {

    private final VkPlugin plugin;
    private final VkService service;

    public VkCommandHandler(VkPlugin plugin, VkService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase("vkadmin")) {
            return handleAdmin(sender, args);
        }
        return handlePlayer(sender, args);
    }

    private boolean handlePlayer(CommandSender sender, String[] args) {
        Messages msg = plugin.messages();
        ChrdkRegisterApi api = plugin.coreApi();
        if (api == null) {
            msg.send(sender, "vk.no-api");
            return true;
        }
        VkConfig cfg = plugin.config();
        if (!cfg.enabled()) {
            msg.send(sender, "vk.disabled");
            return true;
        }
        if (!(sender instanceof Player player)) {
            msg.send(sender, "vk.player-only");
            return true;
        }
        if (!api.isAuthenticated(player)) {
            msg.send(player, "vk.need-auth");
            return true;
        }

        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status", "info", "статус" -> showStatus(player, api, cfg);
            case "link", "code", "привязать", "код" -> issueLinkCode(player, cfg);
            case "unlink", "отвязать" -> unlinkSelf(player, api);
            case "notify", "alerts", "уведомления" -> toggleNotify(player);
            default -> msg.send(player, "vk.usage");
        }
        return true;
    }

    private void showStatus(Player player, ChrdkRegisterApi api, VkConfig cfg) {
        Messages msg = plugin.messages();
        msg.send(player, "vk.status-header");
        msg.send(player, "vk.status-community", "url", cfg.communityUrl());

        Optional<VkStore.LinkSnapshot> opt = service.store().byPlayer(player.getName());
        if (opt.isEmpty()) {
            msg.send(player, "vk.status-unlinked");
            msg.send(player, "vk.status-rewards-hint");
            return;
        }
        VkStore.LinkSnapshot link = opt.get();
        boolean donutActive = service.store().donutPerkActive(
                player.getName(), System.currentTimeMillis(), cfg.donutGraceHours());
        boolean frozen = api.isLoginFrozen(player.getName());

        msg.send(player, "vk.status-linked", "vk_id", Long.toString(link.vkUserId()));
        msg.send(player, "vk.status-membership",
                "member", link.member() ? msg.mini("vk.state-yes") : msg.mini("vk.state-no"),
                "donut", donutActive ? msg.mini("vk.state-yes") : msg.mini("vk.state-no"));
        msg.send(player, "vk.status-security",
                "notify", link.notifyLogins() ? msg.mini("vk.state-on") : msg.mini("vk.state-off"),
                "frozen", frozen ? msg.mini("vk.state-frozen") : msg.mini("vk.state-active"));
        msg.send(player, "vk.status-rewards-hint");
    }

    private void issueLinkCode(Player player, VkConfig cfg) {
        Messages msg = plugin.messages();
        Optional<VkStore.LinkSnapshot> existing = service.store().byPlayer(player.getName());
        if (existing.isPresent()) {
            msg.send(player, "vk.link-already", "vk_id", Long.toString(existing.get().vkUserId()));
            return;
        }
        if (!cfg.apiConfigured()) {
            msg.send(player, "vk.link-not-configured");
            return;
        }
        VkLinkCodeManager.IssueResult issued = service.codeManager().issue(
                player.getName(),
                cfg.codeLength(),
                cfg.codeTtlSeconds(),
                cfg.codeCooldownSeconds(),
                System.currentTimeMillis()
        );
        if (issued.status() == VkLinkCodeManager.Status.COOLDOWN) {
            msg.send(player, "vk.link-cooldown",
                    "seconds", Long.toString(issued.remainingCooldownSeconds()));
            return;
        }
        int minutes = Math.max(1, cfg.codeTtlSeconds() / 60);
        msg.sendList(player, "vk.link-code-issued",
                "code", issued.code(),
                "minutes", Integer.toString(minutes),
                "url", cfg.communityUrl());
    }

    private void unlinkSelf(Player player, ChrdkRegisterApi api) {
        Messages msg = plugin.messages();
        Optional<VkStore.LinkSnapshot> removed = service.store().unlink(player.getName());
        if (removed.isEmpty()) {
            msg.send(player, "vk.unlink-none");
            return;
        }
        if (api.isLoginFrozen(player.getName())) {
            api.setLoginFrozen(player.getName(), false, VkService.PROVIDER_ID);
        }
        service.store().saveNow();
        msg.send(player, "vk.unlink-ok", "vk_id", Long.toString(removed.get().vkUserId()));
    }

    private void toggleNotify(Player player) {
        Messages msg = plugin.messages();
        Optional<Boolean> toggled = service.store().toggleNotifyLogins(player.getName());
        if (toggled.isEmpty()) {
            msg.send(player, "vk.unlink-none");
            return;
        }
        service.store().saveIfDirty();
        msg.send(player, "vk.notify-toggled",
                "state", toggled.get() ? msg.mini("vk.state-on") : msg.mini("vk.state-off"));
    }

    private boolean handleAdmin(CommandSender sender, String[] args) {
        Messages msg = plugin.messages();
        if (!sender.hasPermission("chrdkvk.admin")) {
            msg.send(sender, "admin.usage");
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> {
                VkConfig cfg = plugin.config();
                long now = System.currentTimeMillis();
                msg.send(sender, "admin.status",
                        "configured", cfg.apiConfigured() ? msg.mini("vk.state-yes") : msg.mini("vk.state-no"),
                        "worker", service.longPollConnected() ? msg.mini("vk.state-on") : msg.mini("vk.state-off"),
                        "links", Integer.toString(service.store().linkCount()),
                        "donuts", Integer.toString(service.store().activeDonutCount(now, cfg.donutGraceHours())),
                        "last_post", Long.toString(service.store().lastAnnouncedPostId()));
            }
            case "lookup" -> {
                if (args.length < 2) {
                    msg.send(sender, "admin.usage");
                    return true;
                }
                String target = args[1];
                Optional<VkStore.LinkSnapshot> found = service.store().byPlayer(target);
                if (found.isEmpty()) {
                    msg.send(sender, "admin.lookup-none", "player", target);
                    return true;
                }
                VkStore.LinkSnapshot link = found.get();
                ChrdkRegisterApi api = plugin.coreApi();
                boolean frozen = api != null && api.isLoginFrozen(link.playerName());
                boolean donut = service.store().donutPerkActive(
                        link.playerName(), System.currentTimeMillis(), plugin.config().donutGraceHours());
                msg.send(sender, "admin.lookup-found",
                        "player", link.playerName(),
                        "vk_id", Long.toString(link.vkUserId()),
                        "member", link.member() ? msg.mini("vk.state-yes") : msg.mini("vk.state-no"),
                        "donut", donut ? msg.mini("vk.state-yes") : msg.mini("vk.state-no"),
                        "notify", link.notifyLogins() ? msg.mini("vk.state-on") : msg.mini("vk.state-off"),
                        "frozen", frozen ? msg.mini("vk.state-frozen") : msg.mini("vk.state-active"));
            }
            case "unlink" -> {
                if (args.length < 2) {
                    msg.send(sender, "admin.usage");
                    return true;
                }
                String target = args[1];
                Optional<VkStore.LinkSnapshot> removed = service.store().unlink(target);
                if (removed.isEmpty()) {
                    msg.send(sender, "admin.lookup-none", "player", target);
                    return true;
                }
                ChrdkRegisterApi api = plugin.coreApi();
                if (api != null && api.isLoginFrozen(target)) {
                    api.setLoginFrozen(target, false, "vkadmin");
                }
                service.store().saveNow();
                msg.send(sender, "admin.unlink-ok", "player", removed.get().playerName());
            }
            case "sync" -> {
                msg.send(sender, "admin.sync-started");
                service.triggerManualSync(report -> msg.send(sender, "admin.sync-done",
                        "checked", Integer.toString(report.checkedLinks()),
                        "donuts", Integer.toString(report.activeDonuts())));
            }
            case "reload" -> {
                if (plugin.reloadBundle()) {
                    msg.send(sender, "admin.reloaded");
                } else {
                    msg.send(sender, "admin.reload-failed");
                }
            }
            default -> msg.send(sender, "admin.usage");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender,
                                      @NotNull Command command,
                                      @NotNull String alias,
                                      @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase("vkadmin")) {
            if (!sender.hasPermission("chrdkvk.admin")) {
                return List.of();
            }
            if (args.length == 1) {
                return filterPrefix(List.of("status", "lookup", "unlink", "sync", "reload"), args[0]);
            }
            if (args.length == 2 && (args[0].equalsIgnoreCase("lookup") || args[0].equalsIgnoreCase("unlink"))) {
                List<String> linkedPlayers = service.store().allLinks().stream()
                        .map(VkStore.LinkSnapshot::playerName)
                        .toList();
                return filterPrefix(linkedPlayers, args[1]);
            }
            return List.of();
        }
        if (args.length == 1) {
            return filterPrefix(List.of("status", "link", "notify", "unlink"), args[0]);
        }
        return List.of();
    }

    private static List<String> filterPrefix(List<String> options, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> matched = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                matched.add(option);
            }
        }
        return matched;
    }
}
