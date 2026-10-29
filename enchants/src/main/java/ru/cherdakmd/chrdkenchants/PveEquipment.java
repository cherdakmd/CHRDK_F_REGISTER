package ru.cherdakmd.chrdkenchants;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

/** Износ в конце атаки не является подменой предмета. Игнорируется только Damage, не PDC/чары/модель. */
final class PveEquipment {
    private PveEquipment() { }
    static boolean same(ItemStack before, ItemStack after) {
        boolean emptyBefore = before == null || before.getType().isAir();
        boolean emptyAfter = after == null || after.getType().isAir();
        if (emptyBefore || emptyAfter) return emptyBefore == emptyAfter;
        if (before.getType() != after.getType() || before.getAmount() != after.getAmount()) return false;
        return normalize(before.getItemMeta()).equals(normalize(after.getItemMeta()));
    }
    static boolean same(ItemStack[] before, ItemStack[] after) {
        if (before.length != after.length) return false;
        for (int slot = 0; slot < before.length; slot++) if (!same(before[slot], after[slot])) return false;
        return true;
    }
    static ItemMeta normalize(ItemMeta meta) {
        ItemMeta copy = meta.clone();
        if (copy instanceof Damageable damageable) damageable.setDamage(0);
        return copy;
    }
}
