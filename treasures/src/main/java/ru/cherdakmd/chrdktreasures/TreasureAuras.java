package ru.cherdakmd.chrdktreasures;

import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Ауры находок: регенерация, дыхание под водой и огнестойкость.
 *
 * <p>Эти эффекты нельзя повесить атрибутом, поэтому раз в
 * {@code general.aura-refresh-ticks} плагин пересобирает их по надетым
 * находкам. Длительность берётся с запасом в 40 тиков, чтобы эффект не
 * мигал между пульсами; чужой более сильный эффект не понижается.</p>
 *
 * <p>Ауры работают только от надетых предметов: держать «Венец бездны» в
 * рюкзаке бесполезно.</p>
 */
public final class TreasureAuras {

    /** Запас сверх периода пульса, чтобы эффект не мигал. */
    private static final int BUFFER_TICKS = 40;

    private final TreasuresPlugin plugin;

    public TreasureAuras(TreasuresPlugin plugin) {
        this.plugin = plugin;
    }

    /** Пересобирает ауры игрока по текущей экипировке. */
    public void apply(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        apply(player, TreasureEffect.Kind.REGENERATION, PotionEffectType.REGENERATION);
        apply(player, TreasureEffect.Kind.WATER_BREATHING, PotionEffectType.WATER_BREATHING);
        apply(player, TreasureEffect.Kind.FIRE_RESISTANCE, PotionEffectType.FIRE_RESISTANCE);
    }

    private void apply(Player player, TreasureEffect.Kind kind, PotionEffectType type) {
        double level = plugin.items().sum(player, kind);
        if (level <= 0.0) {
            return;
        }
        int amplifier = Math.max(0, (int) Math.round(level) - 1);
        int duration = Math.max(20, plugin.config().auraRefreshTicks()) + BUFFER_TICKS;
        PotionEffect current = player.getPotionEffect(type);
        if (current != null) {
            if (current.getAmplifier() > amplifier) {
                return;
            }
            if (current.getAmplifier() == amplifier && current.getDuration() > duration) {
                return;
            }
        }
        player.addPotionEffect(new PotionEffect(type, duration, amplifier,
                true, false, true));
    }
}
