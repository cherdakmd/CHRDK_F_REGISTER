package ru.cherdakmd.chrdktierforge;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

/**
 * Аура редкости вокруг игрока (1.4.119).
 *
 * <p>Раз в {@code visuals.interval-ticks} сервис смотрит на предмет в руках и
 * надетую броню, выбирает <b>самый сильный</b> тир и рисует его частицы вокруг
 * игрока. Одна аура на игрока, а не по слою на каждую вещь: полный легендарный
 * сет не превращается в облако, а тир всё равно читается.</p>
 *
 * <p>Игрок может отключить ауру себе командой {@code /tiers effects} — флаг
 * лежит в PDC игрока и переживает перезапуск. Админ-выключатель —
 * {@code visuals.enabled: false}.</p>
 */
public final class TierVisualService {

    private static final String OPT_OUT_KEY = "visuals_off";

    private final TierForgePlugin plugin;
    private final NamespacedKey optOutKey;
    private BukkitTask task;

    public TierVisualService(TierForgePlugin plugin) {
        this.plugin = plugin;
        this.optOutKey = new NamespacedKey(plugin, OPT_OUT_KEY);
    }

    /** Перезапускает таймер под текущий конфиг (старт и {@code /tiersadmin reload}). */
    public void restart() {
        stop();
        TierVisuals visuals = plugin.config().visuals();
        if (!visuals.enabled()) {
            return;
        }
        long period = visuals.intervalTicks();
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, period, period);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** Игрок скрыл ауру у себя? */
    public boolean hidden(Player player) {
        return player.getPersistentDataContainer().has(optOutKey, PersistentDataType.BYTE);
    }

    /** Включает/выключает ауру игрока; возвращает {@code true}, если она теперь скрыта. */
    public boolean setHidden(Player player, boolean hidden) {
        if (hidden) {
            player.getPersistentDataContainer().set(optOutKey, PersistentDataType.BYTE, (byte) 1);
        } else {
            player.getPersistentDataContainer().remove(optOutKey);
        }
        return hidden;
    }

    /** Переключает ауру; возвращает {@code true}, если она теперь скрыта. */
    public boolean toggle(Player player) {
        return setHidden(player, !hidden(player));
    }

    /** Один проход: выбрать тир и дорисовать его частицы. */
    void tick() {
        TierVisuals visuals = plugin.config().visuals();
        if (!visuals.enabled()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (hidden(player)) {
                continue;
            }
            TierDef.Snapshot tier = strongest(player, visuals);
            if (tier == null) {
                continue;
            }
            TierVisuals.Style style = visuals.styleFor(tier.id());
            if (style == null || !style.hasParticles()) {
                continue;
            }
            emit(player, visuals, style);
        }
    }

    /** Самый сильный тир среди рук и (опционально) надетой брони. */
    private TierDef.Snapshot strongest(Player player, TierVisuals visuals) {
        TierDef.Snapshot best = pick(null, player.getInventory().getItemInMainHand());
        best = pick(best, player.getInventory().getItemInOffHand());
        if (visuals.armor()) {
            for (ItemStack piece : player.getInventory().getArmorContents()) {
                best = pick(best, piece);
            }
        }
        return best;
    }

    private TierDef.Snapshot pick(TierDef.Snapshot best, ItemStack stack) {
        String id = plugin.items().tierOf(stack);
        if (id == null) {
            return best;
        }
        TierDef.Snapshot candidate = plugin.config().find(id);
        if (candidate == null) {
            return best;
        }
        if (best == null || TierDef.indexOf(id) > TierDef.indexOf(best.id())) {
            return candidate;
        }
        return best;
    }

    private void emit(Player player, TierVisuals visuals, TierVisuals.Style style) {
        Location center = player.getLocation().add(0, 1.0, 0);
        World world = center.getWorld();
        if (world == null) {
            return;
        }
        for (TierVisuals.Effect effect : style.effects()) {
            double offsetY = effect.spread() * 0.6;
            try {
                if (effect.particle().getDataType() == Particle.DustOptions.class) {
                    Particle.DustOptions data = new Particle.DustOptions(effect.color(), effect.size());
                    if (visuals.selfOnly()) {
                        player.spawnParticle(effect.particle(), center, effect.count(),
                                effect.spread(), offsetY, effect.spread(), effect.speed(), data);
                    } else {
                        world.spawnParticle(effect.particle(), center, effect.count(),
                                effect.spread(), offsetY, effect.spread(), effect.speed(), data);
                    }
                } else {
                    if (visuals.selfOnly()) {
                        player.spawnParticle(effect.particle(), center, effect.count(),
                                effect.spread(), offsetY, effect.spread(), effect.speed());
                    } else {
                        world.spawnParticle(effect.particle(), center, effect.count(),
                                effect.spread(), offsetY, effect.spread(), effect.speed());
                    }
                }
            } catch (RuntimeException exception) {
                // Частицу могли убрать в новой версии сервера — аура не критична.
                plugin.getLogger().fine("[Visuals] частица " + effect.particle()
                        + " не отрисована: " + exception.getMessage());
            }
        }
    }
}
