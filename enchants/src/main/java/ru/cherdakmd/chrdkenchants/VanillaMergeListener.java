package ru.cherdakmd.chrdkenchants;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Repairable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Слияние ванильных чар на наковальне без нашей книги (2.0.16): пара
 * «вещь+вещь», «вещь+ванильная книга», «книга+ванильная книга».
 *
 * <p>Ванильная наковальня зажимает уровни потолком чары: «защита 10» при
 * совмещении двух предметов превращалась в «защиту IV». Здесь ванильные чары
 * поддерживают значения выше ванильных максимумов: равные уровни сливаются в
 * +1 вплоть до потолка {@code rules.vanilla-level-cap}, входные уровни не
 * режутся. Слияние без единого усиления не выдаёт результат — как ванильная
 * наковальня.</p>
 *
 * <p>Влияние минимально: пара с нашей книгой живёт в
 * {@link AnvilApplyListener} (HIGH), механики кузницы перезаписывают свой
 * результат после нас (NORMAL). Ремонт, имена и конфликты решает ванильная
 * наковальня — здесь только уровни ванильных чар.</p>
 */
final class VanillaMergeListener implements Listener {

    private final EnchantsPlugin plugin;

    VanillaMergeListener(EnchantsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        ItemStack left = event.getInventory().getItem(0);
        ItemStack right = event.getInventory().getItem(1);
        if (left == null || right == null || left.getAmount() != 1 || right.getAmount() != 1) return;
        if (ourBook(left) || ourBook(right)) return; // наши пары — в AnvilApplyListener
        Map<Enchantment, Integer> leftMap = VanillaLevels.levels(left);
        Map<Enchantment, Integer> rightMap = VanillaLevels.levels(right);
        if (rightMap.isEmpty()) return; // ремонт или переименование без слияния чар
        int cap = levelCap();
        Map<Enchantment, Integer> merged = AnvilPrice.merge(leftMap, rightMap,
                enchantment -> Math.max(cap, enchantment.getMaxLevel()));
        ItemStack result = event.getResult();
        if (result != null) {
            restore(event, result, left, leftMap, merged);
        } else {
            fabricate(event, left, leftMap, merged);
        }
    }

    /** Pure vanilla book result only. Custom-book quotes are produced by AnvilApplyListener. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void visualResult(PrepareAnvilEvent event) {
        ItemStack left = event.getInventory().getItem(0), right = event.getInventory().getItem(1);
        ItemStack result = event.getResult();
        if (result == null || result.getType() != Material.ENCHANTED_BOOK
                || left != null && plugin.items().marked(left) || right != null && plugin.items().marked(right)) return;
        ItemStack out = result.clone();
        if (plugin.items().refreshBookVisual(out)) event.setResult(out);
    }

    /**
     * Нативный результат есть: поднимаем зажатые уровни до наших и гасим
     * результат, который отличается от левой вещи только зажатым падением чар.
     */
    private void restore(PrepareAnvilEvent event, ItemStack result, ItemStack left,
                         Map<Enchantment, Integer> leftMap, Map<Enchantment, Integer> merged) {
        Map<Enchantment, Integer> resultMap = VanillaLevels.levels(result);
        Map<Enchantment, Integer> patched = new LinkedHashMap<>();
        for (var entry : merged.entrySet()) {
            int now = resultMap.getOrDefault(entry.getKey(), 0);
            if (now > 0 && now < entry.getValue()) {
                patched.put(entry.getKey(), entry.getValue());
            }
        }
        if (patched.isEmpty()) return;
        if (!AnvilPrice.strengthens(merged, leftMap) && sameOutsideEnchants(result, left)) {
            event.setResult(null); // без усиления — без результата: зажатое падение не в счёт
            return;
        }
        ItemStack out = result.clone();
        VanillaLevels.applyLevels(out, patched);
        event.setResult(out);
    }

    /**
     * Нативного результата нет: ваниль зажала слияние до «без изменений».
     * Выдаём результат сами, если слияние усиливает хотя бы одну чару.
     */
    private void fabricate(PrepareAnvilEvent event, ItemStack left,
                           Map<Enchantment, Integer> leftMap, Map<Enchantment, Integer> merged) {
        ItemStack right = event.getInventory().getItem(1);
        boolean leftBook = left.getType() == Material.ENCHANTED_BOOK;
        boolean rightBook = right.getType() == Material.ENCHANTED_BOOK;
        if (leftBook && !rightBook) return; // книгу слева ваниль с вещью не сливает — и мы не будем
        if (!leftBook && left.getType() != right.getType()) {
            return; // вещи разных типов ваниль не сливает
        }
        if (!AnvilPrice.strengthens(merged, leftMap)) return;
        for (Enchantment first : merged.keySet()) {
            for (Enchantment second : merged.keySet()) {
                if (!first.equals(second)
                        && (first.conflictsWith(second) || second.conflictsWith(first))) {
                    return; // конфликт — в решении ванильной наковальни, не вмешиваемся
                }
            }
        }
        boolean bookTarget = left.getType() == Material.ENCHANTED_BOOK;
        for (var entry : merged.entrySet()) {
            if (!bookTarget && !leftMap.containsKey(entry.getKey())
                    && !entry.getKey().canEnchantItem(left)) {
                return; // новая чара не применима к вещи — ваниль бы её не перенесла
            }
        }
        int nativeCost = 0;
        for (var entry : merged.entrySet()) {
            if (entry.getValue() > leftMap.getOrDefault(entry.getKey(), 0)) {
                nativeCost += entry.getValue();
            }
        }
        int cost = (int) AnvilPrice.base(nativeCost, work(left), work(right), 0, true);
        boolean creative = event.getView().getPlayer() instanceof org.bukkit.entity.Player player
                && player.getGameMode() == GameMode.CREATIVE;
        if (!AnvilPrice.allowed(cost, event.getView().getMaximumRepairCost(), creative)) return;
        ItemStack out = left.clone();
        out.setAmount(1);
        VanillaLevels.applyLevels(out, merged);
        event.getView().setRepairItemCountCost(1);
        ru.cherdakmd.chrdkregister.api.AnvilCosts.price(event.getView(), cost);
        event.setResult(out);
    }

    /** Результат отличается от левой вещи только чарами (ремонт и имя — нет). */
    private static boolean sameOutsideEnchants(ItemStack result, ItemStack left) {
        if (result.getType() != left.getType() || result.getAmount() != left.getAmount()) return false;
        if (result.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable a
                && left.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable b
                && a.getDamage() != b.getDamage()) {
            return false;
        }
        return java.util.Objects.equals(
                result.getItemMeta() == null ? null : result.getItemMeta().displayName(),
                left.getItemMeta() == null ? null : left.getItemMeta().displayName());
    }

    private static int work(ItemStack item) {
        return item.getItemMeta() instanceof Repairable repairable ? repairable.getRepairCost() : 0;
    }

    private boolean ourBook(ItemStack item) {
        return item.getType() == Material.ENCHANTED_BOOK && plugin.items().marked(item);
    }

    private int levelCap() {
        return plugin.config() == null ? 10 : plugin.config().vanillaLevelCap();
    }
}
