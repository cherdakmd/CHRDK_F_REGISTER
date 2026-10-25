package ru.cherdakmd.chrdkachievements;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Раздел достижений в {@code /profile} (1.4.156).
 *
 * <p>Коллекция — главное, чем хвастаются в профиле: сколько открыто из общего
 * числа, сколько наград получено и какой бейдж выбран. Данные читаются по
 * UUID, поэтому работают и для оффлайн-игрока.</p>
 */
final class AchievementsProfile implements ChrdkRegisterApi.ProfileProvider {

    private final AchievementsPlugin plugin;

    AchievementsProfile(AchievementsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public ChrdkRegisterApi.ProfileSection section(ChrdkRegisterApi.ProfileContext context) {
        if (context == null || plugin.store() == null) {
            return null;
        }
        String key = context.playerId().toString();
        Set<String> unlocked = plugin.store().unlocked(key);
        if (unlocked.isEmpty()) {
            return null;
        }
        Messages messages = plugin.messages();
        List<Component> lines = new ArrayList<>();
        lines.add(messages.get("profile-progress",
                "unlocked", String.valueOf(unlocked.size()),
                "total", String.valueOf(plugin.service().total())));
        lines.add(messages.get("profile-claimed",
                "claimed", String.valueOf(plugin.store().claimed(key).size())));
        String badge = plugin.store().badge(key);
        if (badge != null && !badge.isBlank()) {
            lines.add(messages.get("profile-badge",
                    "badge", Messages.trusted(messages.raw("badge." + badge))));
        }
        return new ChrdkRegisterApi.ProfileSection(messages.raw("profile-title"),
                Material.ENCHANTED_BOOK, lines);
    }
}
