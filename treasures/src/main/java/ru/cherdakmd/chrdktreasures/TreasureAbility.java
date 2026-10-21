package ru.cherdakmd.chrdktreasures;

import java.util.Locale;

/**
 * Активная способность именной находки: то, что срабатывает по правому клику.
 *
 * <p>Способности есть у эпических, легендарных и мифических предметов: обычные
 * и редкие находки остаются пассивными, чтобы первые шаги не превращались в
 * набор кнопок. Каждая способность параметризована силой, радиусом и
 * перезарядкой, поэтому каталог остаётся данными, а не кодом.</p>
 *
 * <p>Сила ({@code power}) значит разное в зависимости от вида — это указано в
 * описании {@link Kind}. Все способности безопасны для мира: они не ломают
 * блоки, не поджигают территорию и наносят урон только враждебным мобам, не
 * игрокам. Игрок, нажавший ПКМ в приседе с луком в руке, стреляет как обычно:
 * способность лука срабатывает только в приседе.</p>
 *
 * @param kind            вид способности
 * @param power           сила: секунды, сердца, урон или скорость рывка
 * @param radius          радиус действия в блоках (0 — способность на себя)
 * @param cooldownSeconds перезарядка в секундах, 5..300
 */
public record TreasureAbility(Kind kind, double power, int radius, int cooldownSeconds) {

    /** Виды активных способностей. */
    public enum Kind {

        /** Подсветить враждебных мобов в радиусе; сила — секунды свечения. */
        SENSE("подсветка врагов", Unit.SECONDS),
        /** Ударная волна: урон и отброс мобов; сила — урон. */
        BLAST("ударная волна", Unit.DAMAGE),
        /** Рывок вперёд; сила — скорость рывка. */
        DASH("рывок", Unit.VELOCITY),
        /** Прыжок вверх; сила — скорость прыжка. */
        LEAP("прыжок", Unit.VELOCITY),
        /** Лечение; сила — сколько сердец восстанавливает. */
        HEAL("лечение", Unit.HEARTS),
        /** Сопротивление урону; сила — секунды. */
        SHIELD("защитный барьер", Unit.SECONDS),
        /** Поджечь мобов вокруг; сила — секунды горения. */
        FLAME_NOVA("огненная волна", Unit.SECONDS),
        /** Замедлить мобов вокруг; сила — секунды замедления. */
        FROST_NOVA("морозная волна", Unit.SECONDS),
        /** Дыхание под водой и скорость в воде; сила — секунды. */
        AQUA("дыхание глубин", Unit.SECONDS);

        private final String title;
        private final Unit unit;

        Kind(String title, Unit unit) {
            this.title = title;
            this.unit = unit;
        }

        /** Ид для messages.yml: {@code ability.<ид>}. */
        public String id() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }

        /** Ключ названия в messages.yml. */
        public String textKey() {
            return "ability." + id();
        }

        /** Что значит сила способности. */
        public Unit unit() {
            return unit;
        }

        /** Нужна ли цель: способность бьёт по мобам, а не по игроку. */
        public boolean targeted() {
            return this == SENSE || this == BLAST
                    || this == FLAME_NOVA || this == FROST_NOVA;
        }

        /** Русская подпись вида — для логов и админской информации. */
        public String title() {
            return title;
        }
    }

    /** Единица измерения силы способности. */
    public enum Unit {
        /** Секунды действия. */
        SECONDS,
        /** Сердца здоровья. */
        HEARTS,
        /** Урон в половинах сердца. */
        DAMAGE,
        /** Скорость рывка или прыжка. */
        VELOCITY
    }

    public TreasureAbility {
        if (kind == null) {
            throw new IllegalArgumentException("способность без вида");
        }
        if (cooldownSeconds < 5 || cooldownSeconds > 300) {
            throw new IllegalArgumentException("перезарядка вне 5..300 с: " + cooldownSeconds);
        }
        if (radius < 0 || radius > 24) {
            throw new IllegalArgumentException("радиус вне 0..24: " + radius);
        }
        if (power <= 0.0 || power > 40.0) {
            throw new IllegalArgumentException("сила вне 0..40: " + power);
        }
        if (kind.targeted() && radius < 1) {
            throw new IllegalArgumentException("способности по цели нужен радиус: " + kind);
        }
        if (!kind.targeted() && radius != 0) {
            throw new IllegalArgumentException("способность на себя не имеет радиуса: " + kind);
        }
        if (kind == Kind.HEAL && power > 20.0) {
            throw new IllegalArgumentException("лечение больше 20 сердец: " + power);
        }
        if (kind.unit() == Unit.VELOCITY && power > 4.0) {
            throw new IllegalArgumentException("скорость рывка слишком велика: " + power);
        }
    }
}
