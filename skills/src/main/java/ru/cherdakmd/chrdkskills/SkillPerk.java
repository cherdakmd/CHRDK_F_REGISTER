package ru.cherdakmd.chrdkskills;

/**
 * Перк навыка: открывается на указанном уровне и даёт один эффект.
 *
 * @param id    аски-ид для messages.yml ({@code perk.<id>.name|desc})
 * @param level уровень, с которого перк работает
 * @param kind  вид эффекта
 * @param value величина: доля (0.1 = 10 %) или абсолютное число
 */
public record SkillPerk(String id, int level, Kind kind, double value) {

    /**
     * Виды эффектов. {@link #percent()} говорит, как трактовать величину:
     * доля от базового значения или абсолютная прибавка.
     */
    public enum Kind {
        /** Скорость разрушения блоков, доля. */
        BREAK_SPEED(true),
        /** Броня, абсолютное число. */
        ARMOR(false),
        /** Шанс двойной добычи блока, доля. */
        DOUBLE_DROP(true),
        /** Автопосадка после сбора урожая (флаг). */
        AUTO_REPLANT(false),
        /** Шанс дополнительной рыбы, доля. */
        EXTRA_CATCH(true),
        /** Множитель урона в ближнем бою, доля сверх 100 %. */
        MELEE_DAMAGE(true),
        /** Вампиризм: доля нанесённого урона возвращается здоровьем. */
        LIFESTEAL(true),
        /** Множитель урона из лука, доля сверх 100 %. */
        ARROW_DAMAGE(true),
        /** Шанс не потратить стрелу, доля. */
        ARROW_KEEP(true),
        /** Снижение входящего урона, доля. */
        DAMAGE_REDUCTION(true),
        /** Удлинение зелий, доля. */
        POTION_DURATION(true),
        /** Шанс второго зелья при варке, доля. */
        EXTRA_POTION(true),
        /** Шанс поднять уровень чары на 1, доля. */
        ENCHANT_BONUS(true),
        /** Шанс вернуть половину уровней за чары, доля. */
        XP_REFUND(true);

        private final boolean percent;

        Kind(boolean percent) {
            this.percent = percent;
        }

        public boolean percent() {
            return percent;
        }
    }

    public SkillPerk {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("у перка должен быть ид");
        }
        if (level < 1 || level > 100) {
            throw new IllegalArgumentException("уровень перка вне 1..100: " + level);
        }
        if (value < 0) {
            throw new IllegalArgumentException("величина перка не может быть отрицательной: " + id);
        }
        if (kind.percent() && value > 1.0) {
            throw new IllegalArgumentException("доля перка больше 100 %: " + id);
        }
    }
}
