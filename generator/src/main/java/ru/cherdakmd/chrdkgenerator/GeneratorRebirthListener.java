package ru.cherdakmd.chrdkgenerator;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import ru.cherdakmd.chrdkregister.api.events.RebirthEvent;

/** Записывает номер события; сброс восстанавливается после перезапуска по Skills API. */
public final class GeneratorRebirthListener implements Listener {
    private final GeneratorPlugin plugin;
    public GeneratorRebirthListener(GeneratorPlugin plugin) { this.plugin = plugin; }

    @EventHandler
    public void onRebirth(RebirthEvent event) {
        var player = event.getPlayer();
        if (plugin.store().entry(player.getUniqueId()) == null) return;
        try {
            if (!plugin.store().requestSkillRebirth(player.getUniqueId(), event.getCount())
                    || !plugin.containers().recoverPlayer(player)
                    || !plugin.containers().syncRebirth(player.getUniqueId(), player, System.currentTimeMillis())) {
                plugin.getLogger().warning("Перерождение генератора ожидает восстановления: " + player.getUniqueId());
                return;
            }
            var entry = plugin.store().entry(player.getUniqueId());
            double bonus = GeneratorMath.rebirthBonusPercent(plugin.config().rebirthPercentPerRebirth(),
                    plugin.config().rebirthDecay(), plugin.config().rebirthBonusCapPercent(), entry.rebirths());
            plugin.messages().send(player, "rebirth-done", "bunker", "0", "count", String.valueOf(event.getCount()),
                    "bonus", String.format(java.util.Locale.ROOT, "%.1f", bonus));
        } catch (java.io.IOException | RuntimeException error) {
            plugin.getLogger().warning("Перерождение генератора ожидает восстановления: " + error.getMessage());
        }
    }
}
