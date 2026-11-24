package ru.cherdakmd.chrdkfishing.bukkit;

import java.util.Objects;
import java.util.function.Predicate;
import org.bukkit.GameMode;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.plugin.Plugin;
import ru.cherdakmd.chrdkfishing.FishAlbum;

/** Records final uncancelled catch events, never consuming the fish. */
final class FishAlbumListener implements Listener {
    private final Plugin plugin;
    private final FishAlbum album;
    private final FishItemCodec codec;
    private final Predicate<Player> authenticated;
    FishAlbumListener(Plugin plugin, FishAlbum album, FishItemCodec codec, Predicate<Player> authenticated) {
        this.plugin = Objects.requireNonNull(plugin); this.album = Objects.requireNonNull(album);
        this.codec = Objects.requireNonNull(codec); this.authenticated = Objects.requireNonNull(authenticated);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event == null || event.isCancelled() || event.getState() != PlayerFishEvent.State.CAUGHT_FISH
                || !(event.getCaught() instanceof Item item)) return;
        Player player = event.getPlayer();
        if (player == null || !player.isOnline() || player.hasMetadata("NPC") || !authenticated.test(player)
                || (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE)) return;
        var token = codec.read(item.getItemStack());
        if (token.isEmpty()) return;
        try {
            if (album.record(player.getUniqueId(), token.get(), System.currentTimeMillis()) == FishAlbum.Result.CONFLICT)
                throw new IllegalStateException("conflicting fish identity");
        } catch (Exception error) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Улов не записан в альбом; сохраните fishing-album.db и журнал", error);
            player.sendMessage("§cУлов получен, но альбом недоступен. Обратитесь к администрации; не выбрасывайте рыбу.");
        }
    }
}
