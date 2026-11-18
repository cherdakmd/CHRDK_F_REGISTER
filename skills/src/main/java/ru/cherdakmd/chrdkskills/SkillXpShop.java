package ru.cherdakmd.chrdkskills;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import ru.cherdakmd.chrdkregister.api.PaidActions;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Покупка кристаллов опыта в меню {@code /skills}. Оплата идёт через общий
 * движок платных действий ({@link PaidActions}): durable-намерение BankCharge,
 * идемпотентная выдача с {@code saveData} и полный refund при неудаче выдачи
 * или рестарте до неё. Кристаллы сняты из «Кузни» /shop — это единственный
 * платный источник, дроп с мобов и действий остаётся бесплатным. Идентификатор
 * услуги {@code skill-xp-potion} сохранён ради незавершённых покупок в журнале.
 *
 * <p>Покупка бывает по одной штуке (обычный клик) и стаком (Shift+клик):
 * {@code xp-potions.shop-stack-size} штук одним durable-намерением. Между
 * кликами по кристаллам магазина действует короткая задержка
 * {@code xp-potions.shop-click-cooldown-ms} — защита от двойных покупок.</p>
 */
final class SkillXpShop {
    private final SkillsPlugin plugin;
    private final PaidActions payments;
    /** Последние клики покупки по игрокам; после рестарта задержка начинается заново. */
    private final Map<UUID, Long> clicks = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<UUID, Long> eldest) {
            return size() > 10_000;
        }
    };

    SkillXpShop(SkillsPlugin plugin) throws IOException {
        this.plugin = plugin;
        this.payments = new PaidActions(plugin, plugin::coreApi, plugin::messages, "skill-xp");
    }

    /** Общий движок оплаты: сверка, refund и диагностика. */
    PaidActions payments() {
        return payments;
    }

    /** {@code true} — один кристалл выдан и оплачен; {@code false} — отказ или refund. */
    boolean buy(Player player, SkillType skill) {
        return buy(player, skill, 1);
    }

    /**
     * {@code true} — count кристаллов выданы и оплачены; {@code false} — отказ
     * или refund. Между кликами одного игрока выдерживается задержка магазина.
     */
    boolean buy(Player player, SkillType skill, int count) {
        if (player == null || skill == null || !shopEnabled()) return false;
        if (!acceptClick(player.getUniqueId())) return false;
        count = Math.max(1, Math.min(stackSize(), count));
        long total = (long) price() * count;
        if (total < 1L || total > Integer.MAX_VALUE) return false;
        int price = (int) total;
        return payments.perform(player, player, price, "skill-xp-potion", skill.id(),
                () -> give(player, skill, count));
    }

    /** Выдача count кристаллов одним стаком; {@code false} — нет места, оплата вернётся игроку. */
    private boolean give(Player player, SkillType skill, int count) {
        ItemStack crystal = plugin.xpItems().create(skill);
        crystal.setAmount(count);
        if (!fits(player.getInventory(), crystal, count)) return false;
        if (!player.getInventory().addItem(crystal).isEmpty()) return false;
        // Позиция сохраняется до закрытия резерва услуги; исключение saveData
        // остаётся неизвестным исходом и выясняется по журналу действий.
        player.saveData();
        return true;
    }

    /**
     * Проверка места до выдачи: addItem с частичным остатком выдал бы часть
     * стака и вернул бы оплату целиком — дусплей. Полные штабели и пустые слоты
     * вмещают не меньше {@code amount} штук одинакового предмета.
     */
    private static boolean fits(Inventory inventory, ItemStack item, int amount) {
        long capacity = 0;
        for (ItemStack stack : inventory.getStorageContents()) {
            if (stack == null || stack.getType().isAir()) {
                capacity += 64;
            } else if (stack.isSimilar(item)) {
                capacity += 64 - stack.getAmount();
            }
            if (capacity >= amount) return true;
        }
        return capacity >= amount;
    }

    /** Задержка между кликами покупки: {@code false} — клик слишком частый, молча пропускаем. */
    private boolean acceptClick(UUID player) {
        long now = System.currentTimeMillis();
        long cooldown = clickCooldownMillis();
        Long previous = clicks.get(player);
        if (cooldown > 0 && previous != null && now - previous < cooldown) return false;
        clicks.put(player, now);
        return true;
    }

    boolean shopEnabled() {
        return plugin.config().enabled()
                && plugin.getConfig().getBoolean("xp-potions.shop-enabled", true);
    }

    int price() {
        return plugin.getConfig().getInt("xp-potions.price", 96);
    }

    /** Размер стака при Shift+покупке: 1–64, по умолчанию полный стак. */
    int stackSize() {
        return Math.max(1, Math.min(64, plugin.getConfig().getInt("xp-potions.shop-stack-size", 64)));
    }

    /** Задержка между кликами покупки в магазине, миллисекунды (0 — без задержки). */
    long clickCooldownMillis() {
        return Math.max(0, plugin.getConfig().getInt("xp-potions.shop-click-cooldown-ms", 750));
    }
}
