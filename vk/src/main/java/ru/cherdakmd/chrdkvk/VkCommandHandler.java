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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Обработчик игровых и административных команд {@code /vk} и {@code /vkadmin}.
 */
public final class VkCommandHandler implements CommandExecutor, TabCompleter {

    private static final long UNLINK_CONFIRM_WINDOW_MILLIS = 30_000L;

    private final VkPlugin plugin;
    private final VkService service;
    private VkMenuGui gui;
    private final Map<UUID, Long> pendingUnlinkConfirmations = new ConcurrentHashMap<>();

    public VkCommandHandler(VkPlugin plugin, VkService service) {
        this.plugin = plugin;
        this.service = service;
    }

    public void attachGui(VkMenuGui gui) {
        this.gui = gui;
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
        Messages messages = plugin.messages();
        ChrdkRegisterApi api = plugin.coreApi();
        if (api == null) {
            messages.send(sender, "vk.no-api");
            return true;
        }
        VkConfig cfg = plugin.config();
        if (!cfg.enabled()) {
            messages.send(sender, "vk.disabled");
            return true;
        }
        if (!(sender instanceof Player player)) {
            messages.send(sender, "vk.player-only");
            return true;
        }
        if (!api.isAuthenticated(player)) {
            messages.send(player, "vk.need-auth");
            return true;
        }

        if (args.length == 0) {
            if (gui != null) {
                gui.open(player);
            } else {
                showStatus(player, api, cfg);
            }
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "menu", "gui", "меню" -> {
                if (gui != null) {
                    gui.open(player);
                } else {
                    showStatus(player, api, cfg);
                }
            }
            case "status", "info", "статус" -> showStatus(player, api, cfg);
            case "link", "code", "привязать", "код" -> issueLinkCode(player, cfg);
            case "notify", "alerts", "уведомления" -> toggleNotify(player);
            case "chat", "чат", "беседа" -> toggleGroupChatVisibility(player);
            case "mentions", "пинг", "упоминания" -> toggleMentions(player);
            case "unlink", "отвязать" -> {
                boolean confirmed = args.length >= 2 && (
                        args[1].equalsIgnoreCase("confirm")
                                || args[1].equalsIgnoreCase("yes")
                                || args[1].equalsIgnoreCase("да"));
                unlinkSelf(player, api, confirmed);
            }
            default -> messages.send(player, "vk.usage");
        }
        return true;
    }

    void showStatus(Player player, ChrdkRegisterApi api, VkConfig cfg) {
        Messages messages = plugin.messages();
        messages.send(player, "vk.status-header");
        messages.send(player, "vk.status-community", "url", cfg.communityUrl());

        boolean receiveChat = service.store().receivesGroupChat(player.getName());
        Optional<VkStore.LinkSnapshot> opt = service.store().byPlayer(player.getName());
        if (opt.isEmpty()) {
            messages.send(player, "vk.status-unlinked");
            messages.send(player, "vk.status-group-chat",
                    "chat_bridge", service.groupChatBridgeReady()
                            ? messages.mini("vk.state-on") : messages.mini("vk.state-off"),
                    "receive", receiveChat ? messages.mini("vk.state-on") : messages.mini("vk.state-off"),
                    "mentions", messages.mini("vk.state-no"));
            messages.send(player, "vk.status-rewards-hint");
            return;
        }
        VkStore.LinkSnapshot link = opt.get();
        boolean donutActive = service.store().donutPerkActive(
                player.getName(), System.currentTimeMillis(), cfg.donutGraceHours());
        boolean frozen = api.isLoginFrozen(player.getName());

        messages.send(player, "vk.status-linked", "vk_id", Long.toString(link.vkUserId()));
        messages.send(player, "vk.status-membership",
                "member", link.member() ? messages.mini("vk.state-yes") : messages.mini("vk.state-no"),
                "donut", donutActive ? messages.mini("vk.state-yes") : messages.mini("vk.state-no"));
        messages.send(player, "vk.status-security",
                "notify", link.notifyLogins() ? messages.mini("vk.state-on") : messages.mini("vk.state-off"),
                "frozen", frozen ? messages.mini("vk.state-frozen") : messages.mini("vk.state-active"));
        messages.send(player, "vk.status-group-chat",
                "chat_bridge", service.groupChatBridgeReady()
                        ? messages.mini("vk.state-on") : messages.mini("vk.state-off"),
                "receive", receiveChat ? messages.mini("vk.state-on") : messages.mini("vk.state-off"),
                "mentions", link.allowVkChatMentions() ? messages.mini("vk.state-on") : messages.mini("vk.state-off"));
        messages.send(player, "vk.status-rewards-hint");
    }

