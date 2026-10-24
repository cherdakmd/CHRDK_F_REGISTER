package ru.cherdakmd.chrdkquests;

import org.bukkit.Material;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Фракция и её лестница рангов (1.4.161).
 *
 * <p>Репутация копится отдельно у каждой фракции и никогда не сгорает: игрок
 * может уйти из Ордена в Гильдию и вернуться, не начиная заново. Ранг — это
 * порог репутации, а не отдельное хранилище: пересчитывается при каждом
 * обращении, поэтому «ранг разошёлся с очками» невозможен.</p>
 *
 * @param id          ид фракции ({@code watch})
 * @param name        имя для чата
 * @param description короткое пояснение
 * @param icon        иконка в меню
 * @param prefix      префикс ранга в чате (MiniMessage); пусто — без префикса
 * @param ranks       порог репутации → ид ранга (текст — {@code rank.<ид>})
 */
public record QuestFaction(String id, String name, String description, Material icon,
                           String prefix, NavigableMap<Integer, String> ranks) {

    /** Потолок репутации одной фракции. */
    public static final int MAX_REPUTATION = 1_000_000;
    /** Сколько ступеней максимум: больше не помещается в профиль. */
    public static final int MAX_RANKS = 10;

    /** Лестница по умолчанию, если в конфиге не задана. */
    public static final NavigableMap<Integer, String> DEFAULT_RANKS = defaults();

    private static NavigableMap<Integer, String> defaults() {
        TreeMap<Integer, String> ladder = new TreeMap<>();
        ladder.put(0, "newcomer");
        ladder.put(250, "fighter");
        ladder.put(750, "veteran");
        ladder.put(1_500, "captain");
        ladder.put(3_000, "marshal");
        return Collections.unmodifiableNavigableMap(ladder);
    }

    public QuestFaction {
        id = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        name = name == null || name.isBlank() ? id : name.trim();
        description = description == null ? "" : description.trim();
        icon = icon == null || icon == Material.AIR ? Material.PAPER : icon;
        prefix = prefix == null ? "" : prefix.trim();
        ranks = normalizeRanks(ranks);
    }

    private static NavigableMap<Integer, String> normalizeRanks(NavigableMap<Integer, String> source) {
        if (source == null || source.isEmpty()) {
            return DEFAULT_RANKS;
        }
        TreeMap<Integer, String> ladder = new TreeMap<>();
        for (Map.Entry<Integer, String> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isBlank()) {
                continue;
            }
            if (ladder.size() >= MAX_RANKS) {
                break;
            }
            ladder.put(Math.clamp(entry.getKey(), 0, MAX_REPUTATION), entry.getValue().trim());
        }
        return ladder.isEmpty() ? DEFAULT_RANKS
                : Collections.unmodifiableNavigableMap(ladder);
    }

    /** Ид ранга для очков репутации. */
    public String rankId(int reputation) {
        Map.Entry<Integer, String> entry = ranks.floorEntry(Math.clamp(reputation, 0, MAX_REPUTATION));
        return entry == null ? ranks.firstEntry().getValue() : entry.getValue();
    }

    /** Номер ранга, начиная с 1 (первый порог — самый низкий). */
    public int rankNumber(int reputation) {
        int number = 1;
        int best = 0;
        for (int threshold : ranks.keySet()) {
            if (threshold <= reputation) {
                best = number;
            }
            number++;
        }
        return Math.max(1, best);
    }

    /** Порог следующего ранга или {@code -1}, если выше некуда. */
    public int nextThreshold(int reputation) {
        Integer next = ranks.higherKey(Math.clamp(reputation, 0, Integer.MAX_VALUE));
        return next == null ? -1 : next;
    }

    /** Сколько очков до следующего ранга; 0 — уже максимальный. */
    public int toNext(int reputation) {
        int next = nextThreshold(reputation);
        return next < 0 ? 0 : Math.max(0, next - reputation);
    }

    /** Высший порог: на нём лестница заканчивается. */
    public int maxThreshold() {
        return ranks.isEmpty() ? 0 : ranks.lastKey();
    }

    /** Префикс показывается с этого номера ранга. */
    public boolean hasPrefix() {
        return !prefix.isEmpty();
    }
}
