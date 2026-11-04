package ru.cherdakmd.chrdkregister;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;

/**
 * Страховка входа: если имя есть в нашем зеркале {@link org.bukkit.BanList}
 * или в {@code moderation.json}, вход отклоняется с причиной из панели.
 *
 * <p>Ванильный BanList обычно срабатывает и сам, но сообщение тогда общее;
 * здесь мы показываем ту же причину, что администратор видит в GUI.</p>
 */
public final class ModerationListener implements Listener {

    private final RegisterPlugin plugin;
    private final ModerationService service;

    public ModerationListener(RegisterPlugin plugin, ModerationService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onLogin(PlayerLoginEvent event) {
        if (!service.enabled()) return;
        String name = event.getPlayer().getName();
        if (!service.isBanned(name)) return;
        event.disallow(PlayerLoginEvent.Result.KICK_BANNED,
                plugin.messages().get("admin.ban-login", "reason", service.banReason(name)));
    }
}
