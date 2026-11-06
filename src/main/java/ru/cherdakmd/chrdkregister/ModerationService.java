package ru.cherdakmd.chrdkregister;

import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Bukkit-слой модерации: права, автоматический бан на пятом варне, ванильный
 * {@link BanList}, кик онлайн-игрока и обновление TAB.
 *
 * <p>Варны и журнал живут в {@link ModerationLedger}, а бан дублируется в
 * ванильный {@code BanList} по имени (постоянный, без срока): его видят и
 * другие плагины, и сам сервер на {@link org.bukkit.event.player.PlayerLoginEvent}.
 * Разбан из GUI снимает оба флага.</p>
 */
public final class ModerationService {

    public static final String PERMISSION_PANEL = "chrdkregister.admin.panel";
    public static final String PERMISSION_ROLES = "chrdkregister.admin.moderators";
    public static final String BAN_SOURCE = "CHRDK_AdminPanel";

    /** Результат выдачи варна для GUI и команды. */
    public record WarnOutcome(ModerationLedger.Warn warn, int active, boolean banned) { }

    private final RegisterPlugin plugin;
    private final ModerationLedger ledger;
    private final ModerationStore store;
    private volatile ModerationConfig config;

    public ModerationService(RegisterPlugin plugin, ModerationLedger ledger,
                             ModerationStore store, ModerationConfig config) {
        this.plugin = plugin;
        this.ledger = ledger;
        this.store = store;
        this.config = config == null ? ModerationConfig.DEFAULT : config;
    }

    public ModerationConfig config() {
        return config;
    }

    public ModerationLedger ledger() {
        return ledger;
    }

    public void reload(FileConfiguration file) {
        this.config = ModerationConfig.fromConfig(file);
    }

    /** Права на панель: permission ИЛИ роль, выданная через GUI. */
    public boolean isStaff(CommandSender sender) {
        if (sender == null) return false;
        if (sender.hasPermission(PERMISSION_PANEL)) return true;
        return ledger.isModerator(sender.getName());
    }

    /** Управлять составом модераторов могут только операторы/старшие администраторы. */
    public boolean canManageRoles(CommandSender sender) {
        return sender != null && (sender.hasPermission(PERMISSION_ROLES) || sender.isOp());
    }

    public boolean enabled() {
        return config.enabled();
    }

    public int warnLimit() {
        return config.warnLimit();
    }

    public int warnCount(String name) {
        return ledger.activeCount(name);
    }

    /** Значение {@code %tab_warns%}: «3/5» — счётчик и порог рядом. */
    public String warnText(String name) {
        return config.warnsInTab() ? warnCount(name) + "/" + config.warnLimit() : "";
    }

    /**
     * Публичный кадр подвала TAB: одинаковый для всех, без причин и дат.
     * Выключенный флаг даёт пустую строку, чтобы stock-строка подвала не
     * показывала «доску позора» без решения владельца.
     */
    public String warnsPublicText() {
        if (!enabled() || !config.warnsPublicInTab()) {
            return "";
        }
        return PublicWarnSummary.format(ledger.activeWarnSummary(),
                config.warnsPublicLimit(), config.warnLimit());
    }

