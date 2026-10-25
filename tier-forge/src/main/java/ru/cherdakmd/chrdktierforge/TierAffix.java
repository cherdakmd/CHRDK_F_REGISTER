package ru.cherdakmd.chrdktierforge;

/**
 * Универсальное свойство-«аффикс»: то, что может выпасть предмету любого тира
 * сверх его собственного свойства.
 *
 * <p>В отличие от {@link TierProperty}, аффикс не привязан к тиру: пулы хороших
 * и плохих аффиксов общие, а тир решает только, <b>сколько</b> их получит
 * предмет (см. {@link TierAffixes}). Поэтому один и тот же легендарный клинок
 * может быть «Оплётка + Жажда крови», а другой — «Ястребиный глаз +
 * Костолом»: комбинаций в разы больше, чем при одном свойстве.</p>
 *
 * <p>Эффект переиспользует кузнечную механику {@link ForgeUpgrade.Effect} —
 * аффикс начисляется тем же путём, что и ковка, и подчиняется тем же потолкам.
 * Плохие аффиксы намеренно только атрибутные: отрицательный шанс-эффект
 * (например, «−5% к дублю руды») просто никогда бы не сработал.</p>
 *
 * @param id       ключ аффикса (реестр, PDC, лор)
 * @param name     русское название для лора и {@code /tiers info}
 * @param polarity хороший или плохой
 * @param target   на какой вид предмета ставится
 * @param effect   эффект (атрибут или событие)
 * @param value    величина: проценты — числом (4 → 4%), абсолютные — как есть
 */
public record TierAffix(String id, String name, Polarity polarity,
                        ForgeUpgrade.Target target, ForgeUpgrade.Effect effect,
                        double value) {

    /** Знак аффикса: хороший — бонус, плохой — дефект. */
    public enum Polarity {
        /** Бонус: зелёная строка с маркером ◆. */
        GOOD("◆"),
        /** Дефект: красная строка с маркером ✖. */
        BAD("✖");

        private final String marker;

        Polarity(String marker) {
            this.marker = marker;
        }

        /** Маркер лор-строки — по нему строка снимается при пересборке. */
        public String marker() {
            return marker;
        }

        public boolean good() {
            return this == GOOD;
        }
    }

    /** Представление для общей механики: одно «улучшение» первого уровня. */
    public ForgeUpgrade.Def asDef() {
        return new ForgeUpgrade.Def("aff_" + id, name, ForgeUpgrade.Target.ANY,
                effect, value, 1, "");
    }

    /** Человеческое описание величины: «+3% урона», «−5% брони». */
    public String describe() {
        return ForgeUpgrade.describe(asDef(), 1);
    }

    /** Подходит ли аффикс виду предмета: и цель, и сам эффект. */
    public boolean fits(ItemKind kind) {
        return kind != null && target.matches(kind) && effect.fits(kind);
    }

    public boolean good() {
        return polarity.good();
    }
}
