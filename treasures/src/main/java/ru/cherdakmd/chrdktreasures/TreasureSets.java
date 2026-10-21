package ru.cherdakmd.chrdktreasures;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.DOLPHINS_GRACE;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.DOUBLE_ORE;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.FALL_REDUCTION;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.FIRE_RESISTANCE;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.FLAME;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.HASTE;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.JUMP_BOOST;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.LIFESTEAL;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.NIGHT_VISION;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.REGENERATION;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.RESISTANCE;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.SLOW_FALLING;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.SPEED;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.WATER_BREATHING;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.XP_BONUS;

/**
 * Каталог и расчёт сетовых бонусов.
 *
 * <p>Сет — четыре находки одного региона, которые игрок несёт в инвентаре
 * (не обязательно надеть: находки региона — это кирки, клинки и броня, и
 * четыре сразу на себя не надеть). Два предмета дают частичный бонус, четыре —
 * полный; одновременно действует только сильнейший сет, поэтому игрок не
 * складывает ауры двух биомов. Часть бонуса — аура (её обновляет
 * {@link TreasureAuras}), часть — событийная прибавка (опыт, вторая руда,
 * вампиризм, поджог, урон от падения): её слушатели берут через
 * {@link #total(Player, TreasureEffect.Kind)}.</p>
 *
 * <p>Класс содержит и чистый каталог (статические методы), и рантайм-расчёт по
 * надетым предметам, поэтому тесты проверяют таблицу без сервера.</p>
 */
public final class TreasureSets {

    private static final List<TreasureSet> ALL = new ArrayList<>();
    private static final Map<TreasureRegion, TreasureSet> BY_REGION =
            new EnumMap<>(TreasureRegion.class);

    static {
        add(TreasureRegion.PLAINS,
                effect(SPEED, 1),
                effect(SPEED, 2), effect(XP_BONUS, 0.1));
        add(TreasureRegion.FOREST,
                effect(REGENERATION, 1),
                effect(REGENERATION, 2), effect(DOUBLE_ORE, 0.05));
        add(TreasureRegion.TAIGA,
                effect(RESISTANCE, 1),
                effect(RESISTANCE, 2), effect(FALL_REDUCTION, 0.15));
        add(TreasureRegion.SNOW,
                effect(SLOW_FALLING, 1),
                effect(SLOW_FALLING, 2), effect(FALL_REDUCTION, 0.25));
        add(TreasureRegion.DESERT,
                effect(FIRE_RESISTANCE, 1),
                effect(FIRE_RESISTANCE, 2), effect(XP_BONUS, 0.05));
        add(TreasureRegion.SAVANNA,
                effect(JUMP_BOOST, 1),
                effect(JUMP_BOOST, 2), effect(FALL_REDUCTION, 0.15));
        add(TreasureRegion.JUNGLE,
                effect(NIGHT_VISION, 1),
                effect(NIGHT_VISION, 1), effect(REGENERATION, 1), effect(DOUBLE_ORE, 0.08));
        add(TreasureRegion.SWAMP,
                effect(WATER_BREATHING, 1),
                effect(WATER_BREATHING, 1), effect(REGENERATION, 1), effect(DOUBLE_ORE, 0.05));
        add(TreasureRegion.MOUNTAIN,
                effect(SLOW_FALLING, 1),
                effect(SLOW_FALLING, 2), effect(HASTE, 1));
        add(TreasureRegion.OCEAN,
                effect(WATER_BREATHING, 1),
                effect(WATER_BREATHING, 1), effect(DOLPHINS_GRACE, 1));
        add(TreasureRegion.DEEP,
                effect(HASTE, 1),
                effect(HASTE, 2), effect(DOUBLE_ORE, 0.1));
        add(TreasureRegion.DEEP_DARK,
                effect(NIGHT_VISION, 1),
                effect(NIGHT_VISION, 1), effect(RESISTANCE, 1), effect(LIFESTEAL, 0.03));
        add(TreasureRegion.NETHER,
                effect(FIRE_RESISTANCE, 1),
                effect(FIRE_RESISTANCE, 2), effect(FLAME, 0.1));
        add(TreasureRegion.END,
                effect(SLOW_FALLING, 1),
                effect(SLOW_FALLING, 2), effect(LIFESTEAL, 0.05));
    }

    private final TreasuresPlugin plugin;

    public TreasureSets(TreasuresPlugin plugin) {
        this.plugin = plugin;
    }

    /* --------------------------------- каталог -------------------------------- */

