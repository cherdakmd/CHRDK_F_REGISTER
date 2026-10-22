package ru.cherdakmd.chrdktreasures;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Единый реестр находок: классический каталог плюс арсенал Мирового Клада.
 *
 * <p>Каталог ({@link TreasureCatalog}) остаётся «лестницей биома»: ровно одна
 * находка каждого региона на каждую редкость и шесть мифических. Арсенал
 * ({@link TreasureArsenal}) — параллельный пул, перенесённый из датапака
 * Mine Treasure: он добавляет региональные вещи тех же редкостей и одного
 * мифического «Гига-бура».</p>
 *
 * <p>Снаружи модуль работает только через реестр: витрина, статистика,
 * выдача и распознавание предметов по PDC не должны знать, из какого пула
 * пришла вещь. Иначе арсенал оказался бы «вторым сортом» — невидимым в
 * коллекции и не распознаваемым в руках игрока.</p>
 */
public final class TreasureRegistry {

    private static final List<TreasureItemDef> ALL = new ArrayList<>();
    private static final Map<String, TreasureItemDef> BY_ID = new LinkedHashMap<>();
    private static final List<TreasureItemDef> MYTHICAL = new ArrayList<>();
    private static final Map<TreasureRegion, List<TreasureItemDef>> BY_REGION =
            new LinkedHashMap<>();

    static {
        for (TreasureItemDef def : TreasureCatalog.all()) {
            register(def);
        }
        for (TreasureItemDef def : TreasureArsenal.all()) {
            register(def);
        }
    }

    private TreasureRegistry() {
    }

    private static void register(TreasureItemDef def) {
        TreasureItemDef clash = BY_ID.putIfAbsent(def.id(), def);
        if (clash != null) {
            throw new IllegalStateException("повторяющийся ид находки: " + def.id());
        }
        ALL.add(def);
        if (def.tier() == TreasureTier.MYTHICAL) {
            MYTHICAL.add(def);
        } else {
            BY_REGION.computeIfAbsent(def.region(), key -> new ArrayList<>()).add(def);
        }
    }

    /** Все находки: сначала классические, затем арсенал. */
    public static List<TreasureItemDef> all() {
        return List.copyOf(ALL);
    }

    /** Сколько всего находок в коллекции. */
    public static int size() {
        return ALL.size();
    }

    /** Находка по иду из PDC или названию команды; {@code null} — нет такой. */
    public static TreasureItemDef find(String id) {
        return id == null ? null : BY_ID.get(id);
    }

    /** Мифические находки обоих пулов. */
    public static List<TreasureItemDef> mythical() {
        return List.copyOf(MYTHICAL);
    }

    /** Все находки региона: четыре классические и вещи арсенала. */
    public static List<TreasureItemDef> region(TreasureRegion region) {
        if (region == null) {
            return List.of();
        }
        return List.copyOf(BY_REGION.getOrDefault(region, List.of()));
    }

    /** Пулы региона и редкости: классическая находка и арсенал. */
    public static List<TreasureItemDef> pool(TreasureRegion region, TreasureTier tier) {
        if (tier == null) {
            return List.of();
        }
        if (tier == TreasureTier.MYTHICAL) {
            return mythical();
        }
        List<TreasureItemDef> out = new ArrayList<>(2);
        TreasureItemDef classic = TreasureCatalog.forRegionTier(region, tier);
        if (classic != null) {
            out.add(classic);
        }
        out.addAll(TreasureArsenal.pool(region, tier));
        return List.copyOf(out);
    }

    /** Все находки редкости из обоих пулов. */
    public static List<TreasureItemDef> byTier(TreasureTier tier) {
        List<TreasureItemDef> out = new ArrayList<>();
        for (TreasureItemDef def : ALL) {
            if (def.tier() == tier) {
                out.add(def);
            }
        }
        return List.copyOf(out);
    }

    /** Сколько находок региона уже найдено — по данным витрины. */
    public static int countInRegion(TreasureRegion region) {
        return region(region).size();
    }
}
