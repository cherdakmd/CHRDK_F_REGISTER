package ru.cherdakmd.chrdkregister.api;

import org.bukkit.inventory.ItemStack;

/**
 * API42: живой производитель расходников Кузни (регистрируется CHRDK_TierForge).
 *
 * <p>Нужен только для оформления НОВОЙ покупки в витрине магазина. Восстановление
 * уже принятой сделки пользуется замороженным {@link ShopExchange#grant()} и не
 * обращается к этому сервису: отключённый аддон или изменившиеся цены не должны
 * останавливать WAL. Прототип всегда настоящий tagged ItemStack с PDC.</p>
 */
public interface ForgeMaterialSource {

    /** Идентификатор производителя для диагностики. */
    String provider();

    /** Включена ли операция/товар у производителя. */
    boolean available(String sku);

    /** Один PDC-прототип со слотом 1; для искры — уникальный canonical UUID и max-stack-size=1. */
    ItemStack prototype(String sku);
}
