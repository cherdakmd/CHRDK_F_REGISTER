package ru.cherdakmd.chrdktreasures;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Random;

/**
 * Находка на земле (1.4.181): добыча рассыпается у ног, без сундука.
 *
 * <p>Бочки на каждую находку оказались слишком шумными: игроки жаловались, что
 * сундуки «спамят» и отвлекают от игры. Теперь клад — это горсть предметов,
 * которая падает на месте добытого блока, коротко блестит и звучит. Подобрать
 * их можно сразу: задержка подбора нулевая.</p>
 *
 * <p>Первые {@code drops.owner-seconds} секунд добычу может поднять только
 * нашедший — иначе в толпе у чужих находок моментально находились «помощники».
 * Потом привязка снимается: если игрок ушёл или у него полный рюкзак, добыча
 * не пропадёт молча и не останется «ничьей» навсегда.</p>
 *
 * <p>Разброс и подбрасывание ограничены конфигом: предметы не должны разлетаться
 * в лаву или за край обрыва. Все ошибки мира глушатся — находка не может
 * сорвать событие добычи, лучше выдать её в инвентарь, чем потерять.</p>
 */
public final class TreasureDrops {

    private final TreasuresPlugin plugin;
    private final Random random = new Random();

    public TreasureDrops(TreasuresPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Рассыпает добычу вокруг точки находки.
     *
     * @return сколько предметов легло на землю; 0 — добычу надо выдать в инвентарь
     */
    public int drop(Player player, Location origin, List<ItemStack> payload) {
        if (player == null || origin == null || payload == null || payload.isEmpty()) {
            return 0;
        }
        World world = origin.getWorld();
        if (world == null) {
            return 0;
        }
        TreasuresConfig.Drops settings = plugin.config() == null
                ? TreasuresConfig.Drops.INVENTORY : plugin.config().drops();
        Location center = origin.clone().add(0.5, 0.6, 0.5);
        String key = player.getUniqueId().toString();
        int dropped = 0;
        for (ItemStack stack : payload) {
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            try {
                Item item = world.dropItem(center, stack.clone());
                if (item == null) {
                    continue;
                }
                item.setPickupDelay(0);
                if (settings.glow()) {
                    item.setGlowing(true);
                }
                if (settings.ownerSeconds() > 0) {
                    item.setOwner(player.getUniqueId());
                    long ticks = settings.ownerSeconds() * 20L;
                    plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                        // Предмет мог уже подобрать или выгрузить мир — проверяем.
                        if (item.isValid()) {
                            item.setOwner(null);
                        }
                    }, ticks);
                }
                double scatter = settings.scatter();
                item.setVelocity(new Vector(
                        (random.nextDouble() - 0.5) * 2.0 * scatter,
                        settings.lift() + random.nextDouble() * 0.2,
                        (random.nextDouble() - 0.5) * 2.0 * scatter));
                dropped++;
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("[CHRDK_Treasures] не удалось уронить добычу: "
                        + exception.getMessage());
            }
        }
        if (dropped == 0) {
            return 0;
        }
        effects(world, center, player, key);
        plugin.messages().send(player, "drops-landed",
                "count", String.valueOf(dropped));
        return dropped;
    }

    /** Короткий блеск и звук: находка должна читаться, но не спамить. */
    private void effects(World world, Location center, Player player, String key) {
        if (plugin.store() != null && plugin.store().particles(key)) {
            try {
                world.spawnParticle(Particle.HAPPY_VILLAGER, center, 14, 0.45, 0.3, 0.45, 0.0);
            } catch (RuntimeException ignored) {
                // Частица могла не отрисоваться — находка от этого не пропадает.
            }
        }
        if (plugin.store() != null && plugin.store().sounds(key)) {
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.7f, 1.25f);
        }
    }

    /** Строка в журнале: куда легла добыча (для `/treasuresadmin info` и логов). */
    public String summary() {
        TreasuresConfig.Drops settings = plugin.config() == null
                ? TreasuresConfig.Drops.INVENTORY : plugin.config().drops();
        if (!settings.ground()) {
            return plugin.messages().plain("drops-summary-barrel");
        }
        return plugin.messages().plain("drops-summary-ground",
                "seconds", String.valueOf(settings.ownerSeconds()));
    }
}
