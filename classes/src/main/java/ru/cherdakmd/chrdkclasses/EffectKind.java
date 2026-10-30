package ru.cherdakmd.chrdkclasses;

import java.util.Locale;

/**
 * Виды пассивных эффектов класса и талантов (1.4.164).
 *
 * <p>У каждого вида есть потолок: таланты складываются, и без потолка дерево
 * превратилось бы в бесконечную лестницу силы. Потолки подобраны так, чтобы
 * полностью прокачанный класс был заметно сильнее новичка, но не отменял
 * броню, зелья и остальные аддоны.</p>
 *
 * <p>Тексты — {@code effect.<ид>} в {@code messages.yml}; здесь только числа и
 * единицы, поэтому вид эффекта проверяется тестами без сервера.</p>
 */
public enum EffectKind {

    /** Дополнительные полусердца здоровья. */
    MAX_HEALTH("max-health", 10.0, Unit.HALF_HEARTS),
    /** Очки брони, как у нагрудника. */
    ARMOR("armor", 12.0, Unit.POINTS),
    /** Прибавка к скорости ходьбы в долях от базы. */
    MOVEMENT_SPEED("movement-speed", 0.15, Unit.PERCENT),
    /** Сопротивление отбрасыванию: доля 0..1. */
    KNOCKBACK_RESISTANCE("knockback-resistance", 0.5, Unit.PERCENT),
    /** Множитель ближнего урона в долях. */
    MELEE_DAMAGE("melee-damage", 0.35, Unit.PERCENT),
    /** Множитель урона стрел в долях. */
    ARROW_DAMAGE("arrow-damage", 0.5, Unit.PERCENT),
    /** Снижение входящего урона в долях. */
    DAMAGE_REDUCTION("damage-reduction", 0.35, Unit.PERCENT),
    /** Снижение урона от падения в долях. */
    FALL_REDUCTION("fall-reduction", 0.6, Unit.PERCENT),
    /** Внутренние единицы лечения: пульс даёт value / 2 здоровья, то есть value / 4 сердец. */
    REGEN("regen", 4.0, Unit.HALF_HEARTS),
    /** Единицы насыщения за такт пульса. */
    SATURATION("saturation", 8.0, Unit.POINTS);

    /** Как показывать величину в тексте. */
    public enum Unit {
        /** Отображение сердцами: здоровье / 2, у REGEN исторически дополнительный делитель пульса. */
        HALF_HEARTS,
        /** Целые единицы (очки брони, насыщение). */
        POINTS,
        /** Доля: 0.12 = 12 %. */
        PERCENT
    }

    private final String id;
    private final double cap;
    private final Unit unit;

    EffectKind(String id, double cap, Unit unit) {
        this.id = id;
        this.cap = cap;
        this.unit = unit;
    }

    public String id() {
        return id;
    }

    /** Потолок суммы: выше него таланты не складываются. */
    public double cap() {
        return cap;
    }

    public Unit unit() {
        return unit;
    }

    /** Разбор ида без исключений: мусор и {@code null} дают {@code null}. */
    public static EffectKind parse(String raw) {
        if (raw == null) {
            return null;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (EffectKind value : values()) {
            if (value.id.equals(key)) {
                return value;
            }
        }
        return null;
    }
}
