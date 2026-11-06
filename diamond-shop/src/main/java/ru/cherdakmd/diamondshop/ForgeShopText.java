package ru.cherdakmd.diamondshop;

import net.kyori.adventure.text.Component;
import ru.cherdakmd.chrdkregister.Messages;

import java.util.List;

/**
 * Человекочитаемые названия и назначение расходников Кузни.
 *
 * <p>Раньше подсказка витрины показывала только {@code SKU: repair} и цену,
 * поэтому игрок не понимал, зачем покупает «Кузнечный лом». Теперь витрина,
 * окно подтверждения и сообщение о покупке берут русское имя и две строки
 * назначения из {@code messages.yml} по id SKU. Если владелец ещё не обновил
 * свой файл текстов, откатываемся на сам id — пустой строки не будет.</p>
 */
final class ForgeShopText {

    private ForgeShopText() {
    }

    /** Русское название расходника без разметки: подставляется как %name%. */
    static String name(Messages messages, String sku) {
        if (!messages.has("forge.shop.name." + sku)) {
            return sku;
        }
        return Messages.stripTags(messages.raw("forge.shop.name." + sku));
    }

    /** Две строки: что делает предмет и как его применять в наковальне. */
    static List<Component> purpose(Messages messages, String sku) {
        if (!messages.has("forge.shop.purpose." + sku)) {
            return List.of();
        }
        return messages.getList("forge.shop.purpose." + sku);
    }
}
