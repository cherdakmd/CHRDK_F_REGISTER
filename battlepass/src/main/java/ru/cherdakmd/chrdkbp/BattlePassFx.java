package ru.cherdakmd.chrdkbp;

import org.bukkit.Sound;
import org.bukkit.entity.Player;

/**
 * Короткие звуки и подсказки над хотбаром. Включаются и выключаются в
 * {@code battlepass.display} (звуки/actionbar) — на «тихих» серверах их убирают.
 *
 * <p>Класс тонкий: вся логика уже случилась выше, здесь только обратная связь.
 * Ошибки Bukkit-вызовов глушим, чтобы отсутствие звука никогда не ломало выдачу
 * награды.</p>
 */
final class BattlePassFx {

    private final BattlePassPlugin plugin;

    BattlePassFx(BattlePassPlugin plugin) {
        this.plugin = plugin;
    }

    /** Задание только что выполнено. */
    void completed(Player player) {
        play(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.4f);
        actionbar(player, "actionbar-completed");
    }

    /** Награда получена (часы буста, с 1.4.184). */
    void claimed(Player player, int hours) {
        play(player, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.2f);
        if (!plugin.settings().actionbar()) {
            return;
        }
        player.sendActionBar(plugin.messages().get("actionbar-claimed", "hours", hours));
    }

    /** Открылась новая волна. */
    void wave(Player player, int week) {
        play(player, Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.0f);
        actionbar(player, "actionbar-wave", "week", week);
    }

    private void actionbar(Player player, String key, Object... placeholders) {
        if (plugin.settings().actionbar()) {
            player.sendActionBar(plugin.messages().get(key, placeholders));
        }
    }

    private void play(Player player, Sound sound, float volume, float pitch) {
        if (!plugin.settings().sounds()) {
            return;
        }
        try {
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (Throwable ignored) {
            // версия сервера без нужного звука — не повод падать
        }
    }
}
