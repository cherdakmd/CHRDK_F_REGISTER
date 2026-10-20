package ru.cherdakmd.chrdktierforge;

import java.util.List;

/**
 * Что тир даёт предмету: набор характеристик зависит от вида.
 *
 * <p>Раньше инструменты (кирка, лопата, мотыга, топор) получали урон и скорость
 * атаки — то есть тир на кирке усиливал драку, а не добычу. С 1.4.117 правило
 * простое:</p>
 * <ul>
 *   <li>оружие — урон;</li>
 *   <li>броня — броня, твёрдость и немного скорости бега (только плюс-тиры);</li>
 *   <li>инструменты — скорость добычи;</li>
 *   <li>топор — и оружие, и инструмент: урон <b>и</b> скорость добычи.</li>
 * </ul>
 *
 * <p>Класс чистый — ни Bukkit, ни конфига: раскладка проверяется юнит-тестами,
 * а {@link TierItems} только применяет её к атрибутам.</p>
 */
public enum TierBonus {

    /** +% к урону атаки (оружие). */
    ATTACK_DAMAGE,
    /** +% к броне (броня). */
    ARMOR,
    /** +% к твёрдости брони (броня). */
    ARMOR_TOUGHNESS,
    /** +% к скорости бега (броня, только плюс-тиры). */
    MOVEMENT_SPEED,
    /** +% к скорости добычи блоков (инструменты). */
    MINING_SPEED;

    private static final List<TierBonus> WEAPON = List.of(ATTACK_DAMAGE);
    private static final List<TierBonus> TOOL = List.of(MINING_SPEED);
    /** Топор рубан и в бою, и по брёвнам — получает обе группы. */
    private static final List<TierBonus> WEAPON_TOOL = List.of(ATTACK_DAMAGE, MINING_SPEED);
    private static final List<TierBonus> ARMOR_SET =
            List.of(ARMOR, ARMOR_TOUGHNESS, MOVEMENT_SPEED);

    /** Бонусы вида предмета в порядке применения. Пустой список — вид не поддержан. */
    public static List<TierBonus> forKind(ItemKind kind) {
        if (kind == null) {
            return List.of();
        }
        return switch (kind) {
            case HELMET, CHESTPLATE, LEGGINGS, BOOTS -> ARMOR_SET;
            case SWORD, BOW, CROSSBOW, TRIDENT -> WEAPON;
            case AXE -> WEAPON_TOOL;
            case PICKAXE, SHOVEL, HOE -> TOOL;
        };
    }

    /** Получает ли вид боевые бонусы (урон) от тира. */
    public static boolean hasCombatBonus(ItemKind kind) {
        return forKind(kind).contains(ATTACK_DAMAGE);
    }

    /** Получает ли вид бонус к добыче от тира. */
    public static boolean hasMiningBonus(ItemKind kind) {
        return forKind(kind).contains(MINING_SPEED);
    }
}
