package ru.cherdakmd.chrdkfishing.bukkit;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import ru.cherdakmd.chrdkfishing.BaitCatalog;

/** A reserved charge is released for cancelled/unsuccessful casts; crash ambiguity stays quarantined. */
final class BaitCatchSession implements Listener {
    private record Session(UUID owner, UUID attempt, BaitCatalog.Bait bait) { }
    private final Plugin plugin;
    private final BaitPurchaseEngine engine;
    private final FishItemCodec fish;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final java.util.Set<UUID> finishing = new java.util.HashSet<>();
    BaitCatchSession(Plugin plugin, BaitPurchaseEngine engine, FishItemCodec fish) {
        this.plugin = java.util.Objects.requireNonNull(plugin); this.engine = java.util.Objects.requireNonNull(engine);
        this.fish = java.util.Objects.requireNonNull(fish);
    }
    BaitCatalog.Bait bait(PlayerFishEvent event) {
        var session = sessions.get(event.getHook().getUniqueId());
        return session != null && session.owner().equals(event.getPlayer().getUniqueId()) ? session.bait() : null;
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void cast(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.FISHING || !engine.available(event.getPlayer())) return;
        UUID hook = event.getHook().getUniqueId(), owner = event.getPlayer().getUniqueId(), attempt = UUID.randomUUID();
        if (sessions.containsKey(hook)) return;
        try {
            BaitCatalog.Bait bait = engine.reserve(event.getPlayer(), attempt);
            if (bait == null) return;
            sessions.put(hook, new Session(owner, attempt, bait));
            if (bait.effect() == BaitCatalog.Effect.WAIT_TIME) {
                int min = event.getHook().getMinWaitTime(), max = event.getHook().getMaxWaitTime();
                int newMin = (int) Math.max(1L, (long) min * (100 - bait.bonusPercent()) / 100);
                int newMax = (int) Math.max(newMin, (long) max * (100 - bait.bonusPercent()) / 100);
                event.getHook().setWaitTime(newMin, newMax);
            }
            Bukkit.getScheduler().runTask(plugin, () -> { if (event.isCancelled()) finish(hook, false); });
        } catch (Exception error) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Ошибка подготовки приманки; сохраните журнал", error);
            // If the DB transition succeeded before an exception, do not manufacture an unverified refund.
            if (sessions.containsKey(hook)) finish(hook, false);
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void end(PlayerFishEvent event) {
        if (event.getState() == PlayerFishEvent.State.FISHING || event.getState() == PlayerFishEvent.State.BITE
                || event.getState() == PlayerFishEvent.State.LURED) return;
        UUID hook = event.getHook().getUniqueId();
        if (!sessions.containsKey(hook) || !finishing.add(hook)) return;
        Bukkit.getScheduler().runTask(plugin, () -> finish(hook,
                !event.isCancelled() && event.getState() == PlayerFishEvent.State.CAUGHT_FISH && isFish(event)));
    }
    @EventHandler public void quit(PlayerQuitEvent event) {
        UUID owner = event.getPlayer().getUniqueId();
        for (UUID hook : sessions.entrySet().stream().filter(row -> row.getValue().owner().equals(owner) && !finishing.contains(row.getKey())).map(Map.Entry::getKey).toList())
            finish(hook, false);
    }
    private boolean isFish(PlayerFishEvent event) {
        if (!(event.getCaught() instanceof Item caught)) return false;
        var item = caught.getItemStack();
        if (fish.read(item).isPresent()) return true;
        if (item == null || item.hasItemMeta()) return false;
        Material type = item.getType();
        return type == Material.COD || type == Material.SALMON || type == Material.TROPICAL_FISH || type == Material.PUFFERFISH;
    }
    private void finish(UUID hook, boolean success) {
        finishing.remove(hook);
        Session session = sessions.remove(hook);
        if (session == null) return;
        try {
            if (!engine.finish(session.owner(), session.attempt(), success)) throw new IllegalStateException("bait reservation mismatch");
        } catch (Exception error) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Приманка в карантине: " + session.owner(), error);
        }
    }
}
