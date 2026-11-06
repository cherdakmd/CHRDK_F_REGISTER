package ru.cherdakmd.chrdksocial;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import ru.cherdakmd.chrdkvk.VkPlugin;
import ru.cherdakmd.chrdkvk.VkService;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Единый анонсатор событий сервера в сообщество: смерть персонажа, запуск и
 * остановка сервера, приветствие новых игроков.
 *
 * <p>Один слушатель на все события, один набор флагов и один кулдаун вместо
 * разрозненных рассылок. Исходящие строки проходят через готовые транспорты:
 * беседа ВК — {@link VkService#sendEventToGroupChat(String)} (с общим
 * батчером против VK flood control), MAX — {@link MaxService#sendToChat(long, String)}.
 * Каждое отправленное событие фиксируется в {@code logs/social/*.jsonl}.</p>
 *
 * <p>Приватность: в сообщество уходит только безопасный ник и нейтральный
 * текст из {@code messages.yml}. Ни координат, ни IP, ни UUID, ни причины
 * смерти, ни содержимого {@code getDeathMessage()}.</p>
 */
public final class SocialEventAnnouncer implements Listener {

    public enum Kind { DEATH, SERVER_START, SERVER_STOP, WELCOME }

    private final VkPlugin plugin;
    private volatile SocialEventConfig config;
    private final SocialEventThrottle throttle = new SocialEventThrottle(System::currentTimeMillis);
    private final Set<UUID> welcomed = ConcurrentHashMap.newKeySet();
    private volatile boolean stopped;

    public SocialEventAnnouncer(VkPlugin plugin, SocialEventConfig config) {
        this.plugin = plugin;
        this.config = config == null ? SocialEventConfig.DEFAULT : config;
    }

    public void applyConfig(SocialEventConfig next) {
        this.config = next == null ? SocialEventConfig.DEFAULT : next;
        throttle.clear();
        welcomed.clear();
    }

    public SocialEventConfig config() {
        return config;
    }

    public void shutdown() {
        stopped = true;
        welcomed.clear();
        throttle.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!active(config.playerDeath())) return;
        String player = SocialEventText.safePlayer(event.getEntity().getName());
        if (player.isEmpty()) return;
        String text = plugin.messages().plain("bot.event-death", "player", player);
        publish(Kind.DEATH, player, text);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!active(config.welcomeNewPlayer())) return;
        Player player = event.getPlayer();
        if (player.hasPlayedBefore() || !welcomed.add(player.getUniqueId())) return;
        String name = SocialEventText.safePlayer(player.getName());
        if (name.isEmpty()) return;
        String text = plugin.messages().plain("bot.event-welcome", "player", name);
        publish(Kind.WELCOME, name, text);
    }

    /** Вызывается с задержкой после включения: клиенты VK/MAX уже подняты. */
    public void onServerStarted() {
        if (!active(config.serverStart())) return;
        int online = Bukkit.getOnlinePlayers().size();
        int max = Bukkit.getMaxPlayers();
        String text = plugin.messages().plain("bot.event-server-start",
                "online", online, "max", max);
        publish(Kind.SERVER_START, "", text);
    }

    /** Вызывается в onDisable ДО остановки сетевых сервисов. */
    public void onServerStopping() {
        if (!active(config.serverStop())) return;
        String text = plugin.messages().plain("bot.event-server-stop");
        publish(Kind.SERVER_STOP, "", text);
    }

    private boolean active(boolean specificFlag) {
        SocialEventConfig cfg = config;
        return !stopped && cfg.enabled() && cfg.anyEnabled() && specificFlag;
    }

    private void publish(Kind kind, String player, String text) {
        SocialEventConfig cfg = config;
        String safe = SocialEventText.limit(text, cfg.maxLength());
        if (safe.isEmpty() || stopped) return;
        if (!throttle.allow(kind.name(), player, cfg.cooldownMillis())) return;

        boolean vk = false;
        boolean max = false;
        VkService vkService = plugin.vkService();
        if (cfg.toVk() && vkService != null) {
            vk = vkService.sendEventToGroupChat(safe);
        }
        MaxService maxService = plugin.maxService();
        if (cfg.toMax() && cfg.maxChatId() > 0L && maxService != null) {
            max = maxService.sendToChat(cfg.maxChatId(), safe);
        }
        if (cfg.announceInGame()) {
            Bukkit.broadcastMessage("§b[CHERDAK] §f" + safe);
        }
        SocialService social = plugin.socialService();
        if (social != null) {
            social.audit().write("social.event." + kind.name().toLowerCase(Locale.ROOT),
                    player, "events", "vk=" + vk + ", max=" + max);
        }
    }
}
