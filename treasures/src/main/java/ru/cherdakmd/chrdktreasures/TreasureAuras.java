package ru.cherdakmd.chrdktreasures;

import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Ауры находок и сетовых бонусов: регенерация, дыхание под водой, огнестойкость,
 * скорость, спешка, сопротивление, медленное падение, ночное зрение, прыгучесть
 * и дельфинья грация.
 *
 * <p>Эти эффекты нельзя повесить атрибутом, поэтому раз в
 * {@code general.aura-refresh-ticks} плагин пересобирает их по надетым
 * находкам и действующему сету. Длительность берётся с запасом в 40 тиков,
 * чтобы эффект не мигал между пульсами; чужой более сильный эффект не
 * понижается.</p>
 *
 * <p>Ауры предметов работают только от надетых вещей: держать «Венец бездны»
 * в рюкзаке бесполезно. Ауры сета — наоборот, от набора в инвентаре: четыре
 * находки одного региона не обязаны быть надеты. Если игрок убрал сет, его
 * аура доживает свой срок — не дольше одного пульса с запасом.</p>
 */
public final class TreasureAuras {

    /** Запас сверх периода пульса, чтобы эффект не мигал. */
    private static final int BUFFER_TICKS = 40;

    private final TreasuresPlugin plugin;

    public TreasureAuras(TreasuresPlugin plugin) {
        this.plugin = plugin;
    }

    /** Пересобирает ауры игрока по текущей экипировке и сету. */
    public void apply(Player player) {
        if (player == null || !player.isOnline() || !plugin.config().enabled()) {
            return;
        }
        TreasureSets.Snapshot set = plugin.sets().snapshot(player);
        for (TreasureEffect.Kind kind : TreasureEffect.Kind.values()) {
            if (!kind.aura()) {
                continue;
            }
            PotionEffectType type = potionOf(kind);
            if (type == null) {
                continue;
            }
            apply(player, type, plugin.items().sum(player, kind) + set.value(kind));
        }
    }

    private void apply(Player player, PotionEffectType type, double level) {
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

    /** Эффект-зелье для вида ауры; {@code null} — вид не является аурой. */
    public static PotionEffectType potionOf(TreasureEffect.Kind kind) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case REGENERATION -> PotionEffectType.REGENERATION;
            case WATER_BREATHING -> PotionEffectType.WATER_BREATHING;
            case FIRE_RESISTANCE -> PotionEffectType.FIRE_RESISTANCE;
            case SPEED -> PotionEffectType.SPEED;
            case HASTE -> PotionEffectType.HASTE;
            case RESISTANCE -> PotionEffectType.RESISTANCE;
            case SLOW_FALLING -> PotionEffectType.SLOW_FALLING;
            case NIGHT_VISION -> PotionEffectType.NIGHT_VISION;
            case JUMP_BOOST -> PotionEffectType.JUMP_BOOST;
            case DOLPHINS_GRACE -> PotionEffectType.DOLPHINS_GRACE;
            default -> null;
        };
    }
}
