package ru.cherdakmd.chrdkbosses;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;

/**
 * Награда за босса (1.4.162).
 *
 * <p>Без монет и алмазов: босс — это испытание, а не источник валюты. Опыт и
 * расходники участникам, трофей — тому, кто нанёс больше всех урона. Так
 * победа над боссом не обходит потолки охоты и не обесценивает экономику.</p>
 *
 * @param xp       опыт на всех участников (делится по вкладу)
 * @param items    расходники каждому участнику
 * @param trophy   предмет-трофей лучшему бойцу; {@code AIR} — без трофея
 * @param trophyName имя трофея (MiniMessage); пусто — материал как есть
 */
public record BossReward(int xp, List<Item> items, Material trophy, String trophyName) {

    /** Потолок опыта за босса: больше — уже не награда, а уровни. */
    public static final int MAX_XP = 50_000;
    /** Потолок количества одного предмета. */
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

    public BossReward {
        xp = Math.clamp(xp, 0, MAX_XP);
        items = copy(items);
        trophy = trophy == null ? Material.AIR : trophy;
        trophyName = trophyName == null ? "" : trophyName.trim();
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

    /** Пустая награда — боссы без награды в мир не попадают. */
    public static BossReward none() {
        return new BossReward(0, List.of(), Material.AIR, "");
    }

    /** Есть ли что выдавать вообще. */
    public boolean empty() {
        return xp <= 0 && items.isEmpty() && trophy == Material.AIR;
    }

    /** Есть ли трофей. */
    public boolean hasTrophy() {
        return trophy != Material.AIR;
    }

    /** Короткая сводка для лога и админской команды. */
    public String summary() {
        StringBuilder text = new StringBuilder();
        if (xp > 0) {
            text.append("+").append(xp).append(" xp");
        }
        for (Item item : items) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(item.material().name()).append(" x").append(item.amount());
        }
        if (hasTrophy()) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append("трофей ").append(trophy.name());
        }
        return text.toString();
    }
}
