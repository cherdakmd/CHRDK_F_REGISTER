package ru.cherdakmd.chrdkregister.api;

import org.bukkit.inventory.ItemStack;

/**
 * Мост «CHRDK_TierForge → другие аддоны» (1.4.141).
 *
 * <p>Реализацию регистрирует <b>CHRDK_TierForge</b> через {@code ServicesManager};
 * потребители (например, <b>CHRDK_Treasures</b>) спрашивают её мягко и без
 * аддона тиров просто не накладывают тир на свои предметы.</p>
 *
 * <pre>{@code
 * Bukkit.getServicesManager().register(ChrdkTierApi.class, new TierApiBridge(this),
 *         this, ServicePriority.Normal);
 * }</pre>
 *
 * <p>Все методы безопасны для вызова из основного потока и не бросают исключений
 * на чужих предметах: неподходящий предмет, выключенный тир или неизвестный id
 * дают {@code false}/{@code null}, ничего не меняя.</p>
 */
public interface ChrdkTierApi {

    /** Есть ли на предмете тир CHRDK_TierForge. */
    boolean isTierItem(ItemStack item);

    /** Идентификатор тира ({@code cursed..legendary}) или {@code null}. */
    String tierOf(ItemStack item);

    /** Шкала тиров от худшего к лучшему — для витрин и подсказок. */
    java.util.List<String> ladder();

    /**
     * Накладывает случайный тир по весам конфига. Предмет должен быть того вида,
     * который тир-система поддерживает (оружие, инструмент, броня).
     *
     * @param source источник для {@code /tiers info}: {@code treasure}, {@code quest} …
     * @return {@code true}, если тир наложен
     */
    boolean applyRandomTier(ItemStack item, String source);

    /**
     * Накладывает конкретный тир (админская выдача, квестовая награда).
     *
     * @return {@code true}, если тир наложен
     */
    boolean applyTier(ItemStack item, String tierId, String source);

    /**
     * Пересобирает уже выданный предмет по его тиру: атрибуты, свойства, лор.
     * Предмет без тира не трогается.
     */
    void refresh(ItemStack item);
}
