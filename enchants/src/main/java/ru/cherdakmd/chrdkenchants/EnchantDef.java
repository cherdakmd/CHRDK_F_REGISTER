package ru.cherdakmd.chrdkenchants;

import java.util.Set;

/**
 * Определение кастомной чары. conflictGroup != null: чары одной группы несовместимы
 * на одном предмете.
 */
public record EnchantDef(String id, Rarity rarity, int maxLevel, String conflictGroup,
                         Set<ItemGroup> supports, Polarity polarity) {

    /** Знак чары: светлая (обычная награда) или проклятие (риск). */
    public enum Polarity {
        LIGHT,
        CURSE;

        public boolean curse() {
            return this == CURSE;
        }

        /** Ключ строки лора и имени книги. */
        public String labelKey() {
            return curse() ? "polarity-curse" : "polarity-light";
        }
    }

    /** Проклятие ли эта чара. */
    public boolean curse() {
        return polarity != null && polarity.curse();
    }

    public enum Rarity {
        COMMON(45), RARE(30), EPIC(17), LEGENDARY(8);

        private final int weight;

        Rarity(int weight) {
            this.weight = weight;
        }

        public int weight() {
            return weight;
        }

        public String labelKey() {
            return "rarity-" + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public boolean supports(String materialName) {
        ItemGroup group = ItemGroup.classify(materialName);
        return group != null && (supports.contains(group)
                || (group.isMelee() && supports.containsAll(Set.of(ItemGroup.SWORD, ItemGroup.AXE))));
    }

    public boolean isArmorOnly() {
        return supports.stream().allMatch(group ->
                group == ItemGroup.HELMET || group == ItemGroup.CHESTPLATE
                        || group == ItemGroup.LEGGINGS || group == ItemGroup.BOOTS);
    }
}
