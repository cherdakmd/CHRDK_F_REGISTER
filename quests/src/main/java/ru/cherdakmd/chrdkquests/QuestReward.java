package ru.cherdakmd.chrdkquests;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;

/**
 * Награда задания (1.4.161).
 *
 * <p>Ни монет, ни алмазов: награда — опыт, очки фракции и предметы. Так квесты
 * не открывают второй источник валюты и не обходят суточные потолки охоты;
 * предметы подобраны расходными (еда, стрелы, зелья), чтобы не давать силу
 * бесплатно.</p>
 *
 * @param xp         опыт игроку (0..100000)
 * @param reputation очки фракции (0..10000)
 * @param items      предметы в порядке конфига
 */
public record QuestReward(int xp, int reputation, List<Item> items) {

    /** Потолок опыта за одно задание: больше — уже не задание, а уровни. */
    public static final int MAX_XP = 100_000;
    /** Потолок очков фракции за одно задание. */
    public static final int MAX_REPUTATION = 10_000;
    /** Потолок количества одного предмета: защита от «64 стака». */
    public static final int MAX_ITEM_AMOUNT = 64;

    /** Один предмет награды. */
    public record Item(Material material, int amount) {

        public Item {
            material = material == null ? Material.AIR : material;
            amount = Math.clamp(amount, 1, MAX_ITEM_AMOUNT);
        }

        public boolean usable() {
            return material != Material.AIR;
        }
    }

    public QuestReward {
        xp = Math.clamp(xp, 0, MAX_XP);
        reputation = Math.clamp(reputation, 0, MAX_REPUTATION);
        items = copy(items);
    }

    private static List<Item> copy(List<Item> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<Item> out = new ArrayList<>();
        for (Item item : source) {
            if (item != null && item.usable()) {
                out.add(item);
            }
        }
        return List.copyOf(out);
    }

    /** Пустая награда — задания без награды не выдаются. */
    public static QuestReward none() {
        return new QuestReward(0, 0, List.of());
    }

    /** Есть ли вообще что выдавать. */
    public boolean empty() {
        return xp <= 0 && reputation <= 0 && items.isEmpty();
    }

    /** Короткая сводка для лога и админской команды. */
    public String summary() {
        StringBuilder text = new StringBuilder();
        if (xp > 0) {
            text.append("+").append(xp).append(" xp");
        }
        if (reputation > 0) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append("+").append(reputation).append(" rep");
        }
        for (Item item : items) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(item.material().name()).append(" x").append(item.amount());
        }
        return text.toString();
    }
}
