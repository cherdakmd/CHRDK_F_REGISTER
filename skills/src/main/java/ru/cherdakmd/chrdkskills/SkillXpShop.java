package ru.cherdakmd.chrdkskills;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.cherdakmd.chrdkregister.api.PaidActions;

import java.io.IOException;

/**
 * Покупка кристаллов опыта в меню {@code /skills}. Оплата идёт через общий
 * движок платных действий ({@link PaidActions}): durable-намерение BankCharge,
 * идемпотентная выдача с {@code saveData} и полный refund при неудаче выдачи
 * или рестарте до неё. Кристаллы сняты из «Кузни» /shop — это единственный
 * платный источник, дроп с мобов и действий остаётся бесплатным. Идентификатор
 * услуги {@code skill-xp-potion} сохранён ради незавершённых покупок в журнале.
 */
final class SkillXpShop {
    private final SkillsPlugin plugin;
    private final PaidActions payments;

    SkillXpShop(SkillsPlugin plugin) throws IOException {
        this.plugin = plugin;
        this.payments = new PaidActions(plugin, plugin::coreApi, plugin::messages, "skill-xp");
    }

    /** Общий движок оплаты: сверка, refund и диагностика. */
    PaidActions payments() {
        return payments;
    }

    /** {@code true} — кристалл выдан и оплачен; {@code false} — отказ или refund. */
    boolean buy(Player player, SkillType skill) {
        if (player == null || skill == null || !shopEnabled()) return false;
        int price = price();
        if (price < 1) return false;
        return payments.perform(player, player, price, "skill-xp-potion", skill.id(),
                () -> give(player, skill));
    }

    /** Выдача одного кристалла; {@code false} — нет места, оплата вернётся игроку. */
    private boolean give(Player player, SkillType skill) {
        ItemStack crystal = plugin.xpItems().create(skill);
        if (!player.getInventory().addItem(crystal).isEmpty()) return false;
        // Позиция сохраняется до закрытия резерва услуги; исключение saveData
        // остаётся неизвестным исходом и выясняется по журналу действий.
        player.saveData();
        return true;
    }

    boolean shopEnabled() {
        return plugin.config().enabled()
                && plugin.getConfig().getBoolean("xp-potions.shop-enabled", true);
    }

    int price() {
        return plugin.getConfig().getInt("xp-potions.price", 96);
    }
}
