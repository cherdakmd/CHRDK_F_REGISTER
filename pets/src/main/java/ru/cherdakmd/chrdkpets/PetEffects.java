package ru.cherdakmd.chrdkpets;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Визуальный отклик: частицы и звуки.
 *
 * <p>Эффекты — только украшение и никогда не заменяют сообщение в чате: если
 * клиент не покажет частицу или на сборке не окажется звука, игрок всё равно
 * увидит текст. Все вызовы обёрнуты в {@code try/catch}: реестр звуков в новых
 * версиях меняется, и падать из-за косметики модуль не должен.</p>
 */
final class PetEffects {

    private PetEffects() {
    }

    /** Сердечки над питомцем — кормление и поглаживание. */
    static void hearts(World world, Location location) {
        particles(world, location, Particle.HEART, 6, 0.35, 0.35, 0.35, 0.0);
    }

    /** Искры при повышении уровня и выученном трюке. */
    static void sparks(World world, Location location) {
        particles(world, location, Particle.CRIT, 18, 0.45, 0.55, 0.45, 0.15);
    }

    /** Искры одного трюка: набор частиц задаётся идентификатором из {@link PetTricks}. */
    static void trick(World world, Location location, PetTricks trick) {
        if (trick == null) {
            return;
        }
        if ("CRIT".equals(trick.effect())) {
            particles(world, location, Particle.CRIT, 14, 0.4, 0.4, 0.4, 0.1);
        } else {
            hearts(world, location);
        }
    }

    private static void particles(World world, Location location, Particle particle, int count,
                                  double offsetX, double offsetY, double offsetZ, double extra) {
        if (world == null || location == null) {
            return;
        }
        try {
            world.spawnParticle(particle, location.add(0.0, 0.9, 0.0), count,
                    offsetX, offsetY, offsetZ, extra);
        } catch (RuntimeException ignored) {
            // частица недоступна на этой сборке — молча пропускаем
        }
    }

    /** Проигрывает звук игроку, если он есть в реестре. */
    static void sound(Player player, String id, float volume, float pitch) {
        if (player == null || id == null) {
            return;
        }
        try {
            Sound sound = Registry.SOUNDS.get(NamespacedKey.minecraft(id));
            if (sound != null) {
                player.playSound(player.getLocation(), sound, volume, pitch);
            }
        } catch (RuntimeException ignored) {
            // звук переименован или удалён — не повод для ошибки
        }
    }
}
