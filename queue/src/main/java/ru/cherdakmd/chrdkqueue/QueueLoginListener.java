package ru.cherdakmd.chrdkqueue;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Locale;

/**
 * Входная точка: решение о допуске на переполненном сервере.
 *
 * <p>Логика: VIP (группа LuckPerms из vip-groups или vip-permission) заходит
 * всегда — вплоть до жёсткого max-players (его контролирует сам Bukkit).
 * Обычный игрок — через {@link QueueService}: свободный слот сразу или очередь
 * с номером места. Денай-сообщения игрок получает как экран дисконнекта.</p>
 */
public final class QueueLoginListener implements Listener {

    private final QueuePlugin plugin;

    public QueueLoginListener(QueuePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        QueueConfig cfg = plugin.queueService().config();
        if (!cfg.enabled()) {
            return;
        }
        // Профилактика завышений: количество онлайн читаем асинхронно (безопасно для size())
        int onlineNow = Bukkit.getOnlinePlayers().size();

        if (isVip(event)) {
            if (cfg.debugLog()) {
                plugin.getLogger().info("[Queue] VIP-вход: " + event.getName() + " (онлайн " + onlineNow + ").");
            }
            return;
        }

        QueueService.Verdict verdict = plugin.queueService().decide(event.getName(), onlineNow);
        switch (verdict.decision()) {
            case ALLOW -> {
                // решения ниже не требуют сообщений — игрок пройдёт
            }
            case DENY_FULL -> event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_FULL,
                    plugin.messages().get("kick-full",
                            "online", verdict.online(),
                            "limit", cfg.regularLimit(),
                            "position", verdict.position(),
                            "reserve", cfg.reservationSeconds()));
            case DENY_RESERVED -> event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_FULL,
                    plugin.messages().get("kick-reserved",
                            "position", verdict.position()));
            case DENY_OVERFULL -> event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_FULL,
                    plugin.messages().get("kick-overfull", "max", cfg.maxQueue()));
        }
        if (cfg.debugLog() && verdict.decision() != QueueService.Decision.ALLOW) {
            plugin.getLogger().info("[Queue] Отказ: " + event.getName() + " -> " + verdict.decision()
                    + " (место " + verdict.position() + ", онлайн " + verdict.online() + ")");
        }
    }

    /** После входа игрока недожатые очередные остатки сгорают (страховочно, и для VIP тоже). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        plugin.queueService().consumeJoin(event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player leaving = event.getPlayer();
        // после выхода плеера Bukkit ещё учитывает его как онлайн? нет — но на всякий случай считаем минуем пару тиков через commandQueue
        int onlineAfter = Math.max(0, Bukkit.getOnlinePlayers().size() - 1);
        plugin.queueService().maybeReserveNext(onlineAfter);
        if (plugin.queueService().config().debugLog()) {
            String reserved = plugin.queueService().reservedFor();
            if (reserved != null) {
                plugin.getLogger().info("[Queue] " + leaving.getName() + " вышел; слот зарезервирован за " + reserved + ".");
            }
        }
    }

    /* ---------------------------------------------------------------- *
     *  LuckPerms: VIP-группы/право
     * ---------------------------------------------------------------- */

    private boolean isVip(AsyncPlayerPreLoginEvent event) {
        QueueConfig cfg = plugin.queueService().config();
        LuckPerms luckPerms = plugin.luckPerms();
        if (luckPerms == null) {
            return false; // без LP все игроки — обычные
        }
        try {
            User user = luckPerms.getUserManager().loadUser(event.getUniqueId()).join();
            if (user == null) {
                return false;
            }
            if (cfg.useVipPermission() && !cfg.vipPermission().isBlank()
                    && user.getCachedData().getPermissionData().checkPermission(cfg.vipPermission()).asBoolean()) {
                return true;
            }
            if (cfg.vipGroups().isEmpty()) {
                return false;
            }
            for (Group group : user.getInheritedGroups(user.getQueryOptions())) {
                if (cfg.vipGroups().contains(group.getName().toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        } catch (Exception exception) {
            plugin.getLogger().warning("[Queue] Ошибка проверки VIP-группы " + event.getName()
                    + ": " + exception.getMessage());
        }
        return false;
    }
}
