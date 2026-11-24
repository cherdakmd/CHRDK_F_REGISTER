package ru.cherdakmd.chrdkfishing.bukkit;

import java.util.Objects;
import java.util.function.Supplier;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;

/** Registered entry point; without a fully verified adapter this is inert. */
final class FishCatchRouter implements Listener {
    private final Supplier<FishCatchListener> active;
    FishCatchRouter(Supplier<FishCatchListener> active) { this.active = Objects.requireNonNull(active); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event == null || event.isCancelled()) return;
        FishCatchListener delegate = active.get();
        if (delegate != null) delegate.onFish(event);
    }
}