    void issueLinkCode(Player player, VkConfig cfg) {
        Messages messages = plugin.messages();
        Optional<VkStore.LinkSnapshot> existing = service.store().byPlayer(player.getName());
        if (existing.isPresent()) {
            messages.send(player, "vk.link-already", "vk_id", Long.toString(existing.get().vkUserId()));
            return;
        }
        if (!cfg.apiConfigured()) {
            messages.send(player, "vk.link-not-configured");
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
            messages.send(player, "vk.link-cooldown",
                    "seconds", Long.toString(issued.remainingCooldownSeconds()));
            return;
        }
        int minutes = Math.max(1, cfg.codeTtlSeconds() / 60);
        messages.sendList(player, "vk.link-code-issued",
                "code", issued.code(),
                "minutes", Integer.toString(minutes),
                "url", cfg.communityUrl());
    }

    private void unlinkSelf(Player player, ChrdkRegisterApi api, boolean confirmedArg) {
        Messages messages = plugin.messages();
        Optional<VkStore.LinkSnapshot> existing = service.store().byPlayer(player.getName());
        if (existing.isEmpty()) {
            messages.send(player, "vk.not-linked");
            return;
        }
        long now = System.currentTimeMillis();
        Long armedUntil = pendingUnlinkConfirmations.get(player.getUniqueId());
        boolean armed = armedUntil != null && armedUntil > now;
        if (!confirmedArg && !armed) {
            pendingUnlinkConfirmations.put(player.getUniqueId(), now + UNLINK_CONFIRM_WINDOW_MILLIS);
            messages.send(player, "vk.unlink-confirm-prompt",
                    "vk_id", Long.toString(existing.get().vkUserId()));
            return;
        }
        pendingUnlinkConfirmations.remove(player.getUniqueId());
        performUnlinkConfirmed(player, api);
    }

    void performUnlinkConfirmed(Player player, ChrdkRegisterApi api) {
        Messages messages = plugin.messages();
        Optional<VkStore.LinkSnapshot> removed = service.store().unlink(player.getName());
        if (removed.isEmpty()) {
            messages.send(player, "vk.not-linked");
            return;
        }
        if (api.isLoginFrozen(player.getName())) {
            api.setLoginFrozen(player.getName(), false, VkService.PROVIDER_ID);
        }
        service.store().saveNow();
        messages.send(player, "vk.unlink-ok", "vk_id", Long.toString(removed.get().vkUserId()));
    }

    void toggleNotify(Player player) {
        Messages messages = plugin.messages();
        Optional<Boolean> toggled = service.store().toggleNotifyLogins(player.getName());
        if (toggled.isEmpty()) {
            messages.send(player, "vk.not-linked");
            return;
        }
        service.store().saveIfDirty();
        messages.send(player, "vk.notify-toggled",
                "state", toggled.get() ? messages.mini("vk.state-on") : messages.mini("vk.state-off"));
    }

    void toggleGroupChatVisibility(Player player) {
        Messages messages = plugin.messages();
        boolean nowReceiving = service.store().toggleReceiveGroupChat(player.getName());
        service.store().saveIfDirty();
        messages.send(player, "vk.chat-toggled",
                "state", nowReceiving ? messages.mini("vk.state-on") : messages.mini("vk.state-off"));
    }

    void toggleMentions(Player player) {
        Messages messages = plugin.messages();
        Optional<Boolean> toggled = service.store().toggleVkChatMentions(player.getName());
        if (toggled.isEmpty()) {
            messages.send(player, "vk.not-linked");
            return;
        }
        service.store().saveIfDirty();
        messages.send(player, "vk.mentions-toggled",
                "state", toggled.get() ? messages.mini("vk.state-on") : messages.mini("vk.state-off"));
    }

