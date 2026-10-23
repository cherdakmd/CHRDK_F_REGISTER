package ru.cherdakmd.chrdkhunt;

import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.List;

/**
 * Правило добычи с одного моба.
 *
 * <p>Семейство определяет осколок и ключ, а это правило — сколько монет даёт
 * конкретный моб, с каким шансом выпадает осколок и ключ и что падает сверху
 * обычными вещами. Боссы — те же правила с {@code boss: true}: у них больше
 * монет, гарантированный осколок и громкое объявление в чат.</p>
 *
 * @param entityType  тип сущности (имя {@link EntityType})
 * @param familyId    ид семейства
 * @param minCoins    минимум монет за убийство
 * @param maxCoins    максимум монет за убийство
 * @param shardChance шанс осколка в процентах
 * @param minShards   минимум осколков при срабатывании шанса
 * @param maxShards   максимум осколков
 * @param keyChance   шанс ключа в процентах
 * @param boss        босс: объявление в чат и повышенная добыча
 * @param loot        дополнительный ванильный дроп
 */
public record MobRule(EntityType entityType, String familyId, int minCoins, int maxCoins,
                      double shardChance, int minShards, int maxShards, double keyChance,
                      boolean boss, List<HuntLoot> loot) {

    /** Верхняя граница монет за одного моба: защита от опечатки в конфиге. */
    public static final int MAX_COINS = 100_000;
    /** Больше стака осколков за убийство не выдаём. */
    public static final int MAX_SHARDS = 64;

    public MobRule {
        if (entityType == null) {
            throw new IllegalArgumentException("Тип моба не может быть пустым");
        }
        familyId = HuntFamily.normalize(familyId);
        minCoins = Math.clamp(minCoins, 0, MAX_COINS);
        maxCoins = Math.clamp(maxCoins, minCoins, MAX_COINS);
        shardChance = Double.isFinite(shardChance) ? Math.clamp(shardChance, 0.0, 100.0) : 0.0;
        minShards = Math.clamp(minShards, 0, MAX_SHARDS);
        maxShards = Math.clamp(maxShards, minShards, MAX_SHARDS);
        if (shardChance <= 0.0) {
            minShards = 0;
            maxShards = 0;
        }
        keyChance = Double.isFinite(keyChance) ? Math.clamp(keyChance, 0.0, 100.0) : 0.0;
        loot = loot == null ? List.of() : List.copyOf(new ArrayList<>(loot));
    }

    /** Рабочее правило: есть семейство и хоть какая-то награда. */
    public boolean usable() {
        return !familyId.isEmpty() && (maxCoins > 0 || maxShards > 0 || keyChance > 0.0);
    }

    /** Моб даёт хоть один осколок. */
    public boolean givesShards() {
        return maxShards > 0;
    }

    /** Моб может дать ключ. */
    public boolean givesKey() {
        return keyChance > 0.0;
    }
}
