package ru.cherdakmd.chrdkenchants;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Выдача чар из источников (стол, жители, наковальня): фильтр применимости, взвешенный
 * бросок, уровень. Объединяет реестр, конфиг и PDC-механику.
 */
public final class EnchantService {

    private final EnchantsPlugin plugin;
    private final EnchantItems items;
    private final Random random = new Random();

    public EnchantService(EnchantsPlugin plugin, EnchantItems items) {
        this.plugin = plugin;
        this.items = items;
    }

    public Random random() {
        return random;
    }

    /** Все активные в конфиге чары для материала, которые ещё можно добавить. */
    public List<EnchantDef> applicableTo(ItemStack item) {
        String material = item.getType().name();
        Map<String, Integer> present = items.read(item);
        return EnchantRoller.applicable(plugin.config().enabledEnchants(), material, present,
                plugin.config().maxPerItem(), plugin.config().maxLegendaryPerItem());
    }

    /**
     * Пытается вогнать случайную чару в предмет (проверки источников шанса — на вызывающем).
     *
     * @param costTier щедрость источника 1..3 (полная стойка книг — 3)
     * @return добавленная чара и уровень, null = выдасть не удалось (нет кандидатов)
     */
    public RollResult rollOnto(ItemStack item, int costTier) {
        List<EnchantDef> candidates = applicableTo(item);
        EnchantDef picked = EnchantRoller.pickWeighted(candidates, random);
        if (picked == null) {
            return null;
        }
        int level = EnchantRoller.rollLevel(picked, costTier, random);
        items.apply(item, picked, level);
        return new RollResult(picked, level);
    }

    /** Случайная чара для книги (учитывает только вкл/выкл; применимость не важна). */
    public RollResult rollBook(int costTier) {
        EnchantDef picked = EnchantRoller.pickWeighted(plugin.config().enabledEnchants(), random);
        if (picked == null) {
            return null;
        }
        return new RollResult(picked, EnchantRoller.rollLevel(picked, costTier, random));
    }

    /**
     * Проверяет, можно ли перенести чары книги на предмет (правила лимита и конфликтов).
     *
     * @return null = можно; иначе текстовый код причины для сообщения
     */
    public String canApplyFromBook(ItemStack item, ItemStack book) {
        if (!items.isEnchantBook(book)) {
            return "not-book";
        }
        Map<String, Integer> bookEnchants = items.read(book);
        Map<String, Integer> itemEnchants = items.read(item);
        for (String id : bookEnchants.keySet()) {
            EnchantDef def = EnchantRegistry.byId(id);
            if (def == null || !plugin.config().isEnabled(id)) {
                return "disabled";
            }
            if (!def.supports(item.getType().name())) {
                return "mismatch";
            }
            Map<String, Integer> hypothetical = new java.util.HashMap<>(itemEnchants);
            hypothetical.remove(id);
            List<EnchantDef> rest = EnchantRoller.applicable(List.of(def),
                    item.getType().name(), hypothetical,
                    plugin.config().maxPerItem(), plugin.config().maxLegendaryPerItem());
            if (rest.isEmpty()) {
                return "blocked";
            }
            // добавление даже при замене существующей той же чары — пересчёт лимита:
            if (!itemEnchants.containsKey(id) && itemEnchants.size() >= plugin.config().maxPerItem()) {
                return "blocked";
            }
        }
        if (item.getType() == Material.BOOK || item.getType() == Material.AIR) {
            return "mismatch";
        }
        return null;
    }

    /** Переносит чары из книги на предмет (вызывающий проверил canApplyFromBook). */
    public void applyFromBook(ItemStack item, ItemStack book) {
        Map<String, Integer> enchants = new java.util.LinkedHashMap<>(items.read(item));
        items.read(book).forEach((id, level) -> {
            EnchantDef def = EnchantRegistry.byId(id);
            if (def == null) return;
            int existing = enchants.getOrDefault(id, 0);
            // наковальня: одинаковые чары сливаются как ваниль (max или +1 уровень, не выше потолка)
            int merged = existing > 0 && existing == level
                    ? Math.min(def.maxLevel(), level + 1)
                    : Math.max(existing, level);
            enchants.put(id, merged);
        });
        items.write(item, enchants);
    }

    /** Итог выдачи: какая чара и какой уровень. */
    public record RollResult(EnchantDef def, int level) {
    }
}
