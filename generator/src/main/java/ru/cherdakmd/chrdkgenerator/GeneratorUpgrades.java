package ru.cherdakmd.chrdkgenerator;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Покупка улучшений генератора (п.31, §5): деньги и ресурсы вместе.
 *
 * <p>Деньги сжигаются через банк (sink): платёжный протокол
 * {@code generator-upgrades} списывает только банк, касса и сдача не
 * участвуют. Ресурсы — списанием из инвентаря. Низкие уровни модулей
 * стоят ресурсов, высшие — денег; каждый шаг тира требует и деньги, и
 * ресурсы. Модуль «Чанк» — разовая покупка.</p>
 */
final class GeneratorUpgrades {

    private final GeneratorPlugin plugin;

    GeneratorUpgrades(GeneratorPlugin plugin) {
        this.plugin = plugin;
    }

    /** Цена шага тира: {@code nextTier} 2…7 (индекс 0 — переход 1→2). */
    static GeneratorConfig.Cost tierCost(GeneratorConfig config, int nextTier) {
        return config.tierCosts().get(nextTier - 2);
    }

    /** Цена покупки уровня модуля: {@code nextLevel} 1…5 (индекс 0 — уровень 1). */
    static GeneratorConfig.Cost moduleCost(GeneratorConfig config, int nextLevel) {
        return config.moduleCosts().get(nextLevel - 1);
    }

    /** Человекочитаемая цена для сообщений: «5000 ₽ + IRON_INGOT × 256». */
    static String describe(GeneratorConfig.Cost cost) {
        List<String> parts = new ArrayList<>();
        if (cost.money() > 0) {
            parts.add(cost.money() + " ₽");
        }
        for (GeneratorConfig.Resource resource : cost.resources()) {
            parts.add(resource.material() + " × " + resource.amount());
        }
        return String.join(" + ", parts);
    }

    /** Сколько предметов есть в инвентаре (без брони и offhand). */
    private static int count(Player player, String material) {
        Material type = Material.matchMaterial(material);
        if (type == null) {
            return 0;
        }
        int total = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && item.getType() == type) {
                total += item.getAmount();
            }
        }
        return total;
    }

    private static boolean hasResources(Player player, GeneratorConfig.Cost cost) {
        for (GeneratorConfig.Resource resource : cost.resources()) {
            if (count(player, resource.material()) < resource.amount()) {
                return false;
            }
        }
        return true;
    }

    private static void consume(Player player, GeneratorConfig.Cost cost) {
        for (GeneratorConfig.Resource resource : cost.resources()) {
            Material type = Material.matchMaterial(resource.material());
            int remaining = resource.amount();
            ItemStack[] contents = player.getInventory().getStorageContents();
            for (int i = 0; i < contents.length && remaining > 0; i++) {
                ItemStack item = contents[i];
                if (item == null || item.getType() != type) {
                    continue;
                }
                int take = Math.min(remaining, item.getAmount());
                item.setAmount(item.getAmount() - take);
                if (item.getAmount() <= 0) {
                    contents[i] = null;
                }
                remaining -= take;
            }
            player.getInventory().setStorageContents(contents);
        }
    }

    /** Повышение тира: деньги + ресурсы, тир растёт на 1 (до 7). */
    void upgradeTier(Player player) {
        GeneratorStore.Entry entry = plugin.store().entry(player.getUniqueId());
        if (entry == null) {
            plugin.messages().send(player, "upgrade-none");
            return;
        }
        if (!plugin.nearCore(player, entry)) {
            plugin.messages().send(player, "command-too-far",
                    "range", String.valueOf(plugin.config().menuRange()));
            return;
        }
        int next = entry.tier() + 1;
        if (next > GeneratorConfig.TIERS) {
            plugin.messages().send(player, "upgrade-max", "what", plugin.messages().plain("upgrade-name-tier"));
            return;
        }
        GeneratorConfig.Cost cost = tierCost(plugin.config(), next);
        String what = plugin.config().tier(next).name();
        buy(player, what, String.valueOf(next), cost, "generator-tier", "tier:" + next,
                () -> plugin.store().setTier(player.getUniqueId(), next));
    }

    /** Покупка следующего уровня модуля «Скорость»/«Ёмкость»/«Удача». */
    void upgradeModule(Player player, String name) {
        GeneratorStore.Entry entry = plugin.store().entry(player.getUniqueId());
        if (entry == null) {
            plugin.messages().send(player, "upgrade-none");
            return;
        }
        if (!plugin.nearCore(player, entry)) {
            plugin.messages().send(player, "command-too-far",
                    "range", String.valueOf(plugin.config().menuRange()));
            return;
        }
        int level = entry.modules().getOrDefault(name, 0);
        int next = level + 1;
        if (next > GeneratorStore.MAX_LEVEL) {
            plugin.messages().send(player, "upgrade-max", "what", moduleName(name));
            return;
        }
        GeneratorConfig.Cost cost = moduleCost(plugin.config(), next);
        buy(player, moduleName(name), String.valueOf(next), cost,
                "generator-module", name + ":" + next,
                () -> plugin.store().setModuleLevel(player.getUniqueId(), name, next));
    }

    /** Разовая покупка модуля «Чанк» (plugin-chunk-ticket). */
    void buyChunk(Player player) {
        GeneratorStore.Entry entry = plugin.store().entry(player.getUniqueId());
        if (entry == null) {
            plugin.messages().send(player, "upgrade-none");
            return;
        }
        if (!plugin.nearCore(player, entry)) {
            plugin.messages().send(player, "command-too-far",
                    "range", String.valueOf(plugin.config().menuRange()));
            return;
        }
        if (entry.modules().getOrDefault("chunk", 0) > 0) {
            plugin.messages().send(player, "upgrade-max", "what", moduleName("chunk"));
            return;
        }
        buy(player, moduleName("chunk"), "1", plugin.config().chunkCost(),
                "generator-module", "chunk:1",
                () -> plugin.store().setModuleLevel(player.getUniqueId(), "chunk", 1));
    }

    private String moduleName(String name) {
        return plugin.messages().plain("upgrade-name-" + name);
    }

    /**
     * Общий каркас покупки: ресурсы проверяются до платежа и списываются в
     * исполнении; отказ исполнения возвращает деньги (протокол оплаты).
     */
    private void buy(Player player, String what, String level, GeneratorConfig.Cost cost,
                     String kind, String payload, Runnable apply) {
        if (!hasResources(player, cost)) {
            plugin.messages().send(player, "upgrade-no-resources", "need", describe(cost));
            return;
        }
        if (cost.money() > 0) {
            boolean ok = plugin.payments().perform(player, player,
                    Math.toIntExact(cost.money()), kind, payload, () -> {
                        if (!hasResources(player, cost)) {
                            return false;
                        }
                        consume(player, cost);
                        apply.run();
                        return true;
                    });
            if (ok) {
                plugin.messages().send(player, "upgrade-ok", "what", what, "level", level);
            }
            return;
        }
        consume(player, cost);
        apply.run();
        plugin.messages().send(player, "upgrade-ok", "what", what, "level", level);
    }
}
