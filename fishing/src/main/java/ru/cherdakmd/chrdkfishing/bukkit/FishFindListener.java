package ru.cherdakmd.chrdkfishing.bukkit;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.plugin.Plugin;
import ru.cherdakmd.chrdkfishing.FishSaleJournal;

/** Rare optional resource on an actual finalized custom catch, never an extra Skills XP award. */
final class FishFindListener implements Listener {
    private static final Material[] FINDS = { Material.AMETHYST_SHARD, Material.PRISMARINE_SHARD, Material.IRON_NUGGET };
    private final Plugin plugin; private final FishSaleEngine engine; private final FishItemCodec codec;
    FishFindListener(Plugin plugin, FishSaleEngine engine, FishItemCodec codec) {
        this.plugin = Objects.requireNonNull(plugin); this.engine = Objects.requireNonNull(engine); this.codec = Objects.requireNonNull(codec);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void fish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(event.getCaught() instanceof Item caught)
                || ThreadLocalRandom.current().nextInt(100) != 0) return;
        Player player = event.getPlayer();
        if (!engine.available(player) || (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE)) return;
        var token = codec.read(caught.getItemStack()).orElse(null); if (token == null) return;
        Material material = FINDS[ThreadLocalRandom.current().nextInt(FINDS.length)];
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (event.isCancelled() || !engine.available(player) || codec.read(caught.getItemStack()).filter(token::equals).isEmpty()) return;
            try {
                var result = engine.awardFind(player, token, material);
                if (result == FishSaleJournal.Result.DONE) player.sendMessage("§aВ улове обнаружен редкий материал: " + material.name() + ".");
                else if (result == FishSaleJournal.Result.WAIT) player.sendMessage("§eДля находки нужно свободное место; при проблеме обратитесь к администрации.");
            } catch (Exception error) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Ошибка сохранения находки; сохраните журнал рыбалки", error);
                player.sendMessage("§cНаходка ожидает сверки. Обратитесь к администрации.");
            }
        });
    }
}
