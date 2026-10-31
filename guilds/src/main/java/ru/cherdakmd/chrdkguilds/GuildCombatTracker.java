package ru.cherdakmd.chrdkguilds;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Короткий запрет изменения состава/режима после настоящего PvP. Только главный поток. */
final class GuildCombatTracker {
    static final long LOCK = 30_000;
    private final Map<UUID,Long> until=new HashMap<>();
    void mark(UUID player,long now) { until.put(player,Math.addExact(now,LOCK)); }
    boolean blocked(UUID player,long now) { return now<until.getOrDefault(player,0L); }
    void prune(long now) { until.entrySet().removeIf(e->e.getValue()<=now); }
}
