package ru.cherdakmd.chrdkhunt;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;

/**
 * Трофей охотника: коллекционная награда за осколки и монеты.
 *
 * <p>Трофей — только косметика: он попадает в коллекцию игрока и выдаётся
 * предметом для рамки или полки. Никаких бонусов к урону, защите или скорости
 * он не даёт, поэтому его можно спокойно показывать и дарить. Уже полученный
 * трофей выдаётся повторно бесплатно — потерять косметику нельзя.</p>
 *
 * @param id              ид трофея для {@code /hunt trophy <id>}
 * @param familyId        семейство, чьи осколки нужны
 * @param material        материал предмета-трофея
 * @param displayName     название с MiniMessage
 * @param lore            описание в меню и на предмете
 * @param shards          сколько осколков семейства стоит трофей
 * @param coins           сколько монет стоит трофей
 * @param minCoinsEarned  порог «стажа» охотника
 * @param order           порядок в меню
 */
public record HuntTrophy(String id, String familyId, Material material, String displayName,
                         List<String> lore, int shards, int coins, int minCoinsEarned,
                         int order) {

    /** Потолки стоимости: трофей не должен съедать экономику. */
    public static final int MAX_SHARDS = 512;
    public static final int MAX_COINS = 100_000;

    public HuntTrophy {
        id = HuntFamily.normalize(id);
        familyId = HuntFamily.normalize(familyId);
        if (material == null) {
            throw new IllegalArgumentException("Материал трофея не может быть пустым");
        }
        displayName = HuntFamily.blankTo(displayName, id);
        lore = lore == null ? List.of() : List.copyOf(new ArrayList<>(lore));
        shards = Math.clamp(shards, 0, MAX_SHARDS);
        coins = Math.clamp(coins, 0, MAX_COINS);
        minCoinsEarned = Math.clamp(minCoinsEarned, 0, Integer.MAX_VALUE);
        order = Math.clamp(order, 0, Integer.MAX_VALUE);
    }

    /** Трофей вообще можно получить: он чего-то стоит или бесплатен как награда. */
    public boolean usable() {
        return !id.isEmpty() && !familyId.isEmpty();
    }
}
