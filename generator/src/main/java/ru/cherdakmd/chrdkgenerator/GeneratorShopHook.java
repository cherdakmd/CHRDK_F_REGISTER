package ru.cherdakmd.chrdkgenerator;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import ru.cherdakmd.diamondshop.DiamondShopPlugin;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Единственный класс модуля, который ссылается на бинарный API
 * CHRDK_DiamondShop. Загружается JVM только после того, как вызывающий код
 * убедился, что софт-зависимость включена (иначе — {@code NoClassDefFoundError}).
 *
 * <p>Автопродажа продукции оценивается по текущей цене продажи магазина
 * (bid): котировка не трогает инвентарь, банк и оборот магазина. Магазин
 * недоступен или позиция вне каталога — вызывающая сторона берёт
 * fallback-цену конфига.</p>
 */
final class GeneratorShopHook {

    static final String PLUGIN = "CHRDK_DiamondShop";

    private GeneratorShopHook() {
    }

    /**
     * Цена продажи {@code count} предметов магазину, minor.
     *
     * @return {@code -1} — магазина нет, позиция не продаётся или магазин несовместим
     */
    static long sellValue(Logger logger, String material, int count) {
        try {
            Plugin found = Bukkit.getPluginManager().getPlugin(PLUGIN);
            if (!(found instanceof DiamondShopPlugin shop) || !shop.isEnabled()) {
                return -1L;
            }
            var item = shop.shopService().config().item(material);
            if (item.isEmpty() || !item.get().sellEnabled()) {
                return -1L;
            }
            long quote = shop.shopService().quantityQuote(item.get(), count, false);
            return quote < 0 ? -1L : quote;
        } catch (LinkageError | RuntimeException error) {
            logger.log(Level.WARNING,
                    "[CHRDK_Generator] CHRDK_DiamondShop несовместим: автопродажа по fallback-цене", error);
            return -1L;
        }
    }
}
