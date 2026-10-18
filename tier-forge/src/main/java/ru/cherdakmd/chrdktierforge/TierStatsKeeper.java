package ru.cherdakmd.chrdktierforge;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Статистика находок по тирам (для {@code /tiers stats}): сколько раз каждый тир
 * выпал конкретному игроку (крафт, дроп, опознание).
 *
 * <p>С 1.4.81 счётчики живут не только в памяти: {@link #writeTo(ConfigurationSection)}
 * и {@link #load(ConfigurationSection)} сериализуют их в {@code stats.yml}, который
 * плагин пишет атомарно и асинхронно. Счётчики потокобезопасны (пишутся из игровых
 * событий, читаются командой и фоновым сейвом).
 */
public final class TierStatsKeeper {

    private final ConcurrentHashMap<UUID, Map<String, Integer>> byPlayer = new ConcurrentHashMap<>();

    public void count(UUID player, String tierId) {
        if (player == null || !TierDef.isTier(tierId)) {
            return;
        }
        Map<String, Integer> row = byPlayer.computeIfAbsent(player, ignored -> new ConcurrentHashMap<>());
        row.merge(tierId, 1, Integer::sum);
    }

    public int totalOf(UUID player, String tierId) {
        Map<String, Integer> row = byPlayer.get(player);
        return row == null ? 0 : row.getOrDefault(tierId, 0);
    }

    public Map<String, Integer> row(UUID player) {
        Map<String, Integer> row = byPlayer.get(player);
        return row == null ? Map.of() : new HashMap<>(row);
    }

    /** Общее количество выпадений игрока (по всем тирам). */
    public int total(UUID player) {
        return row(player).values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Серверное суммарное количество выпадений (все игроки, все тиры). */
    public int grandTotal() {
        return byPlayer.values().stream()
                .flatMap(row -> row.values().stream())
                .mapToInt(Integer::intValue)
                .sum();
    }

    /** Сколько игроков вообще имеет счётчики (для лога загрузки). */
    public int players() {
        return byPlayer.size();
    }

    public void reset(UUID player) {
        byPlayer.remove(player);
    }

    /** Глубокая копия всех счётчиков (для тестов/диагностики, без живых ссылок). */
    public Map<UUID, Map<String, Integer>> snapshot() {
        Map<UUID, Map<String, Integer>> copy = new LinkedHashMap<>();
        byPlayer.forEach((player, row) -> copy.put(player, new HashMap<>(row)));
        return copy;
    }

    /**
     * Запись в секцию {@code stats.yml}: игрок → тир → счётчик. Тиры пишутся в
     * порядке лестницы, игроки — по UUID, чтобы файл не «прыгал» между сейвами.
     */
    public void writeTo(ConfigurationSection root) {
        if (root == null) {
            return;
        }
        List<UUID> players = new ArrayList<>(byPlayer.keySet());
        players.sort(Comparator.comparing(UUID::toString));
        for (UUID player : players) {
            Map<String, Integer> row = byPlayer.get(player);
            if (row == null || row.isEmpty()) {
                continue;
            }
            ConfigurationSection section = root.createSection(player.toString());
            for (String tier : TierDef.LADDER) {
                int count = row.getOrDefault(tier, 0);
                if (count > 0) {
                    section.set(tier, count);
                }
            }
        }
    }

    /**
     * Загрузка из секции {@code stats.yml}. Неизвестные тиры, битые UUID и
     * неположительные значения игнорируются — файл мог быть испорчен вручную.
     */
    public void load(ConfigurationSection root) {
        if (root == null) {
            return;
        }
        for (String rawPlayer : root.getKeys(false)) {
            UUID player;
            try {
                player = UUID.fromString(rawPlayer);
            } catch (IllegalArgumentException exception) {
                continue;
            }
            ConfigurationSection section = root.getConfigurationSection(rawPlayer);
            if (section == null) {
                continue;
            }
            for (String tier : TierDef.LADDER) {
                int count = section.getInt(tier, 0);
                if (count > 0) {
                    byPlayer.computeIfAbsent(player, ignored -> new ConcurrentHashMap<>())
                            .merge(tier, count, Integer::sum);
                }
            }
        }
    }
}
