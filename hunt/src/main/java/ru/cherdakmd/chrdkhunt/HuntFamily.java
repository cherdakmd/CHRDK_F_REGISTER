package ru.cherdakmd.chrdkhunt;

import org.bukkit.Material;

import java.util.Locale;

/**
 * Семейство мобов: нежить, твари, членистоногие, Нижний мир, Край и глубины.
 *
 * <p>Семейство задаёт осколок и ключ (названия и иконки — из конфига),
 * множитель монет и содержимое сундука. Сущности в мир не спавнятся: семейство
 * — это ярлык для правил добычи, а не живой объект.</p>
 *
 * @param id             ид семейства в конфиге
 * @param displayName    русское имя для чата и меню
 * @param shardName      как называется осколок
 * @param keyName        как называется ключ
 * @param coinMultiplier множитель монет за убийство (0.1..10)
 * @param shardItem      иконка осколка в меню
 * @param keyItem        иконка ключа в меню
 * @param chest          содержимое сундука или {@code null}, если его нет
 */
public record HuntFamily(String id, String displayName, String shardName, String keyName,
                         double coinMultiplier, Material shardItem, Material keyItem,
                         HuntChest chest) {

    /** Иконка осколка по умолчанию. */
    public static final Material DEFAULT_SHARD_ITEM = Material.BONE;
    /** Иконка ключа по умолчанию. */
    public static final Material DEFAULT_KEY_ITEM = Material.TRIAL_KEY;

    public HuntFamily {
        id = normalize(id);
        displayName = blankTo(displayName, id);
        shardName = blankTo(shardName, "Осколок");
        keyName = blankTo(keyName, "Ключ");
        coinMultiplier = Double.isFinite(coinMultiplier)
                ? Math.clamp(coinMultiplier, 0.1, 10.0) : 1.0;
        shardItem = HuntMaterials.isItem(shardItem) ? shardItem : DEFAULT_SHARD_ITEM;
        keyItem = HuntMaterials.isItem(keyItem) ? keyItem : DEFAULT_KEY_ITEM;
    }

    /** Ид в нижнем регистре без пробелов — так он хранится в данных игрока. */
    public static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /** Есть ли у семейства рабочий сундук: без него ключ некуда применить. */
    public boolean hasChest() {
        return chest != null;
    }
}
