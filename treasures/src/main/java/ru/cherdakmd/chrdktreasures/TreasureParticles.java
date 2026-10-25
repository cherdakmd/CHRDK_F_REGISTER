package ru.cherdakmd.chrdktreasures;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Безопасный вызов частиц.
 *
 * <p>1.4.170: в новых версиях Minecraft часть частиц требует данных
 * ({@code DRAGON_BREATH} и {@code SCULK_CHARGE} — {@code Float}, {@code DUST} —
 * {@code DustOptions} и так далее). Вызов без данных бросает
 * {@link IllegalArgumentException} «missing required data» из
 * {@code CraftParticle}. Раньше это обрывало событие находки: игрок не получал
 * ни сообщения, ни витрины. Здесь данные подставляются, если сервер их
 * требует, а сбой эффекта гасится предупреждением — один раз на частицу, а не
 * на каждый вызов.</p>
 */
final class TreasureParticles {

    /** Частицы, о сбое которых уже сообщили. */
    private static final Set<Particle> WARNED = ConcurrentHashMap.newKeySet();

    private TreasureParticles() {
    }

    /**
     * Данные, без которых частица не отрисуется.
     *
     * <p>Пока из частиц кладов данные нужны только семейству {@code Float}
     * (сила драконьего дыхания). Всё остальное отдаёт {@code null} — сервер
     * сам подставляет пустые данные.</p>
     */
    static Object dataOf(Particle particle) {
        if (particle == null) {
            return null;
        }
        return particle.getDataType() == Float.class ? 1.0f : null;
    }

    /**
     * Рисует частицу, подставляя данные, если они обязательны.
     *
     * @return {@code true}, если вызов прошёл без исключения
     */
    static boolean spawn(World world, Particle particle, Location location, int count,
                         double offsetX, double offsetY, double offsetZ, double extra,
                         Logger logger) {
        if (world == null || particle == null || location == null) {
            return false;
        }
        Object data = dataOf(particle);
        try {
            if (data == null) {
                world.spawnParticle(particle, location, count, offsetX, offsetY, offsetZ, extra);
            } else {
                world.spawnParticle(particle, location, count, offsetX, offsetY, offsetZ, extra, data);
            }
            return true;
        } catch (RuntimeException exception) {
            if (WARNED.add(particle)) {
                logger.warning("[CHRDK_Treasures] частица " + particle
                        + " не отрисована: " + exception.getMessage());
            }
            return false;
        }
    }
}
