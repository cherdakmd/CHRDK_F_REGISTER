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
    private final Map<String,Integer> batches;
    private final Map<String,RecipeEconomy.Cost> recipeFloors;
    public ShopService(ShopConfig.ShopConfigData config, ShopStore store, Supplier<ChrdkRegisterApi> api) {
        this.config = config; this.store = store; this.api = api;
        var minimum = new HashMap<String,RecipeEconomy.Cost>();
        config.currencyRates().forEach((m,v)->minimum.put(m,RecipeEconomy.Cost.of(v)));
        for (var item : config.items()) minimum.put(item.material(), RecipeEconomy.Cost.of(
                PriceEngine.ask(PriceEngine.round(item.basePrice()*item.minMultiplier()),config.spreadPercent())));
        // Постоянный консервативный потолок: покупка ингредиента сама поднимает его цену.
        // Текущий снимок НЕ защищал бы от большой покупки и последующей переработки.
        // Собственный нижний ask обязателен даже для сырья без рецепта:
        // иначе покупка большого объёма поднимает spot bid и позволяет тут же продать дороже.
        recipeFloors = RecipeEconomy.costs(minimum);
        var sizes = new HashMap<String,Integer>();
        for (var item : config.items()) {
            var bid = RecipeEconomy.Cost.of(PriceEngine.bid(PriceEngine.round(item.basePrice()*item.minMultiplier()),config.spreadPercent()));
            var cost = recipeFloors.get(item.material());
            if (cost != null) bid = bid.min(RecipeEconomy.withSpread(cost,config.spreadPercent()));
            sizes.put(item.material(), RecipeEconomy.batch(bid));
        }
        batches = Map.copyOf(sizes);
    }
    public ShopConfig.ShopConfigData config() { return config; }
    public ShopStore store() { return store; }
    public double priceOf(ShopConfig.ShopItem item) {
        return PriceEngine.price(item.basePrice(), store.stockOf(item, System.currentTimeMillis(), config.stockRecoveryPerHour()),
                item.refStock(), item.minMultiplier(), item.maxMultiplier());
    }
    public int batchSize(ShopConfig.ShopItem item) { return batches.getOrDefault(item.material(),0); }
    public long askPrice(ShopConfig.ShopItem item) {
        long price=WholeTrades.price(PriceEngine.ask(priceOf(item),config.spreadPercent()),batchSize(item),true);
        long currency=(long)config.currencyRates().getOrDefault(item.material(),0)*batchSize(item);
        return Math.max(price,currency)>WholeTrades.MAX ? -1 : Math.max(price,currency);
    }
    public long bidPrice(ShopConfig.ShopItem item) {
        if (!item.sellEnabled() || config.item(item.material()).filter(item::equals).isEmpty()) return 0;
        long bid = Math.max(0,WholeTrades.price(PriceEngine.bid(priceOf(item),config.spreadPercent()),batchSize(item),false));
        var cost = recipeFloors.get(item.material());
        return cost == null ? bid : Math.min(bid,RecipeEconomy.cap(cost,batchSize(item),config.spreadPercent()));
    }
    public void recordPricePoint(ShopConfig.ShopItem item) { store.recordPrice(item, priceOf(item), System.currentTimeMillis(), config.historyMaxPoints(), config.historyIntervalMillis()); }
    public double[] historyOf(ShopConfig.ShopItem item) { return store.historyOf(item.material()); }
    private boolean binding() {
        var core=api.get(); if(core==null||!Bukkit.isPrimaryThread()||!store.legacyAvailable()||!store.ensureMarket()) return false;
        if(store.registered()) return core.shopRegistered(store.market());
        return core.registerShop(store.market())&&store.markRegistered();
    }
    public boolean migrate(UUID id, double ignoredLegacyPreview) {
        if(!binding()) return false;
        long amount = store.legacyAmount(id); var core = api.get();
        if(store.legacyAvailable()&&core!=null&&core.legacyShopImported(id)) return true;
        return store.legacyAvailable() && amount >= 0 && core != null && core.importLegacyShop(id, amount);
    }
    public boolean ready(Player player) {
        var core = api.get();
        if(!Bukkit.isPrimaryThread()||core==null||!core.shopTradingAvailable(player)||!store.legacyAvailable()||!binding()) return false;
        if(store.pending()!=null) { recoverPending(); return false; }
        return player.isOnline() && !player.isDead() && !player.hasMetadata("NPC")
                && player.hasPermission("chrdkshop.use") && core.isAuthenticated(player)
                && migrate(player.getUniqueId(), store.balanceOf(player.getUniqueId()));
    }
    public boolean recoverPending() {
        var core=api.get(); if(core==null||!Bukkit.isPrimaryThread()||!store.legacyAvailable()||!binding()) return false;
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

    /** Количество — штуки, не партии. Полная эталонная партия сохраняет прежнюю цену. */
    public TradeResult buy(Player player, ShopConfig.ShopItem item, long units) { return trade(player, item, units, true, -1, -1); }
    public TradeResult sell(Player player, ShopConfig.ShopItem item, long units) { return trade(player, item, units, false, -1, -1); }
    TradeResult quoted(Player player, ShopConfig.ShopItem item, long units, boolean buy, int batch, long price) {
        return trade(player, item, units, buy, batch, price);
    }
    private TradeResult trade(Player player, ShopConfig.ShopItem item, long units, boolean buy, int expectedBatch, long expectedPrice) {
        return trade(player,item,units,buy,expectedBatch,expectedPrice,false);
    }
    private TradeResult trade(Player player, ShopConfig.ShopItem item, long units, boolean buy, int expectedBatch, long expectedPrice, boolean carry) {
        if (!config.item(item.material()).filter(item::equals).isPresent()) return fail("trading.price-changed",0);
        if (!buy && !item.sellEnabled()) return fail("quantity.buy-only",0);
        if (!ready(player) || player.getGameMode() != GameMode.SURVIVAL) return fail("trading.unavailable", 0);
        int batch = batchSize(item); long price = buy ? askPrice(item) : bidPrice(item);
        if (expectedBatch >= 0 && (batch != expectedBatch || price != expectedPrice)) return fail("trading.price-changed", 0);
        if (batch < 1 || price < 1) return fail(buy?"trading.unavailable":"quantity.no-bid", 0);
        String remainderBefore = carry ? api.get().shopSaleRemainder(player.getUniqueId()) : null;
        ShopSaleRemainder.Quote exact = null;
        if(carry) {
            try { exact=ShopSaleRemainder.quote(remainderBefore,batch,price,units); }
            catch(RuntimeException error) { return fail("trading.unavailable",0); }
        }
        long money=carry ? exact.money() : TradeAmounts.quote(batch,price,units,buy);
        if(money<0 || money>WholeTrades.MAX)return fail("trading.invalid-number",0);
        if(money==0 && !carry)return fail("quantity.too-small",0);
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
            if (!remove(after, material, units)) return fail("quantity.not-enough-items", money);
        }
        long now = System.currentTimeMillis();
        if (money>0 && !store.canRecordTurnover(player.getUniqueId(), money, config.dailyTurnoverLimit(), now)) return fail(store.legacyAvailable() ? "trading.daily-limit" : "trading.unavailable", money);
        var frozenRates=new LinkedHashMap<String,Integer>();
        config.currencyRates().forEach((key,value)->{ var m=Material.matchMaterial(key); if(m!=null&&m.isItem()&&m.name().equals(key)) frozenRates.put(key,value); });
        var t=new ShopExchange(UUID.randomUUID(),store.market(),store.sequence()+1,player.getUniqueId(),buy,item.material(),units,money,debit,credit,spent,frozenRates,ChargeInventory.encode(before),ChargeInventory.encode(after));
        if(carry) t=new ShopExchange(t.transaction(),t.market(),t.sequence(),t.owner(),false,t.material(),t.units(),t.money(),0,t.credit(),0,t.rates(),t.before(),t.after(),batch,price,remainderBefore,exact.remainder());
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
    private TradeAmounts.Sale maximumSalePlan(Player player,ShopConfig.ShopItem item,int batch,long bid) {
        if (!item.sellEnabled() || config.item(item.material()).filter(item::equals).isEmpty()) return null;
        var material=Material.matchMaterial(item.material()); if(material==null)return null;
        long budget=Math.max(0,WholeTrades.MAX-balance(player));
        if(config.dailyTurnoverLimit()>0)budget=Math.min(budget,(long)Math.floor(Math.max(0,config.dailyTurnoverLimit()-store.usedToday(player.getUniqueId(),System.currentTimeMillis()))));
        return TradeAmounts.selectSale(batch,bid,countItems(player.getInventory(),material),budget);
    }
    public int maximumSale(Player player,ShopConfig.ShopItem item,int batch,long bid) {
        var plan=maximumSalePlan(player,item,batch,bid);
        return plan==null?0:plan.units();
    }
    /** all использует ту же замороженную котировку, по которой подобран объём. */
    public TradeResult sellMaximum(Player player,ShopConfig.ShopItem item) {
        if (config.item(item.material()).filter(item::equals).isEmpty()) return fail("trading.price-changed",0);
        if (!item.sellEnabled()) return fail("quantity.buy-only",0);
        if(!ready(player)||player.getGameMode()!=GameMode.SURVIVAL)return fail("trading.unavailable",0);
        var plan=maximumSalePlan(player,item,batchSize(item),bidPrice(item));
        if(plan==null)return fail("quantity.sellall-none",0);
        return quoted(player,item,plan.units(),false,plan.batch(),plan.price());
    }
    public String saleRemainder(Player player) {
        var core=api.get(); return core==null ? null : core.shopSaleRemainder(player.getUniqueId());
    }
    private TradeResult sellAllUnits(Player player, ShopConfig.ShopItem item) {
        if(!ready(player)||player.getGameMode()!=GameMode.SURVIVAL) return fail("trading.unavailable",0);
        var material=Material.matchMaterial(item.material());
        if(material==null || config.currencyRates().containsKey(item.material())) return fail("quantity.sellall-none",0);
        int batch=batchSize(item); long price=bidPrice(item);
        long budget=Math.max(0,WholeTrades.MAX-balance(player));
        if(config.dailyTurnoverLimit()>0) budget=Math.min(budget,(long)Math.floor(Math.max(0,config.dailyTurnoverLimit()-store.usedToday(player.getUniqueId(),System.currentTimeMillis()))));
        int units=CarrySale.maximum(saleRemainder(player),batch,price,countItems(player.getInventory(),material),budget);
        return units==0 ? fail("quantity.sellall-none",0) : trade(player,item,units,false,batch,price,true);
    }
    public SellAllResult sellAll(Player player) {
        long total=0,units=0;int kinds=0;
        for(var item:config.items()){
            if (!item.sellEnabled()) continue;
            if(!ready(player)||player.getGameMode()!=GameMode.SURVIVAL)break;
            var result=sellAllUnits(player,item);
            if(result.success()){total+=result.money();units+=result.units();kinds++;}
            else if(store.pending()!=null||!store.legacyAvailable())break;
        }
        return new SellAllResult(total,units,kinds);
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
