package ru.cherdakmd.chrdkitems;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import java.util.Map;
import org.bukkit.persistence.PersistentDataType;

/** Synchronous on the command thread; only the three cosmetic/information consumables are spent. */
final class PrototypeUse {
    static void use(Player player, Map<String, ItemCatalog.Definition> catalog, boolean enabled) {
        var inventory = player.getInventory();
        var item = inventory.getItemInMainHand();
        if (item.getType().isAir() || !item.hasItemMeta()) {
            player.sendMessage(Component.text("Возьмите прототип в основную руку.")); return;
        }
        var id = item.getItemMeta().getPersistentDataContainer().get(PrototypeItems.ID, PersistentDataType.STRING);
        var definition = id == null ? null : catalog.get(id);
        if (BuiltinUse.supports(definition) && PrototypeEffects.identityMatches(item, definition)) {
            try {
                String text = BuiltinUse.describe(definition, player);
                player.sendMessage(Component.text(text == null ? "Данные недоступны." : text));
            } catch (RuntimeException e) { player.sendMessage(Component.text("Не удалось получить данные.")); }
            return;
        }
        if (!enabled) { player.sendMessage(Component.text("Тестовые свойства выключены на сервере.")); return; }
        var effect = PrototypeEffects.effect(definition);
        if (effect == null || !PrototypeEffects.identityMatches(item, definition)) {
            player.sendMessage(Component.text("У этого предмета нет доступного тестового свойства.")); return;
        }
        final String text;
        try { text = PrototypeEffects.describe(effect, player); }
        catch (RuntimeException e) {
            player.sendMessage(Component.text("Не удалось проверить свойство; предмет не расходован.")); return;
        }
        var plan = plan(effect, item.getAmount(), text);
        if (plan == null) { player.sendMessage(Component.text("Данных нет; предмет не расходован.")); return; }
        if (plan.consume()) {
            // Build replacement before writing: no partial decrement or drop on failure.
            var remainder = plan.remaining() == 0 ? null : item.clone();
            if (remainder != null) remainder.setAmount(plan.remaining());
            inventory.setItemInMainHand(remainder);
        }
        player.sendMessage(Component.text(plan.text()));
    }

    record Plan(String text, boolean consume, int remaining) { }
    static Plan plan(PrototypeEffects.Effect effect, int amount, String text) {
        if (effect == null || amount < 1 || text == null || text.isBlank()) return null;
        return new Plan(text, effect.consumable, amount - (effect.consumable ? 1 : 0));
    }
}
