package ru.cherdakmd.chrdkdisasters;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Locale;
import java.util.Set;

/**
 * Звуки и мягкие эффекты состояния. Резолвер работает по именам из config.yml,
 * но допускает только константы, уже используемые другими модулями CHRDK, —
 * случайная опечатка владельца просто выключит звук/эффект, а не уронит плагин.
 *
 * <p>Урона здесь нет принципиально: эффекты носят атмосферный характер,
 * накладываются на игроков в радиусе только во время активной фазы и снимаются
 * на затухании/остановке. Право {@code chrdkdisasters.bypass} защищает от них
 * так же, как от самого расписания.</p>
 */
final class DisasterFx {

    static final Set<String> SOUND_NAMES = Set.of(
            "BLOCK_NOTE_BLOCK_BASS", "ENTITY_LIGHTNING_BOLT_IMPACT",
            "ENTITY_LIGHTNING_BOLT_THUNDER", "ITEM_TRIDENT_RIPTIDE_1",
            "BLOCK_BEACON_ACTIVATE", "BLOCK_SNOW_BREAK", "BLOCK_GLASS_BREAK");

    static final Set<String> EFFECT_NAMES = Set.of(
            "SLOWNESS", "SLOW_FALLING", "MINING_FATIGUE", "NAUSEA",
            "DARKNESS", "BLINDNESS");

    private static final String BYPASS = "chrdkdisasters.bypass";

    private DisasterFx() {
    }

    static Sound sound(String raw) {
        String name = normalize(raw);
        return switch (name) {
            case "BLOCK_NOTE_BLOCK_BASS" -> Sound.BLOCK_NOTE_BLOCK_BASS;
            case "ENTITY_LIGHTNING_BOLT_IMPACT" -> Sound.ENTITY_LIGHTNING_BOLT_IMPACT;
            case "ENTITY_LIGHTNING_BOLT_THUNDER" -> Sound.ENTITY_LIGHTNING_BOLT_THUNDER;
            case "ITEM_TRIDENT_RIPTIDE_1" -> Sound.ITEM_TRIDENT_RIPTIDE_1;
            case "BLOCK_BEACON_ACTIVATE" -> Sound.BLOCK_BEACON_ACTIVATE;
            case "BLOCK_SNOW_BREAK" -> Sound.BLOCK_SNOW_BREAK;
            case "BLOCK_GLASS_BREAK" -> Sound.BLOCK_GLASS_BREAK;
            default -> null;
        };
    }

    static PotionEffectType effect(String raw) {
        String name = normalize(raw);
        return switch (name) {
            case "SLOWNESS" -> PotionEffectType.SLOWNESS;
            case "SLOW_FALLING" -> PotionEffectType.SLOW_FALLING;
            case "MINING_FATIGUE" -> PotionEffectType.MINING_FATIGUE;
            case "NAUSEA" -> PotionEffectType.NAUSEA;
            case "DARKNESS" -> PotionEffectType.DARKNESS;
            case "BLINDNESS" -> PotionEffectType.BLINDNESS;
            default -> null;
        };
    }

    static void play(World world, Location center, String name, float volume, float pitch) {
        Sound found = sound(name);
        if (found == null || world == null || center == null) {
            return;
        }
        world.playSound(center, found,
                Math.max(0.0f, Math.min(2.0f, volume)),
                Math.max(0.5f, Math.min(2.0f, pitch)));
    }

    /** Обновляет эффект на один тик с запасом: он не успевает закончиться между тиками. */
    static void apply(World world, Location center, int radius, String name,
                      int amplifier, int ticks) {
        PotionEffectType type = effect(name);
        if (type == null || world == null || center == null) {
            return;
        }
        double limit = (double) Math.max(1, radius) * Math.max(1, radius);
        PotionEffect effect = new PotionEffect(type, Math.max(20, ticks),
                Math.max(0, amplifier), false, false, false);
        for (Player player : world.getPlayers()) {
            if (player == null || !player.isOnline() || player.hasPermission(BYPASS)) {
                continue;
            }
            if (player.getWorld() != world
                    || player.getLocation().distanceSquared(center) > limit) {
                continue;
            }
            player.addPotionEffect(effect);
        }
    }

    /** Снимает только свой эффект, не трогая зелья игрока из других механик. */
    static void clear(World world, Location center, int radius, String name) {
        PotionEffectType type = effect(name);
        if (type == null || world == null || center == null) {
            return;
        }
        double limit = (double) Math.max(1, radius) * Math.max(1, radius);
        for (Player player : world.getPlayers()) {
            if (player == null || player.getWorld() != world
                    || player.getLocation().distanceSquared(center) > limit) {
                continue;
            }
            player.removePotionEffect(type);
        }
    }

    private static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }
}