    private boolean handleAdmin(CommandSender sender, String[] args) {
        Messages messages = plugin.messages();
        if (!sender.hasPermission("chrdkvk.admin")) {
            messages.send(sender, "admin.usage");
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> {
                VkConfig cfg = plugin.config();
                long now = System.currentTimeMillis();
                long chatPeer = service.effectiveGroupChatPeerId();
                messages.send(sender, "admin.status",
                        "configured", cfg.apiConfigured() ? messages.mini("vk.state-yes") : messages.mini("vk.state-no"),
                        "worker", service.longPollConnected() ? messages.mini("vk.state-on") : messages.mini("vk.state-off"),
                        "chat_peer", chatPeer > 0L ? Long.toString(chatPeer) : "выкл",
                        "links", Integer.toString(service.store().linkCount()),
                        "donuts", Integer.toString(service.store().activeDonutCount(now, cfg.donutGraceHours())),
                        "chatbans", Integer.toString(service.store().bannedChatVkCount()),
                        "last_post", Long.toString(service.store().lastAnnouncedPostId()));
            }
            case "setchat" -> {
                if (args.length < 2) {
                    messages.send(sender, "admin.usage");
                    return true;
                }
                String rawPeer = args[1].trim();
                if (rawPeer.equalsIgnoreCase("off") || rawPeer.equals("0") || rawPeer.equalsIgnoreCase("reset")) {
                    service.store().setGroupChatPeerId(0L);
                    service.store().saveNow();
                    long configPeer = plugin.config().groupChatPeerId();
                    messages.send(sender, "admin.setchat-off",
                            "config_peer", configPeer > 0L ? Long.toString(configPeer) : "выкл");
                    return true;
                }
                long parsedPeer;
                try {
                    parsedPeer = Long.parseLong(rawPeer);
                } catch (NumberFormatException badNumber) {
                    messages.send(sender, "admin.setchat-invalid");
                    return true;
                }
                if (parsedPeer < VkConfig.MIN_CONVERSATION_PEER_ID) {
                    messages.send(sender, "admin.setchat-invalid");
                    return true;
                }
                service.store().setGroupChatPeerId(parsedPeer);
                service.store().saveNow();
                messages.send(sender, "admin.setchat-ok", "peer_id", Long.toString(parsedPeer));
            }
            case "testchat" -> {
                if (!service.groupChatBridgeReady()) {
                    messages.send(sender, "admin.testchat-not-ready");
                    return true;
                }
                String text = args.length >= 2
                        ? String.join(" ", Arrays.copyOfRange(args, 1, args.length))
                        : "✅ Проверка моста глобального чата CHRDK_VK прошла успешно!";
                service.sendToGroupChat(text);
                messages.send(sender, "admin.testchat-queued",
                        "peer_id", Long.toString(service.effectiveGroupChatPeerId()));
            }
            case "lookup" -> handleLookup(sender, args, messages);
            case "chatban" -> handleChatBan(sender, args, messages);
            case "unlink" -> {
                if (args.length < 2) {
                    messages.send(sender, "admin.usage");
                    return true;
                }
                String target = args[1];
                Optional<VkStore.LinkSnapshot> removed = service.store().unlink(target);
                if (removed.isEmpty()) {
                    messages.send(sender, "admin.lookup-none", "player", target);
                    return true;
                }
                ChrdkRegisterApi api = plugin.coreApi();
                if (api != null && api.isLoginFrozen(target)) {
                    api.setLoginFrozen(target, false, "vkadmin");
                }
                service.store().saveNow();
                messages.send(sender, "admin.unlink-ok", "player", removed.get().playerName());
            }
            case "sync" -> {
                messages.send(sender, "admin.sync-started");
                service.triggerManualSync(report -> messages.send(sender, "admin.sync-done",
                        "checked", Integer.toString(report.checkedLinks()),
                        "donuts", Integer.toString(report.activeDonuts())));
            }
            case "reload" -> {
                if (plugin.reloadBundle()) {
                    messages.send(sender, "admin.reloaded");
                } else {
                    messages.send(sender, "admin.reload-failed");
                }
            }
            default -> messages.send(sender, "admin.usage");
        }
        return true;
    }

