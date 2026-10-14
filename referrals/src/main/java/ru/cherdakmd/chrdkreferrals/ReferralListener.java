package ru.cherdakmd.chrdkreferrals;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import ru.cherdakmd.chrdkregister.api.events.PassGrantedEvent;

/**
 * Слушатель: выплата бонуса при первой оплате приглашённого (PassGrantedEvent ядра, главный
 * поток) и одноразовая подсказка новичку на 5-й секунде после входа.
 */
public final class ReferralListener implements Listener {

    private final ReferralsPlugin plugin;
    private final ReferralService service;

    public ReferralListener(ReferralsPlugin plugin, ReferralService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPassGranted(PassGrantedEvent event) {
        if (ReferralRules.triggersBonus(event.getSource())) service.onPassGranted(event.getPlayerName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAuthenticated(ru.cherdakmd.chrdkregister.api.events.PlayerAuthCompletedEvent event) {
        if (service.shouldHint(event.getPlayer().getName())) plugin.messages().send(event.getPlayer(), "ref.join-hint");
    }
}
