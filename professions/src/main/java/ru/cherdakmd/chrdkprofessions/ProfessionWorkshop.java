package ru.cherdakmd.chrdkprofessions;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;

import java.util.Arrays;
import java.util.Set;

/**
 * Приёмы работают с собственным рюкзаком: не трогают блоки, печи, чужие контейнеры
 * или сущности и не создают фиктивных событий крафта. Сначала полный план на копии,
 * затем один коммит. Нет места/сырья — ни один предмет и таймер не изменены.
 */
final class ProfessionWorkshop {
    private static final Set<String> TIER_KEYS = Set.of("item_tier", "item_tier_source");
    private final ProfessionsPlugin plugin;

    ProfessionWorkshop(ProfessionsPlugin plugin) { this.plugin = plugin; }

    void perform(Player player) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Workshop requires server thread");
        if (player == null || !player.isOnline()) return;
        if (!player.hasPermission("chrdkprofessions.use") || !plugin.authenticated(player)) {
            plugin.messages().send(player, "no-permission");
            return;
        }
        if (player.getGameMode() != GameMode.SURVIVAL
                || player.getOpenInventory().getTopInventory().getType() != InventoryType.CRAFTING
                || !empty(player.getItemOnCursor())) {
            plugin.messages().send(player, "work-survival");
            return;
        }
        var service = plugin.service();
        var check = service.checkMastery(player.getUniqueId());
        if (!check.ok()) { send(player, check); return; }
        ProfessionMastery mastery = service.mastery(player.getUniqueId());
        if (mastery == null) { plugin.messages().send(player, "mastery-needed"); return; }
        long now = System.currentTimeMillis();
        long left = service.workCooldownLeft(player.getUniqueId(), now);
        if (left > 0) {
            plugin.messages().send(player, "work-cooldown", "time", ProfessionMath.clock(left));
            return;
        }
        ItemStack[] original = copy(player.getInventory().getStorageContents());
        ItemStack[] planned = copy(original);
        int selected = player.getInventory().getHeldItemSlot();
        ItemStack held = planned[selected];
        if (empty(held)) { plugin.messages().send(player, "work-invalid"); return; }
        Result result = prepare(mastery, planned, selected);
        if (!result.ok()) { plugin.messages().send(player, result.error()); return; }
        // Никаких отложенных списаний и изменений чужих инвентарей.
        if (!Arrays.equals(original, player.getInventory().getStorageContents())) {
            plugin.messages().send(player, "work-changed");
            return;
        }
        try {
            player.getInventory().setStorageContents(planned);
        } catch (RuntimeException exception) {
            player.getInventory().setStorageContents(original);
            plugin.getLogger().warning("Не удалось применить приём профессии: " + exception.getMessage());
            plugin.messages().send(player, "work-changed");
            return;
        }
        if (!plugin.store().setWorkUntil(player.getUniqueId(), mastery.profession(), now + mastery.cooldownSeconds() * 1000L)) {
            player.getInventory().setStorageContents(original);
            plugin.messages().send(player, "mastery-storage-full");
            return;
        }
        plugin.requestSave();
        if (result.repaired() > 0) {
            plugin.messages().send(player, "work-repaired", "amount", result.repaired());
        } else {
            plugin.messages().send(player, "work-complete", "amount", result.amount(),
                    "item", Component.translatable(result.output().translationKey()));
        }
    }

    private Result prepare(ProfessionMastery mastery, ItemStack[] contents, int selected) {
        ItemStack held = contents[selected];
        if (mastery.profession() == Profession.SMITH) return repair(mastery, contents, selected);
        String target = ProfessionWorkRecipes.output(mastery, held.getType().name());
        if (target == null) return Result.fail("work-invalid");
        Material output = Material.matchMaterial(target);
        if (output == null) return Result.fail("work-invalid");
        if (mastery.profession() == Profession.ALCHEMIST) {
            if (!ordinary(held) || !(held.getItemMeta() instanceof PotionMeta potion)
                    || potion.getBasePotionType() == null || potion.hasCustomEffects()) return Result.fail("work-special");
            String catalyst = mastery == ProfessionMastery.GRENADIER ? "GUNPOWDER" : "DRAGON_BREATH";
            ItemStack fingerprint = held.clone();
            int amount = 0;
            for (int i = 0; i < contents.length && amount < mastery.batch(); i++) {
                ItemStack item = contents[i];
                if (empty(item) || !item.isSimilar(fingerprint)) continue;
                int take = Math.min(item.getAmount(), mastery.batch() - amount);
                ItemStack converted = new ItemStack(output, take);
                if (!converted.setItemMeta(item.getItemMeta())) return Result.fail("work-special");
                removeAt(contents, i, take);
                if (!add(contents, converted)) return Result.fail("work-space");
                amount += take;
            }
            if (amount == 0) return Result.fail("work-invalid");
            if (!consume(contents, Material.valueOf(catalyst), 1)) return Result.fail("work-materials");
            // В варке дыхание дракона возвращает стеклянный флакон.
            if (mastery == ProfessionMastery.DISTILLER && !add(contents, new ItemStack(Material.GLASS_BOTTLE)))
                return Result.fail("work-space");
            return new Result("", output, amount, 0);
        }
        if (!ordinary(held)) return Result.fail("work-special");
        int count = Math.min(held.getAmount(), mastery.batch());
        removeAt(contents, selected, count);
        if (mastery != ProfessionMastery.FINISHER
                && !consume(contents, Material.COAL, ProfessionWorkRecipes.fuel(count))) return Result.fail("work-materials");
        if (!add(contents, new ItemStack(output, count))) return Result.fail("work-space");
        return new Result("", output, count, 0);
    }

    private Result repair(ProfessionMastery mastery, ItemStack[] contents, int selected) {
        ItemStack held = contents[selected];
        String ingredient = ProfessionWorkRecipes.repairMaterial(held.getType().name());
        if (held.getAmount() != 1 || ingredient == null || !(held.getItemMeta() instanceof Damageable damage)
                || damage.isUnbreakable() || !damage.hasDamage()) return Result.fail("work-invalid");
        int max = damage.hasMaxDamage() ? damage.getMaxDamage() : held.getType().getMaxDurability();
        int restored = ProfessionWorkRecipes.repair(max, damage.getDamage(), mastery.repairPercent());
        if (restored == 0) return Result.fail("work-invalid");
        if (!consume(contents, Material.valueOf(ingredient), 1)) return Result.fail("work-materials");
        // Мета клонирована вместе со стеком: чары, атрибуты, имя, PDC и тир сохраняются.
        damage.setDamage(Math.max(0, damage.getDamage() - restored));
        if (!held.setItemMeta(damage)) return Result.fail("work-special");
        return new Result("", held.getType(), 1, restored);
    }

    /** Защита особых ресурсов. Декоративная редкость TierForge — не особый рецепт. */
    static boolean ordinary(ItemStack item) {
        if (empty(item)) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return true;
        if (meta.hasEnchants() || meta.hasCustomModelData() || meta.isUnbreakable() || meta.hasCustomName()) return false;
        boolean tier = false;
        for (var key : meta.getPersistentDataContainer().getKeys()) {
            if (!key.getNamespace().equals("chrdk_tierforge") || !TIER_KEYS.contains(key.getKey())) return false;
            tier = true;
        }
        return tier || (!meta.hasDisplayName() && !meta.hasLore());
    }

    private static boolean consume(ItemStack[] inventory, Material type, int count) {
        int available = 0;
        for (ItemStack stack : inventory) if (!empty(stack) && stack.getType() == type && ordinary(stack)) available += stack.getAmount();
        if (available < count) return false;
        for (int i = 0; i < inventory.length && count > 0; i++) {
            ItemStack stack = inventory[i];
            if (empty(stack) || stack.getType() != type || !ordinary(stack)) continue;
            int take = Math.min(count, stack.getAmount());
            removeAt(inventory, i, take);
            count -= take;
        }
        return true;
    }

    private static void removeAt(ItemStack[] inventory, int slot, int count) {
        int remaining = inventory[slot].getAmount() - count;
        if (remaining <= 0) inventory[slot] = null;
        else inventory[slot].setAmount(remaining);
    }

    private static boolean add(ItemStack[] inventory, ItemStack input) {
        ItemStack left = input.clone();
        for (ItemStack stack : inventory) {
            if (empty(stack) || !stack.isSimilar(left)) continue;
            int amount = Math.min(left.getAmount(), Math.max(0, stack.getMaxStackSize() - stack.getAmount()));
            stack.setAmount(stack.getAmount() + amount);
            left.setAmount(left.getAmount() - amount);
            if (left.getAmount() <= 0) return true;
        }
        for (int i = 0; i < inventory.length; i++) {
            if (!empty(inventory[i])) continue;
            int amount = Math.min(left.getAmount(), left.getMaxStackSize());
            inventory[i] = left.clone();
            inventory[i].setAmount(amount);
            left.setAmount(left.getAmount() - amount);
            if (left.getAmount() <= 0) return true;
        }
        return false;
    }

    private static boolean empty(ItemStack stack) { return stack == null || stack.getType().isAir() || stack.getAmount() <= 0; }
    private static ItemStack[] copy(ItemStack[] inventory) {
        return Arrays.stream(inventory).map(item -> item == null ? null : item.clone()).toArray(ItemStack[]::new);
    }
    private void send(Player player, ProfessionResult result) {
        plugin.messages().send(player, result.key(), ProfessionText.localise(plugin.messages(), result).toArray());
    }
    private record Result(String error, Material output, int amount, int repaired) {
        static Result fail(String error) { return new Result(error, null, 0, 0); }
        boolean ok() { return error.isEmpty(); }
    }
}
