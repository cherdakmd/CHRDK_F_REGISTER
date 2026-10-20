package ru.cherdakmd.chrdkregister.api;

import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * Мост «CHRDK_Enchants → другие аддоны» (1.4.128).
 *
 * <p>Реализацию регистрирует <b>CHRDK_Enchants</b> через {@code ServicesManager};
 * потребители (например, <b>CHRDK_TierForge</b>) спрашивают её мягко и без
 * аддона чар просто не накладывают кастомные чары.</p>
 *
 * <pre>{@code
 * Bukkit.getServicesManager().register(ChrdkEnchantsApi.class, new EnchantsApiBridge(this),
 *         this, ServicePriority.Normal);
 * }</pre>
 *
 * <p>Все методы безопасны для вызова из основного потока и не бросают исключений
 * на чужих предметах: неподходящий предмет или выключенная чара дают
 * {@code false}/{@code 0}/пустой список.</p>
 */
public interface ChrdkEnchantsApi {

    /** Известна ли чара с таким id (регистр не важен). */
    boolean isKnown(String id);

    /** Проклятие ли это (иначе — светлая чара). */
    boolean isCurse(String id);

    /** Идентификаторы включённых светлых чар. */
    List<String> lightIds();

    /** Идентификаторы включённых проклятий. */
    List<String> curseIds();

    /** Человекочитаемое имя чары для лоров и диагностики (пустая строка — нет такой). */
    String name(String id);

    /** Максимальный уровень чары (0 — такой чары нет). */
    int maxLevel(String id);

    /** Уровень чары на предмете (0 — нет). */
    int levelOf(ItemStack item, String id);

    /** Все кастомные чары предмета: id → уровень (пустая карта — их нет). */
    Map<String, Integer> enchantsOf(ItemStack item);

    /** Сколько кастомных чар уже на предмете. */
    int count(ItemStack item);

    /**
     * Накладывает чару на предмет с учётом правил аддона (поддержка вида,
     * лимит чар, конфликтные группы, включена ли чара в конфиге).
     *
     * @return {@code true}, если чара теперь на предмете (в том числе уже была)
     */
    boolean apply(ItemStack item, String id, int level);

    /** Переносит кастомные чары с одного предмета на другой (незеритовый апгрейд). */
    void copy(ItemStack from, ItemStack to);

    /**
     * Перерисовывает лор чар по PDC. Нужно после внешней пересборки лора
     * (тир-предметы): сами чары не меняются, восстанавливаются только строки ✶.
     */
    void refresh(ItemStack item);
}
