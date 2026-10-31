package ru.cherdakmd.chrdkartifacts;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Очередь главного потока: любое число событий игрока объединяется в один пересчёт. */
final class ArtifactRefreshQueue {
    private final Set<UUID> players = new LinkedHashSet<>();

    void request(UUID player) { players.add(player); }
    void forget(UUID player) { players.remove(player); }
    Set<UUID> drain() {
        Set<UUID> result = Set.copyOf(players);
        players.clear();
        return result;
    }
}