    private void handleLookup(CommandSender sender, String[] args, Messages messages) {
        if (args.length < 2) {
            messages.send(sender, "admin.usage");
            return;
        }
        String target = args[1];
        Optional<VkStore.LinkSnapshot> byPlayer = service.store().byPlayer(target);
        if (byPlayer.isPresent()) {
            sendLookupCard(sender, byPlayer.get(), messages);
            return;
        }
        OptionalLong vkIdOpt = VkStore.parseVkUserIdToken(target);
        if (vkIdOpt.isPresent()) {
            long vkId = vkIdOpt.getAsLong();
            List<VkStore.LinkSnapshot> byVk = service.store().byVkUser(vkId);
            if (!byVk.isEmpty()) {
                for (VkStore.LinkSnapshot link : byVk) {
                    sendLookupCard(sender, link, messages);
                }
                return;
            }
            boolean chatBanned = service.store().isVkUserChatBanned(vkId);
            messages.send(sender, "admin.lookup-vk-unlinked",
                    "vk_id", Long.toString(vkId),
                    "chatban", chatBanned ? messages.mini("vk.state-yes") : messages.mini("vk.state-no"));
            return;
        }
        messages.send(sender, "admin.lookup-none", "player", target);
    }

    private void sendLookupCard(CommandSender sender, VkStore.LinkSnapshot link, Messages messages) {
        ChrdkRegisterApi api = plugin.coreApi();
        boolean frozen = api != null && api.isLoginFrozen(link.playerName());
        boolean donut = service.store().donutPerkActive(
                link.playerName(), System.currentTimeMillis(), plugin.config().donutGraceHours());
        boolean chatBanned = service.store().isVkUserChatBanned(link.vkUserId());
        messages.send(sender, "admin.lookup-found",
                "player", link.playerName(),
                "vk_id", Long.toString(link.vkUserId()),
                "member", link.member() ? messages.mini("vk.state-yes") : messages.mini("vk.state-no"),
                "donut", donut ? messages.mini("vk.state-yes") : messages.mini("vk.state-no"),
                "notify", link.notifyLogins() ? messages.mini("vk.state-on") : messages.mini("vk.state-off"),
                "frozen", frozen ? messages.mini("vk.state-frozen") : messages.mini("vk.state-active"),
                "chatban", chatBanned ? messages.mini("vk.state-yes") : messages.mini("vk.state-no"));
    }

    private void handleChatBan(CommandSender sender, String[] args, Messages messages) {
        if (args.length < 2) {
            messages.send(sender, "admin.usage");
            return;
        }
        String target = args[1];
        boolean ban = args.length < 3 || !(
                args[2].equalsIgnoreCase("off")
                        || args[2].equalsIgnoreCase("unban")
                        || args[2].equalsIgnoreCase("false"));

        long vkId = 0L;
        String label = target;
        Optional<VkStore.LinkSnapshot> byPlayer = service.store().byPlayer(target);
        if (byPlayer.isPresent()) {
            vkId = byPlayer.get().vkUserId();
            label = byPlayer.get().playerName() + " (id" + vkId + ")";
        } else {
            OptionalLong parsed = VkStore.parseVkUserIdToken(target);
            if (parsed.isPresent()) {
                vkId = parsed.getAsLong();
                List<VkStore.LinkSnapshot> linked = service.store().byVkUser(vkId);
                label = linked.isEmpty()
                        ? "id" + vkId
                        : linked.stream().map(VkStore.LinkSnapshot::playerName).collect(Collectors.joining(", "))
                        + " (id" + vkId + ")";
            }
        }
        if (vkId <= 0L) {
            messages.send(sender, "admin.chatban-invalid", "target", target);
            return;
        }
        service.store().setVkUserChatBanned(vkId, ban);
        service.store().saveNow();
        if (ban) {
            messages.send(sender, "admin.chatban-added", "target", label);
        } else {
            messages.send(sender, "admin.chatban-removed", "target", label);
        }
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
                return filterPrefix(List.of("status", "lookup", "chatban", "unlink", "setchat", "testchat", "sync", "reload"), args[0]);
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("setchat")) {
                return filterPrefix(List.of("off", "2000000001"), args[1]);
            }
            if (args.length == 2 && (args[0].equalsIgnoreCase("lookup")
                    || args[0].equalsIgnoreCase("unlink")
                    || args[0].equalsIgnoreCase("chatban"))) {
                List<String> linkedPlayers = service.store().allLinks().stream()
                        .map(VkStore.LinkSnapshot::playerName)
                        .toList();
                return filterPrefix(linkedPlayers, args[1]);
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("chatban")) {
                return filterPrefix(List.of("off"), args[2]);
            }
            return List.of();
        }
        if (args.length == 1) {
            return filterPrefix(List.of("menu", "status", "link", "notify", "chat", "mentions", "unlink"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("unlink")) {
            return filterPrefix(List.of("confirm"), args[1]);
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
