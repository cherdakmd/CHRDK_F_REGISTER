package ru.cherdakmd.chrdkclaims;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Личный вход именно в связную собственную территорию; не зависит от текстовых уведомлений. */
final class ClaimBorderEntryTracker {
    private record Spot(String world, String owner, int x, int z) { }
    private final Map<UUID, Spot> last = new HashMap<>();

    boolean entered(UUID id, String name, Claim current, ClaimBorders.Lookup lookup) {
        if (current == null || !current.isOwner(name)) {
            clear(id);
            return false;
        }
        Spot before = last.put(id, new Spot(current.world, current.owner, current.x, current.z));
        if (before == null || !before.world().equals(current.world)
                || !before.owner().equalsIgnoreCase(current.owner)) return true;
        long distance = Math.abs((long) before.x() - current.x) + Math.abs((long) before.z() - current.z);
        // Соседние по стороне свои чанки всегда смержены. Для телепорта проверяем всю связность.
        if (distance <= 1) {
            Claim previous = lookup.at(before.x(), before.z());
            return previous == null || !previous.world.equals(current.world) || !previous.isOwner(name);
        }
        return !ClaimBorders.outline(current.x, current.z, lookup).contains(before.x(), before.z());
    }
    void clear(UUID id) { last.remove(id); }
    void clearAll() { last.clear(); }
    int tracked() { return last.size(); }
}
