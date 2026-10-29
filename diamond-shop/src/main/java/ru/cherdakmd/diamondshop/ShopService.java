package ru.cherdakmd.diamondshop;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import ru.cherdakmd.chrdkregister.api.*;
import java.util.*;
import java.util.function.Supplier;

/** Единственный финансовый источник — Core /bal; магазин хранит только рынок и старый вход миграции. */
public final class ShopService {
    private final ShopConfig.ShopConfigData config;
    private final ShopStore store;
    private final Supplier<ChrdkRegisterApi> api;
    public ShopService(ShopConfig.ShopConfigData config, ShopStore store, Supplier<ChrdkRegisterApi> api) {
        this.config = config; this.store = store; this.api = api;
    }
    public ShopConfig.ShopConfigData config() { return config; }
    public ShopStore store() { return store; }
    public double priceOf(ShopConfig.ShopItem item) {
        return PriceEngine.price(item.basePrice(), store.stockOf(item, System.currentTimeMillis(), config.stockRecoveryPerHour()),
                item.refStock(), item.minMultiplier(), item.maxMultiplier());
    }
    public int batchSize(ShopConfig.ShopItem item) {
        return WholeTrades.batch(PriceEngine.bid(PriceEngine.round(item.basePrice() * Math.min(1, item.minMultiplier())), config.spreadPercent()));
    }
    public long askPrice(ShopConfig.ShopItem item) { return WholeTrades.price(PriceEngine.ask(priceOf(item), config.spreadPercent()), batchSize(item), true); }
    public long bidPrice(ShopConfig.ShopItem item) { return WholeTrades.price(PriceEngine.bid(priceOf(item), config.spreadPercent()), batchSize(item), false); }
    public void recordPricePoint(ShopConfig.ShopItem item) { store.recordPrice(item, priceOf(item), System.currentTimeMillis(), config.historyMaxPoints(), config.historyIntervalMillis()); }
    public double[] historyOf(ShopConfig.ShopItem item) { return store.historyOf(item.material()); }
    public boolean migrate(UUID id, double ignoredLegacyPreview) {
        long amount = store.legacyAmount(id); var core = api.get();
        if(store.legacyAvailable()&&core!=null&&core.legacyShopImported(id)) return true;
        return store.legacyAvailable() && amount >= 0 && core != null && core.importLegacyShop(id, amount);
    }
    public boolean ready(Player player) {
        var core = api.get();
        if(!Bukkit.isPrimaryThread()||core==null||!core.shopTradingAvailable(player)||!store.legacyAvailable()||!store.ensureMarket()||!core.registerShop(store.market())) return false;
        if(store.pending()!=null) { recoverPending(); return false; }
        return player.isOnline() && !player.isDead() && !player.hasMetadata("NPC")
                && player.hasPermission("chrdkshop.use") && core.isAuthenticated(player)
                && migrate(player.getUniqueId(), store.balanceOf(player.getUniqueId()));
    }
    public boolean recoverPending() {
        var core=api.get(); if(core==null||!Bukkit.isPrimaryThread()||!store.legacyAvailable()||!store.ensureMarket()||!core.registerShop(store.market())) return false;
        return ShopJournal.recover(store,new ShopJournal.Bank() {
            public ShopExchange.Status status(ShopExchange t) { return core.shopExchangeStatus(t); }
            public ShopExchange.Status commit(ShopExchange t) {
                var player=Bukkit.getPlayer(t.owner()); if(player==null||!core.isAuthenticated(player)) return ShopExchange.Status.UNAVAILABLE;
                var result=core.commitShopExchange(player,t);
                if(result==ShopExchange.Status.PREPARED||result==ShopExchange.Status.CONFLICT) lock(player);
                return result;
            }
        });
    }
    private void lock(Player player) { player.kick(net.kyori.adventure.text.Component.text("Магазин: незавершённая сделка требует восстановления. Не сбрасывайте данные.")); }
    public long balance(Player player) { var core = api.get(); return core == null ? 0 : core.personalDiamonds(player); }
    public long physical(Player player) {
        long value = 0;
        for (var stack : player.getInventory().getStorageContents()) if (plain(stack))
            value += (long) config.currencyRates().getOrDefault(stack.getType().name(), 0) * stack.getAmount();
        return value;
    }
    private static boolean plain(ItemStack item) { return item != null && !item.getType().isAir() && !item.hasItemMeta() && item.getAmount() > 0 && item.getAmount() <= item.getMaxStackSize(); }
    private static ItemStack[] copy(ItemStack[] items) { return Arrays.stream(items).map(i -> i == null ? null : i.clone()).toArray(ItemStack[]::new); }
    public record TradeResult(boolean success, long money, String errorKey, long units, long bankPaid, long physicalPaid, long change) { }
    private static TradeResult fail(String key, long money) { return new TradeResult(false, money, key, 0, 0, 0, 0); }

