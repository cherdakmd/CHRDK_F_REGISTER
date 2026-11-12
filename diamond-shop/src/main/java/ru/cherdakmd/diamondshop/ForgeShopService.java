package ru.cherdakmd.diamondshop;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import ru.cherdakmd.chrdkregister.api.ChargeInventory;
import ru.cherdakmd.chrdkregister.api.DiamondMoney;
import ru.cherdakmd.chrdkregister.api.ForgeMarketCatalog;
import ru.cherdakmd.chrdkregister.api.ForgeMaterialSource;
import ru.cherdakmd.chrdkregister.api.ShopExchange;
import ru.cherdakmd.chrdkregister.api.ShopInventory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RC16: раздел «Кузня». Оплата — сначала личный {@code /bal}, затем физическая
 * недостача, сдача от крупных номиналов уходит в банк. Никакого «списать деньги
 * и отдельно выдать предмет командой/addItem»: и оплата, и выдача — один
 * frozen {@link ShopExchange} с exact native after через прежний WAL.
 */
final class ForgeShopService {

    /** Один SKU витрины; грант — native-снимок одного прототипа. */
    record Offer(String sku, String material, long price, boolean seed, ItemStack prototype, String grant) { }

    record Result(boolean success, long money, String errorKey, long units,
                  long bankPaid, long physicalPaid, long change) { }

    private final DiamondShopPlugin plugin;
    private final ForgeShopPrices prices;

    ForgeShopService(DiamondShopPlugin plugin, ForgeShopPrices prices) {
        this.plugin = plugin;
        this.prices = prices;
    }

    ForgeShopPrices prices() { return prices; }

    private static Result fail(String key, long money) {
        return new Result(false, money, key, 0, 0, 0, 0);
    }

    /** Живой производитель нужен только для НОВОЙ покупки, не для recovery. */
    private ForgeMaterialSource source(String sku) {
        for (var registration : Bukkit.getServicesManager().getRegistrations(ForgeMaterialSource.class)) {
            ForgeMaterialSource candidate = registration.getProvider();
            try {
                if (candidate != null && candidate.available(sku)) return candidate;
            } catch (RuntimeException | LinkageError ignored) {
                // Повреждённый производитель закрывает только свой SKU.
            }
        }
        var api = plugin.coreApi();
        ForgeMaterialSource fallback = api == null ? null : api.forgeMaterialSource();
        try {
            return fallback != null && fallback.available(sku) ? fallback : null;
        } catch (RuntimeException | LinkageError error) {
            return null;
        }
    }

    static boolean isPotionSku(String sku) {
        return sku != null && sku.startsWith("skill_xp_");
    }

    static boolean matchingSkillToken(String sku, String token) {
        return isPotionSku(sku) && sku.substring("skill_xp_".length()).equals(token);
    }

    /**
     * Skills 2.0.1 пишет chrdk_skills:skill_xp_v1, а Core 2.0.1 проверяет
     * chrdkskills:skill_xp_v1. Сохраняем исходный ключ для onConsume и добавляем
     * проверенный alias для native grant/recovery Core. Неизвестный/чужой PDC
     * по-прежнему закрывает SKU, а не превращается в обычное зелье по материалу.
     */
    private static ItemStack compatiblePrototype(String sku, ItemStack prototype) {
        if (!isPotionSku(sku)) return prototype;
        if (prototype == null || prototype.getType() != Material.POTION || !prototype.hasItemMeta()) return null;
        var skillsKey = NamespacedKey.fromString("chrdk_skills:skill_xp_v1");
        var coreKey = NamespacedKey.fromString("chrdkskills:skill_xp_v1");
        if (skillsKey == null || coreKey == null) return null;
        ItemStack compatible = prototype.clone();
        var meta = compatible.getItemMeta();
        if (meta == null) return null;
        var pdc = meta.getPersistentDataContainer();
        String token = pdc.get(skillsKey, PersistentDataType.STRING);
        String alias = pdc.get(coreKey, PersistentDataType.STRING);
        if (!matchingSkillToken(sku, token) || alias != null && !alias.equals(token)) return null;
        pdc.set(coreKey, PersistentDataType.STRING, token);
        compatible.setItemMeta(meta);
        return compatible;
    }