    public boolean isBanned(String name) {
        if (ledger.isBanned(name)) return true;
        try {
            return Bukkit.getBanList(BanList.Type.NAME).isBanned(name);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Причина из нашего журнала; для чужого ванильного бана — подставленный шаблон без сырых %..%. */
    public String banReason(String name) {
        ModerationLedger.Ban ban = ledger.banOf(name);
        if (ban != null && !ban.reason.isBlank()) return ban.reason;
        return config.banReason()
                .replace("%player%", name == null ? "" : name)
                .replace("%count%", Integer.toString(warnCount(name)))
                .replace("%limit%", Integer.toString(config.warnLimit()));
    }

    public List<ModerationLedger.JournalEntry> journal(String filter, int limit) {
        return ledger.journal(filter, limit);
    }

    public void save() {
        store.save(ledger);
    }

    /* -------------------------------- варны -------------------------------- */

    public WarnOutcome warn(CommandSender actor, String targetName, String reason) {
        if (!isStaff(actor)) {
            plugin.messages().send(actor, "admin.no-permission");
            return null;
        }
        String target = ModerationLedger.display(targetName);
        if (target.isEmpty() || target.length() > 32) {
            plugin.messages().send(actor, "admin.target-not-found", "player", target);
            return null;
        }
        if (reason == null || reason.isBlank()) {
            plugin.messages().send(actor, "admin.reason-empty");
            return null;
        }
        if (target.equalsIgnoreCase(actor.getName())) {
            plugin.messages().send(actor, "admin.self-action");
            return null;
        }
        long now = System.currentTimeMillis();
        ModerationLedger.Warn warn = ledger.issueWarn(target, actor.getName(), reason.trim(), now);
        int active = ledger.activeCount(target);
        boolean banned = false;
        if (config.enabled() && active >= config.warnLimit() && !ledger.isBanned(target)) {
            String banReason = formatBanReason(target, active);
            applyBan(actor, target, banReason, now);
            audit(AuditLog.MODERATION_AUTO_BAN, actor, target, Map.of("reason", banReason));
            banned = true;
        }
        save();
        audit(AuditLog.MODERATION_WARN, actor, target,
                Map.of("reason", reason.trim(), "count", active, "id", warn == null ? 0 : warn.id));
        plugin.messages().send(actor, "admin.warn-issued",
                "player", target, "count", active, "limit", config.warnLimit(),
                "reason", reason.trim(), "actor", actor.getName());
        plugin.refreshTab();
        return new WarnOutcome(warn, active, banned);
    }

    public boolean removeWarn(CommandSender actor, long id) {
        if (!isStaff(actor)) {
            plugin.messages().send(actor, "admin.no-permission");
            return false;
        }
        long now = System.currentTimeMillis();
        if (!ledger.removeWarn(id, actor.getName(), now)) {
            plugin.messages().send(actor, "admin.warn-not-found", "id", id);
            return false;
        }
        save();
        audit(AuditLog.MODERATION_UNWARN, actor, "", Map.of("id", id));
        plugin.messages().send(actor, "admin.warn-removed", "id", id, "actor", actor.getName());
        plugin.refreshTab();
        return true;
    }

    /* --------------------------------- баны -------------------------------- */

    public boolean ban(CommandSender actor, String targetName, String reason) {
        if (!isStaff(actor)) {
            plugin.messages().send(actor, "admin.no-permission");
            return false;
        }
        String target = ModerationLedger.display(targetName);
        if (target.isEmpty() || target.length() > 32) {
            plugin.messages().send(actor, "admin.target-not-found", "player", target);
            return false;
        }
        String text = reason == null || reason.isBlank()
                ? formatBanReason(target, ledger.activeCount(target)) : reason.trim();
        applyBan(actor, target, text, System.currentTimeMillis());
        save();
        audit(AuditLog.MODERATION_BAN, actor, target, Map.of("reason", text));
        plugin.messages().send(actor, "admin.ban-applied",
                "player", target, "reason", text, "actor", actor.getName());
        plugin.refreshTab();
        return true;
    }

    public boolean unban(CommandSender actor, String targetName) {
        if (!isStaff(actor)) {
            plugin.messages().send(actor, "admin.no-permission");
            return false;
        }
        String target = ModerationLedger.display(targetName);
        if (target.isEmpty() || target.length() > 32) {
            plugin.messages().send(actor, "admin.target-not-found", "player", target);
            return false;
        }
        ledger.unban(target, actor.getName(), System.currentTimeMillis());
        try {
            Bukkit.getBanList(BanList.Type.NAME).pardon(target);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING,
                    "[CHRDK_Core] Не удалось снять BanList для " + target, exception);
        }
        save();
        audit(AuditLog.MODERATION_UNBAN, actor, target, Map.of());
        plugin.messages().send(actor, "admin.ban-removed", "player", target, "actor", actor.getName());
        plugin.refreshTab();
        return true;
    }

    /** Общая точка для ручного бана и автобана на пятом варне. */
    private void applyBan(CommandSender actor, String target, String reason, long now) {
        ledger.ban(target, reason, actor.getName(), now);
        try {
            BanList list = Bukkit.getBanList(BanList.Type.NAME);
            // Перезапись причины: повторный addBan для уже забаненного имени бросает исключение.
            if (list.isBanned(target)) list.pardon(target);
            list.addBan(target, reason, (Date) null, BAN_SOURCE);
        } catch (RuntimeException | LinkageError exception) {
            plugin.getLogger().log(Level.WARNING,
                    "[CHRDK_Core] Не удалось записать BanList для " + target, exception);
        }
        Player online = findOnline(target);
        if (online != null) {
            online.kick(plugin.messages().get("admin.ban-kick", "reason", reason));
        }
    }

    private Player findOnline(String name) {
        Player exact = Bukkit.getPlayerExact(name);
        if (exact != null) return exact;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().equalsIgnoreCase(name)) return player;
        }
        return null;
    }

    private String formatBanReason(String target, int count) {
        return config.banReason()
                .replace("%player%", target)
                .replace("%count%", Integer.toString(count))
                .replace("%limit%", Integer.toString(config.warnLimit()));
    }

    /* ----------------------------- модераторы ------------------------------ */

    public boolean addModerator(CommandSender actor, String name) {
        if (!isStaff(actor)) {
            plugin.messages().send(actor, "admin.no-permission");
            return false;
        }
        if (!canManageRoles(actor)) {
            plugin.messages().send(actor, "admin.role-denied");
            return false;
        }
        String target = ModerationLedger.display(name);
        if (target.isEmpty() || target.length() > 32) {
            plugin.messages().send(actor, "admin.target-not-found", "player", target);
            return false;
        }
        if (!ledger.addModerator(target, actor.getName(), System.currentTimeMillis())) {
            plugin.messages().send(actor, "admin.role-already", "player", target);
            return false;
        }
        save();
        audit(AuditLog.MODERATION_ROLE_ADD, actor, target, Map.of());
        plugin.messages().send(actor, "admin.role-added", "player", target, "actor", actor.getName());
        return true;
    }

    public boolean removeModerator(CommandSender actor, String name) {
        if (!isStaff(actor)) {
            plugin.messages().send(actor, "admin.no-permission");
            return false;
        }
        if (!canManageRoles(actor)) {
            plugin.messages().send(actor, "admin.role-denied");
            return false;
        }
        String target = ModerationLedger.display(name);
        if (!ledger.removeModerator(target, actor.getName(), System.currentTimeMillis())) {
            plugin.messages().send(actor, "admin.role-missing", "player", target);
            return false;
        }
        save();
        audit(AuditLog.MODERATION_ROLE_REMOVE, actor, target, Map.of());
        plugin.messages().send(actor, "admin.role-removed", "player", target, "actor", actor.getName());
        return true;
    }

    /* --------------------- мут, кик и бан по IP ---------------------------- */

    /** Наказания из панели доступны старшим: /mute, операторам или выданному праву. */
    public boolean canPunish(CommandSender sender) {
        return sender != null && (sender.hasPermission(ChatService.PERMISSION_MUTE) || sender.isOp());
    }

    private boolean punishAllowed(CommandSender actor) {
        if (!isStaff(actor)) {
            plugin.messages().send(actor, "admin.no-permission");
            return false;
        }
        if (!canPunish(actor)) {
            plugin.messages().send(actor, "admin.punish-denied");
            return false;
        }
        return true;
    }

    public boolean mute(CommandSender actor, String targetName, long minutes, String reason) {
        if (!punishAllowed(actor)) return false;
        String target = ModerationLedger.display(targetName);
        if (target.isEmpty() || minutes <= 0 || reason == null || reason.isBlank()) {
            plugin.messages().send(actor, "admin.mute-failed", "player", target);
            return false;
        }
        if (!plugin.chatService().applyMuteFromPanel(actor, target, minutes, reason.trim())) {
            plugin.messages().send(actor, "admin.mute-failed", "player", target);
            return false;
        }
        long now = System.currentTimeMillis();
        ledger.record(ModerationLedger.ACTION_MUTE, actor.getName(), target,
                reason.trim() + " (" + minutes + " мин.)", now);
        save();
        audit(AuditLog.MODERATION_MUTE, actor, target,
                Map.of("minutes", minutes, "reason", reason.trim()));
        return true;
    }

    public boolean unmute(CommandSender actor, String targetName) {
        if (!punishAllowed(actor)) return false;
        String target = ModerationLedger.display(targetName);
        if (!plugin.chatService().clearMuteFromPanel(actor, target)) return false;
        ledger.record(ModerationLedger.ACTION_UNMUTE, actor.getName(), target, "",
                System.currentTimeMillis());
        save();
        audit(AuditLog.MODERATION_UNMUTE, actor, target, Map.of());
        return true;
    }

    public java.util.List<RegistrationStore.MuteEntry> activeMutes() {
        return plugin.chatService().activeMutesFromPanel();
    }

    public static long muteDurationMinutes(String raw) {
        return ChatService.muteDurationMinutes(raw);
    }

    public boolean kick(CommandSender actor, String targetName, String reason) {
        if (!punishAllowed(actor)) return false;
        String target = ModerationLedger.display(targetName);
        Player online = findOnline(target);
        if (online == null) {
            plugin.messages().send(actor, "admin.player-offline", "player", target);
            return false;
        }
        String text = reason == null || reason.isBlank()
                ? plugin.messages().plain("admin.value-none") : reason.trim();
        online.kick(plugin.messages().get("admin.kick-message", "reason", text));
        ledger.record(ModerationLedger.ACTION_KICK, actor.getName(), target, text,
                System.currentTimeMillis());
        save();
        audit(AuditLog.MODERATION_KICK, actor, target, Map.of("reason", text));
        plugin.messages().send(actor, "admin.kick-applied", "player", target, "reason", text);
        return true;
    }

    public boolean ipBan(CommandSender actor, String targetName, String reason) {
        if (!punishAllowed(actor)) return false;
        String target = ModerationLedger.display(targetName);
        String ip = resolveIp(target);
        if (ip.isEmpty()) {
            plugin.messages().send(actor, "admin.ip-unknown", "player", target);
            return false;
        }
        String text = reason == null || reason.isBlank()
                ? plugin.messages().plain("admin.value-none") : reason.trim();
        try {
            BanList list = Bukkit.getBanList(BanList.Type.IP);
            if (list.isBanned(ip)) list.pardon(ip);
            list.addBan(ip, text, (Date) null, BAN_SOURCE);
        } catch (RuntimeException | LinkageError exception) {
            plugin.getLogger().log(Level.WARNING,
                    "[CHRDK_Core] Не удалось забанить IP " + ip, exception);
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (ip.equals(ipOf(player))) {
                player.kick(plugin.messages().get("admin.ip-ban-kick", "reason", text));
            }
        }
        ledger.record(ModerationLedger.ACTION_IP_BAN, actor.getName(), target,
                ip + " · " + text, System.currentTimeMillis());
        save();
        audit(AuditLog.MODERATION_IP_BAN, actor, target, Map.of("ip", ip, "reason", text));
        plugin.messages().send(actor, "admin.ip-ban-applied",
                "player", target, "ip", ip, "reason", text);
        return true;
    }

    public boolean ipUnban(CommandSender actor, String ipOrName) {
        if (!punishAllowed(actor)) return false;
        String value = ModerationLedger.display(ipOrName);
        String ip = value.contains(".") || value.contains(":") ? value : resolveIp(value);
        if (ip.isEmpty()) {
            plugin.messages().send(actor, "admin.ip-unknown", "player", value);
            return false;
        }
        try {
            Bukkit.getBanList(BanList.Type.IP).pardon(ip);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING,
                    "[CHRDK_Core] Не удалось снять IP-бан " + ip, exception);
        }
        ledger.record(ModerationLedger.ACTION_IP_UNBAN, actor.getName(), value, ip,
                System.currentTimeMillis());
        save();
        audit(AuditLog.MODERATION_IP_UNBAN, actor, value, Map.of("ip", ip));
        plugin.messages().send(actor, "admin.ip-unban-applied", "ip", ip);
        return true;
    }

    /** Причина IP-бана для профиля; пусто, если адрес не забанен. */
    public String ipBanReason(String target) {
        String ip = resolveIp(target);
        if (ip.isEmpty()) return "";
        try {
            var entry = Bukkit.getBanList(BanList.Type.IP).getBanEntry(ip);
            return entry == null || entry.getReason() == null ? "" : entry.getReason();
        } catch (RuntimeException | LinkageError ignored) {
            return "";
        }
    }

    public java.util.List<String> chatHistory(String target, int limit) {
        return plugin.chatService().readChatHistory(target, limit);
    }

    private String resolveIp(String target) {
        Player online = findOnline(target);
        String live = ipOf(online);
        if (!live.isEmpty()) return live;
        var account = plugin.store().getAccount(target).orElse(null);
        if (account == null) return "";
        if (account.lastAuthIp != null && !account.lastAuthIp.isBlank()) return account.lastAuthIp;
        if (account.sessions != null) {
            for (int i = account.sessions.size() - 1; i >= 0; i--) {
                String ip = account.sessions.get(i).ip;
                if (ip != null && !ip.isBlank()) return ip;
            }
        }
        return "";
    }

    private static String ipOf(Player player) {
        if (player == null || player.getAddress() == null || player.getAddress().getAddress() == null) return "";
        return player.getAddress().getAddress().getHostAddress();
    }

    /* ------------------------------- аудит --------------------------------- */

    private void audit(String event, CommandSender actor, String target, Map<String, Object> details) {
        AuditLog log = plugin.auditLog();
        if (log == null) return;
        String ip = "";
        if (actor instanceof Player player && player.getAddress() != null
                && player.getAddress().getAddress() != null) {
            ip = player.getAddress().getAddress().getHostAddress();
        }
        Map<String, Object> all = new LinkedHashMap<>();
        if (details != null) all.putAll(details);
        all.put("actor", actor.getName());
        try {
            log.log(event, actor.getName(), target, ip, all);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING,
                    "[CHRDK_Core] Не удалось записать событие аудита " + event, exception);
        }
    }
}
