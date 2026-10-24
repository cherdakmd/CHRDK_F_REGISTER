package ru.cherdakmd.chrdkbosses;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Зона опасности (1.4.162).
 *
 * <p>Место на карте, где мир сопротивляется: медленнее ноги, темнее глаза,
 * капает здоровье — но и опыт за мобов идёт с надбавкой. Зона не убивает: урон
 * снижается так, чтобы не отнимать последние сердца. Смысл — заставить
 * готовиться, а не наказать за любопытство.</p>
 *
 * @param id                   ид зоны
 * @param name                 имя для сообщений
 * @param description          короткое пояснение
 * @param enabled              включена ли зона
 * @param world                мир
 * @param x                    центр X
 * @param y                    центр Y
 * @param z                    центр Z
 * @param radius               радиус в блоках (сфера)
 * @param level                уровень опасности 1..5
 * @param damagePerSecond      урон в секунду (0 — без урона)
 * @param xpBonusPercent       надбавка к опыту за мобов внутри зоны
 * @param effectIntervalSeconds как часто накладывать эффекты
 * @param effects              эффекты зоны
 * @param icon                 иконка в меню
 */
public record DangerZone(String id, String name, String description, boolean enabled,
                         String world, double x, double y, double z, double radius,
                         int level, double damagePerSecond, int xpBonusPercent,
                         int effectIntervalSeconds, List<ZoneEffect> effects, Material icon) {

    /** Потолок радиуса зоны. */
    public static final double MAX_RADIUS = 128.0;
    /** Потолок урона в секунду: больше — зона убивает за секунды. */
    public static final double MAX_DAMAGE_PER_SECOND = 4.0;
    /** Потолок надбавки к опыту: экономика не должна поехать. */
    public static final int MAX_XP_BONUS_PERCENT = 50;
    /** Потолок числа эффектов. */
    public static final int MAX_EFFECTS = 4;

    public DangerZone {
        id = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        name = name == null || name.isBlank() ? id : name.trim();
        description = description == null ? "" : description.trim();
        world = world == null ? "" : world.trim();
        x = Math.clamp(x, -30_000_000.0, 30_000_000.0);
        y = Math.clamp(y, -256.0, 320.0);
        z = Math.clamp(z, -30_000_000.0, 30_000_000.0);
        radius = Math.clamp(radius, 4.0, MAX_RADIUS);
        level = Math.clamp(level, 1, 5);
        damagePerSecond = Math.clamp(damagePerSecond, 0.0, MAX_DAMAGE_PER_SECOND);
        xpBonusPercent = Math.clamp(xpBonusPercent, 0, MAX_XP_BONUS_PERCENT);
        effectIntervalSeconds = Math.clamp(effectIntervalSeconds, 1, 60);
        effects = copy(effects);
        icon = icon == null || icon == Material.AIR ? Material.MAGMA_BLOCK : icon;
    }

    private static List<ZoneEffect> copy(List<ZoneEffect> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<ZoneEffect> out = new ArrayList<>();
        for (ZoneEffect effect : source) {
            if (effect == null || !effect.usable()) {
                continue;
            }
            if (out.size() >= MAX_EFFECTS) {
                break;
            }
            out.add(effect);
        }
        return List.copyOf(out);
    }

    /** Лежит ли точка внутри зоны. */
    public boolean contains(String otherWorld, double otherX, double otherY, double otherZ) {
        if (!enabled || otherWorld == null || !world.equalsIgnoreCase(otherWorld)) {
            return false;
        }
        double dx = otherX - x;
        double dy = otherY - y;
        double dz = otherZ - z;
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    /** Есть ли у зоны хоть какой-то эффект на игрока. */
    public boolean harmful() {
        return damagePerSecond > 0 || !effects.isEmpty();
    }

    /** Подпись уровня: «Опасность III». */
    public String levelLabel() {
        return "Опасность " + roman(level);
    }

    /** Римская цифра уровня — для меню и сообщений. */
    public static String roman(int value) {
        return switch (Math.clamp(value, 1, 5)) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            default -> "V";
        };
    }

    /** Короткая подпись для меню и команды {@code /boss zones}. */
    public String label() {
        return name + " · " + levelLabel() + " · r" + Math.round(radius);
    }
}