    /** Аргумент — число партий, а не штук. Цена каждой партии целая, количество ограничено вместимостью storage. */
    public TradeResult buy(Player player, ShopConfig.ShopItem item, long batches) { return trade(player, item, batches, true, -1, -1); }
    public TradeResult sell(Player player, ShopConfig.ShopItem item, long batches) { return trade(player, item, batches, false, -1, -1); }
    TradeResult quoted(Player player, ShopConfig.ShopItem item, long batches, boolean buy, int batch, long price) {
        return trade(player, item, batches, buy, batch, price);
    }
    private TradeResult trade(Player player, ShopConfig.ShopItem item, long batches, boolean buy, int expectedBatch, long expectedPrice) {
        if (!ready(player) || player.getGameMode() != GameMode.SURVIVAL) return fail("trading.unavailable", 0);
        int batch = batchSize(item); long price = buy ? askPrice(item) : bidPrice(item);
        if (expectedBatch >= 0 && (batch != expectedBatch || price != expectedPrice)) return fail("trading.price-changed", 0);
        if (batch < 1 || price < 1) return fail("trading.unavailable", 0);
        if (batches < 1 || batches > 2304L / batch || batches > WholeTrades.MAX / price) return fail("trading.invalid-number", 0);
        long units = batches * batch, money = batches * price;
        Material material = Material.matchMaterial(item.material());
        if (material == null || !material.isItem()) return fail("trading.unknown-item", 0);
        var before = copy(player.getInventory().getStorageContents()); var after = copy(before);
        long debit = 0, credit = buy ? 0 : money, spent = 0, change = 0;
        if (buy) {
            long wallet = balance(player), needed = Math.max(0, money - wallet);
            int[] amounts = new int[36], values = new int[36];
            for (int slot=0; slot<after.length; slot++) if (plain(after[slot])) {
                amounts[slot] = after[slot].getAmount(); values[slot] = config.currencyRates().getOrDefault(after[slot].getType().name(), 0);
            }
            var removal = WholeTrades.take(amounts, values, needed);
            if (removal == null) return fail("trading.not-enough-balance", money);
            spent = removal.paid();
            for (int slot=0; slot<after.length; slot++) if (amounts[slot] != removal.after()[slot]) {
                if (removal.after()[slot] == 0) after[slot] = null; else after[slot].setAmount(removal.after()[slot]);
            }
            var payment = WholeTrades.payment(money, wallet, spent);
            if (payment == null) return fail("trading.not-enough-balance", money);
            debit = payment.account(); credit = payment.change(); change = credit;
            if (!insert(after, material, units)) return fail("trading.no-space", money);
        } else {
            if (!remove(after, material, units)) return fail("trading.not-enough-items", money);
        }
        long now = System.currentTimeMillis();
        if (!store.canRecordTurnover(player.getUniqueId(), money, config.dailyTurnoverLimit(), now)) return fail(store.legacyAvailable() ? "trading.daily-limit" : "trading.unavailable", money);
        var frozenRates=new LinkedHashMap<String,Integer>();
        config.currencyRates().forEach((key,value)->{ var m=Material.matchMaterial(key); if(m!=null&&m.isItem()) frozenRates.put(key,value); });
        var t=new ShopExchange(UUID.randomUUID(),store.market(),store.sequence()+1,player.getUniqueId(),buy,item.material(),units,money,debit,credit,spent,frozenRates,ChargeInventory.encode(before),ChargeInventory.encode(after));
        if(!ShopInventory.valid(t)) return fail("trading.unavailable",money);
        long stock=store.stockOf(item,now,config.stockRecoveryPerHour());
        long afterStock; try { afterStock=Math.max(1,Math.addExact(stock,buy?-units:units)); } catch(ArithmeticException e) { return fail("trading.unavailable",money); }
        double afterPrice=PriceEngine.price(item.basePrice(),afterStock,item.refStock(),item.minMultiplier(),item.maxMultiplier());
        if(!store.prepare(t,item,now,config.stockRecoveryPerHour(),config.dailyTurnoverLimit(),config.historyMaxPoints(),config.historyIntervalMillis(),afterPrice)) {
            if(!store.legacyAvailable()) lock(player); return fail("trading.unavailable",money);
        }
        if(!recoverPending()) {
            if(store.pending()!=null||!store.legacyAvailable()) { lock(player); return fail("trading.pending",money); }
            return fail("trading.unavailable",money);
        }
        return new TradeResult(true, money, null, units, debit, spent, change);
    }
    public record SellAllResult(long income, long units, int kinds) { }
    public SellAllResult sellAll(Player player) {
        long total = 0, units = 0; int kinds = 0;
        for (var item : config.items()) {
            var material = Material.matchMaterial(item.material()); int batch = batchSize(item); long price = bidPrice(item);
            if (material == null || batch < 1 || price < 1) continue;
            long lots = countItems(player.getInventory(), material) / batch;
            if (config.dailyTurnoverLimit() > 0) lots = Math.min(lots, (long) Math.floor(Math.max(0, config.dailyTurnoverLimit() - store.usedToday(player.getUniqueId(), System.currentTimeMillis())) / price));
            if (lots < 1) continue;
            var result = sell(player, item, lots);
            if (result.success()) { total += result.money(); units += result.units(); kinds++; }
        }
        return new SellAllResult(total, units, kinds);
    }
    public static long countItems(PlayerInventory inventory, Material material) {
        long count = 0; for (var stack : inventory.getStorageContents()) if (plain(stack) && stack.getType() == material) count += stack.getAmount(); return count;
    }
    private static boolean remove(ItemStack[] items, Material material, long units) {
        for (int i = 0; i < items.length && units > 0; i++) {
            var stack = items[i]; if (!plain(stack) || stack.getType() != material) continue;
            int take = (int) Math.min(units, stack.getAmount()); units -= take; stack.setAmount(stack.getAmount() - take);
            if (stack.getAmount() == 0) items[i] = null;
        }
        return units == 0;
    }
    private static boolean insert(ItemStack[] items, Material material, long units) {
        int maximum = material.getMaxStackSize();
        for (int i = 0; i < items.length && units > 0; i++) {
            var stack = items[i]; if (!plain(stack) || stack.getType() != material) continue;
            int add = (int) Math.min(units, maximum - stack.getAmount()); stack.setAmount(stack.getAmount() + add); units -= add;
        }
        for (int i = 0; i < items.length && units > 0; i++) if (items[i] == null || items[i].getType().isAir()) {
            int add = (int) Math.min(units, maximum); items[i] = new ItemStack(material, add); units -= add;
        }
        return units == 0;
    }
    public Optional<ShopConfig.ShopItem> findItem(String input) { return config.item(input); }
}
