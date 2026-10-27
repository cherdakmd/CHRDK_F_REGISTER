package ru.cherdakmd.chrdkenchants;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.inventory.view.AnvilView;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Кастомная доплата поверх native-цены, без самостоятельной выдачи/списания XP. */
public final class AnvilApplyListener implements Listener {
    private final EnchantsPlugin plugin;
    private final AnvilQuotes<AnvilView, ItemStack> quotes = new AnvilQuotes<>();
    private final Map<UUID, Object> notices = new HashMap<>();

    public AnvilApplyListener(EnchantsPlugin plugin) { this.plugin = plugin; }
    void clear() { quotes.clear(); notices.clear(); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        AnvilView view = event.getView();
        ItemStack left = event.getInventory().getItem(0);
        ItemStack right = event.getInventory().getItem(1);
        if (!(view.getPlayer() instanceof Player viewer)) return;
        var playerId = viewer.getUniqueId();
        if (!customBook(right)) {
            quotes.remove(playerId);
            return; // Починка/переименование без кастомной книги полностью остаются ванильными.
        }
        if (left == null || left.getAmount() != 1 || right.getAmount() != 1
                || !viewer.hasPermission("chrdkenchants.use") || plugin.coreApi() == null
                || !plugin.coreApi().isAuthenticated(viewer) || view.canBypassCost()
                || plugin.service().canApplyFromBook(left, right) != null) {
            reject(event, viewer);
            return;
        }
        boolean cumulative = cumulativeCost();
        boolean creative = viewer.getGameMode() == GameMode.CREATIVE;
        var previous = quotes.get(playerId);
        // Paper может повторно отправить PrepareResult после createResult при переименовании.
        // Это уже наш результат/цена: не сливаем уровень и не начисляем доплату повторно.
        if (previous != null && previous.source(view, left, right, view.getRenameText())
                && previous.couldBeRepeat(view.getRepairCost(), view.getRepairItemCountCost())) {
            if (!previous.output(event.getResult(), view.getRepairCost(), view.getRepairItemCountCost(),
                    view.getMaximumRepairCost(), plugin.sourceGeneration(), cumulative, creative)) {
                reject(event, viewer); // Изменённый сторонним обработчиком или устаревший preview.
            }
            return;
        }
        ItemStack vanilla = event.getResult();
        int vanillaCost = view.getRepairCost();
        boolean pureBook = right.getItemMeta() instanceof EnchantmentStorageMeta storage && !storage.hasStoredEnchants();
        // Null смешанной книги может означать конфликт vanilla, недопустимый предмет или Too Expensive.
        if (!AnvilPrice.nativeCompatible(pureBook, vanilla != null, vanillaCost)) {
            reject(event, viewer);
            return;
        }
        ItemStack result = vanilla == null ? left.clone() : vanilla.clone();
        if (result.getType() != left.getType() || result.getAmount() != 1
                || plugin.service().canApplyFromBook(result, right) != null) {
            reject(event, viewer);
            return;
        }
        int renameCost = pureBook && vanilla == null ? renamePlain(result, view.getRenameText())
                : Objects.equals(left.getItemMeta().displayName(), result.getItemMeta().displayName()) ? 0 : 1;
        Map<String, Integer> before = plugin.items().read(left);
        plugin.service().applyFromBook(result, right);
        Map<String, Integer> weights = new HashMap<>();
        for (String id : plugin.items().read(right).keySet()) {
            EnchantDef def = EnchantRegistry.byId(id);
            weights.put(id, switch (def.rarity()) {
                case COMMON -> 2;
                case RARE -> 4;
                case EPIC -> 7;
                case LEGENDARY -> 10;
            });
        }
        long surcharge = AnvilPrice.surcharge(before, plugin.items().read(result), weights);
        if (pureBook && surcharge == 0) {
            reject(event, viewer); // Не сжигаем книгу ради отсутствующего улучшения.
            return;
        }
        int leftWork = work(left), bookWork = work(right);
        if (leftWork < 0 || bookWork < 0) {
            reject(event, viewer);
            return;
        }
        int cost = AnvilPrice.total(AnvilPrice.base(vanillaCost, leftWork, bookWork, renameCost, pureBook), surcharge);
        if (surcharge > 0) {
            ItemMeta meta = result.getItemMeta();
            if (!(meta instanceof Repairable repairable)) {
                reject(event, viewer);
                return;
            }
            repairable.setRepairCost(AnvilPrice.nextWork(leftWork, bookWork, cumulative));
            result.setItemMeta(meta);
        }
        // Перенос чары НЕ является только переименованием: исключение native max-1 не применяется.
        ItemStack output = AnvilPrice.allowed(cost, view.getMaximumRepairCost(), creative) ? result : null;
        var quote = new AnvilQuotes.Quote<>(view, left.clone(), right.clone(), view.getRenameText(),
                output == null ? null : output.clone(), cost, view.getMaximumRepairCost(),
                plugin.sourceGeneration(), cumulative, creative, surcharge);
        if (!quotes.put(playerId, quote)) {
            reject(event, viewer);
            return;
        }
        view.setRepairItemCountCost(1);
        view.setRepairCost(cost);
        event.setResult(output);
    }

