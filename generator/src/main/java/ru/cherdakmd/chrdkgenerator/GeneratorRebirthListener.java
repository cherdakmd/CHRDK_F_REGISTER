package ru.cherdakmd.chrdkgenerator;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import ru.cherdakmd.chrdkregister.api.events.RebirthEvent;

import java.util.Random;

/**
 * Перерождение персонажа сбрасывает и генератор (п.31, §8): тиры и модули
 * уходят на старт, ядро и бинд остаются, бункер выдаётся предметами до
 * сброса. Взамен счётчик перерождений растёт — бонус производства
 * с убывающей отдачей (+5% за перерождение, до +50%).
 */
public final class GeneratorRebirthListener implements Listener {

    private final GeneratorPlugin plugin;
    private final Random random = new Random();

    public GeneratorRebirthListener(GeneratorPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onRebirth(RebirthEvent event) {
        Player player = event.getPlayer();
        GeneratorStore.Entry before = plugin.store().entry(player.getUniqueId());
        if (before == null) {
            return;
        }
        long units = plugin.store().rebirth(player.getUniqueId());
        if (units > 0) {
            GeneratorHandouts.giveBunker(player, plugin.config(), before, units, random);
        }
        double bonus = GeneratorMath.rebirthBonusPercent(
                plugin.config().rebirthPercentPerRebirth(), plugin.config().rebirthDecay(),
                plugin.config().rebirthBonusCapPercent(), before.rebirths() + 1);
        plugin.messages().send(player, "rebirth-done",
                "bunker", String.valueOf(Math.max(0L, units)),
                "count", String.valueOf(event.getCount()),
                "bonus", String.format(java.util.Locale.ROOT, "%.1f", bonus));
    }
}
