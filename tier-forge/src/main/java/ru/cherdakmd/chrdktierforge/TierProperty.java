package ru.cherdakmd.chrdktierforge;

/**
 * Уникальное свойство тира: то, чем редкость отличается от просто «+процент».
 *
 * <p>У каждого из семи тиров свой пул из 15 свойств (реестр —
 * {@link TierProperties}). Предмет получает одно свойство своего тира при
 * накате, перекате, слиянии и апгрейде; свойство живёт в PDC и показано в лоре
 * отдельной строкой с маркером ✧.</p>
 *
 * <p>Эффект переиспользует кузнечную механику {@link ForgeUpgrade.Effect}:
 * атрибутные свойства начисляются модификаторами, событийные — тем же
 * слушателем, что и ковка (например, «Кровопийца» или «Громовержец»). Поэтому
 * свойства тира не дублируют код и подчиняются тем же потолкам.</p>
 *
 * @param id     ключ свойства (PDC, реестр)
 * @param tier   тир, которому принадлежит свойство
 * @param name   русское название для лора и {@code /tiers info}
 * @param target на какой вид предмета ставится
 * @param effect эффект (атрибут или событие)
 * @param value  величина: проценты — числом (8 → 8%), абсолютные — как есть
 */
public record TierProperty(String id, String tier, String name,
                           ForgeUpgrade.Target target, ForgeUpgrade.Effect effect,
                           double value) {

    /** Представление для общей механики: одно «улучшение» первого уровня. */
    public ForgeUpgrade.Def asDef() {
        return new ForgeUpgrade.Def("prop_" + id, name, ForgeUpgrade.Target.ANY,
                effect, value, 1, "");
    }

    /** Человеческое описание величины: «+8% урона», «+1❤ к здоровью». */
    public String describe() {
        return ForgeUpgrade.describe(asDef(), 1);
    }

    /** Подходит ли свойство виду предмета: и цель, и сам эффект. */
    public boolean fits(ItemKind kind) {
        return kind != null && target.matches(kind) && effect.fits(kind);
    }
}
