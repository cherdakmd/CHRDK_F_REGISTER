package ru.cherdakmd.chrdktreasures;

import java.util.List;
import java.util.Locale;

/**
 * Редкости кладов — от обычного до мифического.
 *
 * <p>Шанс выпадения задаётся в config.yml (`chances.<ид>`), но значения по
 * умолчанию живут здесь: так каталог и тесты видят один источник правды.
 * Редкости выдаются от самой редкой к обычной, поэтому мифический клад не
 * «съедается» обычным броском.</p>
 *
 * <p>Прогрессия — порог добытых блоков: пока игрок не накопил
 * {@link #minMined()}, редкий клад ему не выпадет. Мифический порог не имеет
 * (0) — он игнорирует прогрессию, как и задумано: это лотерея для любого
 * шахтёра, а не награда за стаж.</p>
 */
public enum TreasureTier {

    COMMON("common", 0, true, 1.000, 0.45),
    RARE("rare", 250, true, 0.220, 0.55),
    EPIC("epic", 2_000, true, 0.050, 0.70),
    LEGENDARY("legendary", 15_000, true, 0.008, 0.90),
    MYTHICAL("mythical", 0, false, 0.0008, 1.00);

    private final String id;
    private final int minMined;
    private final boolean regional;
    private final double defaultChancePercent;
    private final double defaultItemChance;

    TreasureTier(String id, int minMined, boolean regional,
                 double defaultChancePercent, double defaultItemChance) {
        this.id = id;
        this.minMined = minMined;
        this.regional = regional;
        this.defaultChancePercent = defaultChancePercent;
        this.defaultItemChance = defaultItemChance;
    }

    public String id() {
        return id;
    }

    /** Сколько блоков нужно добыть, чтобы клад этой редкости стал доступен. */
    public int minMined() {
        return minMined;
    }

    /** Привязан ли клад к биому; у мифического — общий пул. */
    public boolean regional() {
        return regional;
    }

    public double defaultChancePercent() {
        return defaultChancePercent;
    }

    /** Шанс, что вместо ванильной добычи выпадет именной предмет редкости. */
    public double defaultItemChance() {
        return defaultItemChance;
    }

    /** Редкости от самой редкой к обычной — в этом порядке идёт бросок. */
    public static List<TreasureTier> fromRarest() {
        return List.of(MYTHICAL, LEGENDARY, EPIC, RARE, COMMON);
    }

    public static TreasureTier byId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (TreasureTier tier : values()) {
            if (tier.id.equals(normalized) || tier.name().equalsIgnoreCase(normalized)) {
                return tier;
            }
        }
        return null;
    }
}
