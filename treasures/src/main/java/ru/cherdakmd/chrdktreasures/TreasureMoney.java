package ru.cherdakmd.chrdktreasures;

import org.bukkit.inventory.ItemStack;

/** Ordinary money loot only. Named/custom items and genuine equipment/resources remain physical. */
final class TreasureMoney {
    static int unit(String material){return switch(material){case "DIAMOND"->1;case "DIAMOND_BLOCK"->9;default->0;};}
    static boolean plain(ItemStack item){return item!=null&&!item.hasItemMeta()&&item.getAmount()>0&&item.getAmount()<=64&&unit(item.getType().name())>0;}
    private TreasureMoney(){}
}
