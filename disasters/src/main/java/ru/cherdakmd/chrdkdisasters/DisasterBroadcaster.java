package ru.cherdakmd.chrdkdisasters;

import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Один общий кулдаун на игрока для всех оповещений о катастрофах — та же
 * политика, что у напоминаний CHRDK_VK: игрок не должен получать подряд
 * сообщения от разных катастроф и разных таймеров. Названия всех шести видов
 * берутся из messages.yml по ключу {@code kind-<id>}, поэтому добавление
 * катастрофы не требует правки этого класса.
 */
public final class DisasterBroadcaster {

    private final DisastersPlugin plugin;
    private final Map<String, Long> lastAt = new HashMap<>();

    public DisasterBroadcaster(DisastersPlugin plugin) {
        this.plugin = plugin;
    }

    public void announce(DisasterKind kind, int level, Collection<? extends Player> players) {
        announce(kind, level, players, false);
    }

    public void announce(DisasterKind kind, int level,
                         Collection<? extends Player> players, boolean force) {
        long now = System.currentTimeMillis();
        long pause = plugin.settings().announceCooldownSeconds() * 1000L;
        for (Player player : players) {
            if (player == null || !player.isOnline()) {
                continue;
            }
            String key = DisasterScheduler.canonical(player.getName());
            Long last = lastAt.get(key);
            if (!force && last != null && now - last < pause) {
                continue;
            }
            lastAt.put(key, now);
            plugin.messages().send(player, "start",
                    "kind", title(kind), "level", level);
        }
        if (lastAt.size() > 4096) {
            lastAt.entrySet().removeIf(entry -> now - entry.getValue() > pause * 4L);
        }
    }

    /** Название катастрофы лежит в messages.yml, а не в коде. */
    private String title(DisasterKind kind) {
        return plugin.messages().raw(kind.titleKey());
    }

    public void forget(String player) {
        lastAt.remove(DisasterScheduler.canonical(player));
    }
}