    List<Offer> forgeOffers(Player player) {
        return offers(player).stream().filter(offer -> !isPotionSku(offer.sku())).toList();
    }

    List<Offer> potionOffers(Player player) {
        var result = offers(player).stream().filter(offer -> isPotionSku(offer.sku())).toList();
        if (result.isEmpty()) diagnosePotions();
        return result;
    }

    /** Безопасная диагностика silent-filter: не создаёт предметы и не меняет сделки. */
    private void diagnosePotions() {
        var registrations = Bukkit.getServicesManager().getRegistrations(ForgeMaterialSource.class);
        plugin.getLogger().warning("[DiamondShop] Зелья не прошли витрину; ForgeMaterialSource providers=" + registrations.size());
        for (var sku : ForgeMarketCatalog.SKUS) {
            if (!isPotionSku(sku.id())) continue;
            long price = prices.price(sku.id());
            if (registrations.isEmpty()) {
                plugin.getLogger().warning("[DiamondShop] " + sku.id() + ": производитель не зарегистрирован; price=" + price);
                continue;
            }
            for (var registration : registrations) {
                var candidate = registration.getProvider();
                String name;
                try { name = candidate == null ? "null" : candidate.provider(); }
                catch (RuntimeException | LinkageError error) { name = candidate.getClass().getName(); }
                try {
                    if (candidate == null || !candidate.available(sku.id())) {
                        plugin.getLogger().warning("[DiamondShop] " + sku.id() + " @ " + name + ": available=false; price=" + price);
                        continue;
                    }
                    var prototype = compatiblePrototype(sku.id(), candidate.prototype(sku.id()));
                    if (prototype == null) {
                        plugin.getLogger().warning("[DiamondShop] " + sku.id() + " @ " + name
                                + ": prototype=null или PDC Skills не соответствует SKU");
                        continue;
                    }
                    String grant = ForgeMarketCatalog.snapshot(prototype);
                    plugin.getLogger().warning("[DiamondShop] " + sku.id() + " @ " + name
                            + ": validGrant=" + ForgeMarketCatalog.validTradeGrant(grant, sku.material(), 1)
                            + "; material=" + prototype.getType() + "; price=" + price);
                } catch (RuntimeException | LinkageError error) {
                    plugin.getLogger().log(java.util.logging.Level.WARNING,
                            "[DiamondShop] " + sku.id() + " @ " + name + ": исключение диагностики", error);
                }
            }
        }
    }

    /** Полный каталог для проверки котировки/recovery; витрины разделяют его по типу SKU. */
    List<Offer> offers(Player player) {
        List<Offer> offers = new ArrayList<>();
        if (!prices.enabled()) return offers;
        for (var sku : ForgeMarketCatalog.SKUS) {
            var source = source(sku.id());
            long price = plugin.coreApi().diamondRate().scaleMinor(prices.price(sku.id()));
            if (source == null || price < 1 || !source.available(sku.id())) continue;
            ItemStack prototype = compatiblePrototype(sku.id(), source.prototype(sku.id()));
            if (prototype == null) continue;
            String grant;
            try {
                grant = ForgeMarketCatalog.snapshot(prototype);
            } catch (RuntimeException invalid) {
                continue;
            }
            if (!ForgeMarketCatalog.validTradeGrant(grant, sku.material(), 1)) continue;
            offers.add(new Offer(sku.id(), sku.material(), price, sku.seed(), prototype, grant));
        }
        return offers;
    }