    /** Пользовательский read-only отчёт о реально открытой наковальне; предмет preview не выдаётся. */
    void preview(Player player) {
        if (!(player.getOpenInventory() instanceof AnvilView view)) { plugin.messages().send(player, "preview-open-anvil"); return; }
        var quote = quotes.get(player.getUniqueId());
        var left = view.getTopInventory().getItem(0); var right = view.getTopInventory().getItem(1);
        if (quote == null || !quote.source(view, left, right, view.getRenameText())
                || !quote.output(view.getTopInventory().getItem(2), view.getRepairCost(), view.getRepairItemCountCost(),
                    view.getMaximumRepairCost(), plugin.sourceGeneration(), cumulativeCost(), player.getGameMode() == GameMode.CREATIVE)
                || view.canBypassCost() || !player.hasPermission("chrdkenchants.use") || plugin.coreApi() == null
                || !plugin.coreApi().isAuthenticated(player) || plugin.service().canApplyFromBook(left, right) != null) {
            plugin.messages().send(player, "preview-unavailable"); return;
        }
        plugin.messages().send(player, "preview-price", "total", quote.cost(), "custom", quote.surcharge(),
                "native", quote.cost() == Integer.MAX_VALUE ? "не разделяется при насыщении" : Long.toString(Math.max(0L, quote.cost() - quote.surcharge())), "maximum", quote.maximum());
        boolean allowed = AnvilPrice.allowed(quote.cost(), quote.maximum(), player.getGameMode() == GameMode.CREATIVE);
        plugin.messages().send(player, allowed && (player.getGameMode() == GameMode.CREATIVE || player.getLevel() >= quote.cost())
                ? "preview-ready" : "preview-too-expensive");
        var before = plugin.items().read(left);
        for (var entry : plugin.items().read(right).entrySet()) {
            var def = EnchantRegistry.byId(entry.getKey()); int was = before.getOrDefault(def.id(), 0);
            plugin.messages().send(player, "preview-transfer", "name", plugin.messages().mini("en." + def.id() + ".name"),
                    "before", was, "after", AnvilPrice.merge(was, entry.getValue(), def.maxLevel()));
        }
    }

    private void reject(PrepareAnvilEvent event, Player viewer) {
        quotes.remove(viewer.getUniqueId());
        event.setResult(null);
        event.getView().setRepairCost(-1);
    }

    private boolean customBook(ItemStack right) {
        return right != null && right.getType() == Material.ENCHANTED_BOOK && plugin.items().marked(right);
    }

    private static int work(ItemStack item) {
        return item.getItemMeta() instanceof Repairable repairable ? repairable.getRepairCost() : 0;
    }

