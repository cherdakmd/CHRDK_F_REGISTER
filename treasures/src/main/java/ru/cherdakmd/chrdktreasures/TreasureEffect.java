package ru.cherdakmd.chrdktreasures;

/**
 * Эффект кладового предмета.
 *
 * <p>Часть эффектов ложится на предмет атрибутами ({@link Kind#attribute()}),
 * часть — событийная: вампиризм, поджог, бонус опыта и ауры считает плагин.
 * Одно описание на обе половины позволяет каталогу оставаться чистым, а
 * рантайму — не знать, откуда взялся бонус.</p>
 *
 * @param kind  вид эффекта
 * @param value величина: доля (0.1 = 10 %) или абсолютное число
 */
public record TreasureEffect(Kind kind, double value) {

    /** Виды эффектов и способ их применения. */
    public enum Kind {
        /** Урон в ближнем бою, доля. Атрибут ATTACK_DAMAGE. */
        ATTACK_DAMAGE(true, true),
        /** Урон из лука, доля. Событийный: помечаем стрелу при выстреле. */
        RANGED_DAMAGE(true, false),
        /** Броня, абсолютное число. */
        ARMOR(false, true),
        /** Твёрдость брони, абсолютное число. */
        ARMOR_TOUGHNESS(false, true),
        /** Максимум здоровья, абсолютное число (2 = одно сердце). */
        MAX_HEALTH(false, true),
        /** Скорость передвижения, доля. */
        MOVEMENT_SPEED(true, true),
        /** Скорость разрушения блоков, доля. */
        MINING_SPEED(true, true),
        /** Удача, абсолютное число. */
        LUCK(false, true),
        /** Сопротивление отбрасыванию, абсолютное число (0..1). */
        KNOCKBACK_RESISTANCE(false, true),
        /** Снижение урона от падения, доля. */
        FALL_REDUCTION(true, false),
        /** Вампиризм: доля нанесённого урона возвращается здоровьем. */
        LIFESTEAL(true, false),
        /** Шанс поджечь цель при ударе, доля. */
        FLAME(true, false),
        /** Дополнительный опыт за блок, доля. */
        XP_BONUS(true, false),
        /** Шанс второй руды, доля. */
        DOUBLE_ORE(true, false),
        /** Аура регенерации, уровень I..II. */
        REGENERATION(false, false),
        /** Аура подводного дыхания, уровень I..II. */
        WATER_BREATHING(false, false),
        /** Аура огнестойкости, уровень I..II. */
        FIRE_RESISTANCE(false, false);

        private final boolean percent;
        private final boolean attribute;

        Kind(boolean percent, boolean attribute) {
            this.percent = percent;
            this.attribute = attribute;
        }

        /** Доля ли это (true) или абсолютная величина. */
        public boolean percent() {
            return percent;
        }

        /** Ложится ли эффект атрибутом на сам предмет. */
        public boolean attribute() {
            return attribute;
        }
    }

    public TreasureEffect {
        if (kind == null) {
            throw new IllegalArgumentException("эффект без вида");
        }
        if (value < 0.0) {
            throw new IllegalArgumentException("отрицательный эффект: " + kind);
        }
        if (kind.percent() && value > 1.0) {
            throw new IllegalArgumentException("доля больше 100 %: " + kind);
        }
    }
}
