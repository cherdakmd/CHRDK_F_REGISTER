package ru.cherdakmd.chrdkregister;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Bukkit-граница независимых файлов nicknames.yml, anti-swear.yml и anti-spam.yml. */
final class ChatModerationService implements Listener {
    private final RegisterPlugin plugin;
    private final Supplier<Messages> messages;
    private final Predicate<Player> authenticated;
    private volatile ChatModerationPolicy policy;

    ChatModerationService(RegisterPlugin plugin, Supplier<Messages> messages, Predicate<Player> authenticated) {
        this.plugin = plugin; this.messages = messages; this.authenticated = authenticated;
        reload();
    }

    final void reload() {
        try {
            ChatModerationPolicy loaded = new ChatModerationPolicy(load("nicknames.yml"),
                    load("anti-swear.yml"), load("anti-spam.yml"));
            policy = loaded;
        } catch (RuntimeException error) {
            // При ошибке reload прежний полный снимок продолжает действовать; чат не остаётся без защиты.
            plugin.getLogger().severe("Не удалось загрузить moderation configs: " + error.getMessage());
            if (policy == null) throw error;
        }
    }

    private YamlConfiguration load(String name) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.isFile()) plugin.saveResource(name, false);
        return YamlConfiguration.loadConfiguration(file);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED
                || policy.isNicknameAllowed(event.getName())) return;
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                messages.get().get("moderation.nickname-blocked"));
    }

    /** LOW оставляет LOWEST-слушателю GUI возможность сначала забрать служебный ввод. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!authenticated.test(event.getPlayer())) return;
        String raw = PlainTextComponentSerializer.plainText().serialize(event.message());
        ChatModerationPolicy.Decision decision = moderate(event.getPlayer(), raw,
                ChatModerationPolicy.Channel.CHAT, System.currentTimeMillis());
        if (decision.status() == ChatModerationPolicy.Status.BLOCK) {
            event.setCancelled(true);
            messages.get().send(event.getPlayer(), decision.messageKey());
        } else if (!raw.equals(decision.text())) {
            event.message(Component.text(decision.text()));
        }
    }

    ChatModerationPolicy.Decision moderate(Player player, String text,
            ChatModerationPolicy.Channel channel, long now) {
        ChatModerationPolicy current = policy;
        return current.moderate(player.getUniqueId(), text, channel,
                has(player, current, true), has(player, current, false), now);
    }

    private static boolean has(Player player, ChatModerationPolicy policy, boolean swear) {
        // Доступ к снимкам остаётся в policy; отсутствие permission означает отсутствие обхода.
        String permission = policy.bypassPermission(swear);
        return permission != null && !permission.isBlank() && player.hasPermission(permission);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) { policy.forget(event.getPlayer().getUniqueId()); }
}