    private static boolean cumulativeCost() {
        // Только чтение действующего Purpur config; не загружаем второй файл и не меняем серверные флаги.
        return Bukkit.spigot().getPurpurConfig().getBoolean("settings.blocks.anvil.cumulative-cost", true);
    }

    /** Для чистой книги native может вернуть null до обработки имени. Никакого MiniMessage/цветовых bypass. */
    private static int renamePlain(ItemStack item, String rename) {
        ItemMeta meta = item.getItemMeta();
        if (rename == null || rename.isBlank()) {
            if (!meta.hasDisplayName()) return 0;
            meta.displayName(null);
        } else {
            String name = meta.hasDisplayName()
                    ? PlainTextComponentSerializer.plainText().serialize(meta.displayName()) : item.getI18NDisplayName();
            if (rename.equals(name)) return 0;
            meta.displayName(Component.text(rename));
        }
        item.setItemMeta(meta);
        return 1;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTakeResult(InventoryClickEvent event) {
        if (!(event.getView() instanceof AnvilView view) || event.getRawSlot() != 2
                || !(event.getWhoClicked() instanceof Player player)) return;
        ItemStack right = event.getInventory().getItem(1);
        ItemStack result = event.getCurrentItem();
        ItemStack left = event.getInventory().getItem(0);
        var quote = quotes.get(player.getUniqueId());
        if (customBook(right) || quote != null && quote.view() == view) {
            if (!customBook(right) || left == null || result == null || quote == null
                    || !quote.source(view, left, right, view.getRenameText())
                    || !quote.output(result, view.getRepairCost(), view.getRepairItemCountCost(),
                        view.getMaximumRepairCost(), plugin.sourceGeneration(), cumulativeCost(),
                        player.getGameMode() == GameMode.CREATIVE)
                    || !AnvilPrice.allowed(view.getRepairCost(), view.getMaximumRepairCost(),
                        player.getGameMode() == GameMode.CREATIVE)
                    || view.canBypassCost() || !player.hasPermission("chrdkenchants.use")
                    || plugin.coreApi() == null || !plugin.coreApi().isAuthenticated(player)
                    || plugin.service().canApplyFromBook(left, right) != null) {
                event.setCancelled(true);
                return;
            }
        }
        if (result == null || left == null || !result.hasItemMeta()
                || event.getAction() == InventoryAction.NOTHING
                || (player.getGameMode() != GameMode.CREATIVE && player.getLevel() < view.getRepairCost())) return;
        Map<String, Integer> before = plugin.items().read(left);
        boolean newCurse = plugin.items().read(result).entrySet().stream()
                .anyMatch(entry -> EnchantRegistry.byId(entry.getKey()) != null
                        && EnchantRegistry.byId(entry.getKey()).curse()
                        && entry.getValue() > before.getOrDefault(entry.getKey(), 0));
        if (!newCurse || notices.size() >= AnvilQuotes.LIMIT) return;
        Object ticket = new Object();
        if (notices.putIfAbsent(player.getUniqueId(), ticket) != null) return;
        long generation = plugin.sourceGeneration();
        // Native сам переносит предмет, списывает уровни и ровно одну книгу; поздняя отмена сохраняется.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!notices.remove(player.getUniqueId(), ticket)) return;
            if (!event.isCancelled() && player.isOnline() && plugin.sourceGeneration() == generation
                    && view.getTopInventory().getItem(0) == null && view.getTopInventory().getItem(1) == null) {
                plugin.messages().send(player, "anvil-curse-applied");
            }
        });
    }

    private void forget(UUID player) { quotes.remove(player); notices.remove(player); }
    @EventHandler public void onClose(InventoryCloseEvent event) { forget(event.getPlayer().getUniqueId()); }
    @EventHandler public void onQuit(PlayerQuitEvent event) { forget(event.getPlayer().getUniqueId()); }
}