    private static void add(TreasureRegion region, TreasureEffect partial, TreasureEffect... full) {
        TreasureSet set = new TreasureSet(region, List.of(partial), List.of(full));
        ALL.add(set);
        BY_REGION.put(region, set);
    }

    private static TreasureEffect effect(TreasureEffect.Kind kind, double value) {
        return new TreasureEffect(kind, value);
    }

    public static List<TreasureSet> all() {
        return List.copyOf(ALL);
    }

    public static TreasureSet of(TreasureRegion region) {
        return region == null ? null : BY_REGION.get(region);
    }

    /**
     * Сильнейший сет по числу надетых предметов.
     *
     * <p>При равенстве побеждает регион, который идёт раньше в перечислении:
     * результат должен быть предсказуемым, а не зависеть от порядка инвентаря.
     * Меньше {@value TreasureSet#PARTIAL_PIECES} предметов — сета нет.</p>
     */
    public static TreasureSet strongest(Map<TreasureRegion, Integer> counts) {
        if (counts == null || counts.isEmpty()) {
            return null;
        }
        TreasureSet best = null;
        int bestPieces = TreasureSet.PARTIAL_PIECES - 1;
        for (TreasureRegion region : TreasureRegion.values()) {
            Integer count = counts.get(region);
            if (count == null || count < TreasureSet.PARTIAL_PIECES) {
                continue;
            }
            if (count > bestPieces) {
                bestPieces = count;
                best = of(region);
            }
        }
        return best;
    }

    /** Сумма бонуса сета заданной силы по одному виду эффекта. */
    public static double bonus(TreasureSet set, int pieces, TreasureEffect.Kind kind) {
        if (set == null || kind == null) {
            return 0.0;
        }
        double total = 0.0;
        for (TreasureEffect effect : set.bonusFor(pieces)) {
            if (effect.kind() == kind) {
                total += effect.value();
            }
        }
        return total;
    }

    /* --------------------------------- рантайм -------------------------------- */

    /** Сколько предметов сета игрок несёт в инвентаре. */
    public int pieces(Player player, TreasureRegion region) {
        if (player == null || region == null || !plugin.config().setsEnabled()) {
            return 0;
        }
        int count = 0;
        for (TreasureItemDef def : plugin.items().carried(player)) {
            if (def.region() == region) {
                count++;
            }
        }
        return count;
    }

    /** Сколько предметов каждого сета в инвентаре; пустые регионы не попадают. */
    public Map<TreasureRegion, Integer> pieces(Player player) {
        Map<TreasureRegion, Integer> counts = new EnumMap<>(TreasureRegion.class);
        if (player == null || !plugin.config().setsEnabled()) {
            return counts;
        }
        for (TreasureItemDef def : plugin.items().carried(player)) {
            TreasureRegion region = def.region();
            if (region != null) {
                counts.merge(region, 1, Integer::sum);
            }
        }
        return counts;
    }

    /** Действующий сет игрока или {@code null}. */
    public TreasureSet active(Player player) {
        return snapshot(player).set();
    }

    /**
     * Снимок сетов: действующий сет и число его предметов за один проход по
     * инвентарю. Пульс аур берёт снимок один раз, а не на каждый вид эффекта.
     */
    public Snapshot snapshot(Player player) {
        Map<TreasureRegion, Integer> counts = pieces(player);
        TreasureSet set = strongest(counts);
        int pieces = set == null ? 0 : counts.getOrDefault(set.region(), 0);
        return new Snapshot(set, pieces);
    }

    /** Бонус действующего сета по виду эффекта. */
    public double bonus(Player player, TreasureEffect.Kind kind) {
        return snapshot(player).value(kind);
    }

    /** Эффекты находок и действующего сета вместе — то, что реально работает. */
    public double total(Player player, TreasureEffect.Kind kind) {
        return plugin.items().sum(player, kind) + bonus(player, kind);
    }

    /** Действующий сет и число его предметов. */
    public static final class Snapshot {

        private final TreasureSet set;
        private final int pieces;

        private Snapshot(TreasureSet set, int pieces) {
            this.set = set;
            this.pieces = pieces;
        }

        public TreasureSet set() {
            return set;
        }

        public int pieces() {
            return pieces;
        }

        /** Бонус снимка по виду эффекта; 0 — сета нет. */
        public double value(TreasureEffect.Kind kind) {
            return set == null ? 0.0 : TreasureSets.bonus(set, pieces, kind);
        }
    }
}