    /** Штатное подтверждение: ровно одна искра, остальные — до 64 за покупку (по 1 в слоте). */
    Result purchase(Player player, Offer offer, int units) {
        var service = plugin.shopService();
        if (!service.ready(player) || player.getGameMode() != GameMode.SURVIVAL) return fail("trading.unavailable", 0);
        var marketQuote=plugin.coreApi().diamondRate();
        if (!prices.enabled() || marketQuote.scaleMinor(prices.price(offer.sku())) != offer.price()) return fail("forge.shop.closed", 0);
        if (units < 1 || units > 64 || (offer.seed() && units != 1)) return fail("forge.shop.quantity", 0);
        long money;
        try {
            money = Math.multiplyExact(offer.price(), (long) units);
        } catch (ArithmeticException overflow) {
            return fail("trading.invalid-number", 0);
        }
        if (money < 1 || money > WholeTrades.MAX) return fail("trading.invalid-number", money);
        var source = source(offer.sku());
        if (source == null || !source.available(offer.sku())) return fail("forge.shop.closed", money);
        // Повторно проверяем прототип на момент подтверждения, без слепой веры в GUI.
        ItemStack fresh = compatiblePrototype(offer.sku(), source.prototype(offer.sku()));
        if (fresh == null || !ForgeMarketCatalog.validTradeGrant(offer.grant(), offer.material(), 1)) {
            return fail("forge.shop.closed", money);
        }
        Material material = Material.matchMaterial(offer.material());
        if (material == null || !material.isItem()) return fail("trading.unknown-item", money);

        var config = service.config();
        var before = copy(player.getInventory().getStorageContents());
        var after = copy(before);
        long wallet = service.balance(player), neededMinor = Math.max(0, money - wallet),physicalMinor=marketQuote.creditMinor(1),needed=(neededMinor+physicalMinor-1)/physicalMinor;
        int[] amounts = new int[36], values = new int[36];
        for (int slot = 0; slot < after.length; slot++) if (plain(after[slot])) {
            amounts[slot] = after[slot].getAmount();
            values[slot] = config.currencyRates().getOrDefault(after[slot].getType().name(), 0);
        }
        var removal = WholeTrades.take(amounts, values, needed);
        if (removal == null) return fail("trading.not-enough-balance", money);
        long spent = removal.paid();
        for (int slot = 0; slot < after.length; slot++) if (amounts[slot] != removal.after()[slot]) {
            if (removal.after()[slot] == 0) after[slot] = null;
            else after[slot].setAmount(removal.after()[slot]);
        }
        var payment = WholeTrades.payment(money, wallet, Math.multiplyExact(spent,physicalMinor));
        if (payment == null) return fail("trading.not-enough-balance", money);
        long debit = payment.account(), credit = payment.change();
        // exact isSimilar clone insertion; для искры max-stack-size=1 → отдельный слот.
        if (!ShopInventory.insertExact(after, offer.prototype(), units)) return fail("trading.no-space", money);

        var frozenRates = new LinkedHashMap<String, Integer>();
        config.currencyRates().forEach((key, value) -> {
            var m = Material.matchMaterial(key);
            if (m != null && m.isItem() && m.name().equals(key)) frozenRates.put(key, value);
        });
        long now = System.currentTimeMillis();
        double limit = service.dailyLimit(player);
        if (!service.store().canRecordTurnover(player.getUniqueId(), DiamondMoney.ceilWhole(money), limit, now)) {
            return fail("trading.daily-limit", money);
        }
        var trade = new ShopExchange(UUID.randomUUID(), service.store().market(),
                service.store().sequence() + 1, player.getUniqueId(), true, offer.material(),
                units, money, debit, credit, spent, frozenRates,
                ChargeInventory.encode(before), ChargeInventory.encode(after),
                0, 0, null, null, null, offer.grant());
        trade=ShopExchange.fractional(trade,marketQuote.rateBps());
        if (!ShopInventory.valid(trade)) return fail("trading.unavailable", money);
        if (!service.store().prepareForge(trade, now, limit)) {
            return fail("trading.unavailable", money);
        }
        if (!service.recoverPending()) return fail("trading.pending", money);
        return new Result(true, money, null, units, debit, spent, credit);
    }

    private static boolean plain(ItemStack item) {
        return item != null && !item.getType().isAir() && !item.hasItemMeta()
                && item.getAmount() > 0 && item.getAmount() <= item.getMaxStackSize();
    }

    private static ItemStack[] copy(ItemStack[] items) {
        var result = new ItemStack[36];
        for (int i = 0; i < 36 && i < items.length; i++) result[i] = items[i] == null ? null : items[i].clone();
        return result;
    }
}
