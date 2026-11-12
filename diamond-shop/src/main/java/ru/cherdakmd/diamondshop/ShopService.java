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
    private final Supplier<ru.cherdakmd.chrdkskills.ChrdkSkillsApi> skillsApi;
    private final Map<String,Integer> batches;
    private final Map<String,RecipeEconomy.Cost> recipeFloors;
    /** {@code null} в старых вызовах и юнит-тестах — акции честно выключены. */
    private final ShopPromoService promos;
    public ShopService(ShopConfig.ShopConfigData config, ShopStore store, Supplier<ChrdkRegisterApi> api) {
        this(config, store, api, () -> null, null);
    }
    /** Конструктор с мягким чтением престижа из CHRDK_Skills; без аддона — база лимита. */
    public ShopService(ShopConfig.ShopConfigData config, ShopStore store, Supplier<ChrdkRegisterApi> api,
                       Supplier<ru.cherdakmd.chrdkskills.ChrdkSkillsApi> skillsApi) {
        this(config, store, api, skillsApi, null);
    }
    /** Полная сборка плагина: дневные акции вычисляются внутри обычной сделки. */
    public ShopService(ShopConfig.ShopConfigData config, ShopStore store, Supplier<ChrdkRegisterApi> api,
                       Supplier<ru.cherdakmd.chrdkskills.ChrdkSkillsApi> skillsApi,
                       ShopPromoService promos) {
        this.config = config; this.store = store; this.api = api; this.skillsApi = skillsApi;
        this.promos = promos;
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
            int initial = RecipeEconomy.batch(bid);
            // 7 ноября 2026: лот не должен стоить 1 алмаз. Если нижняя цена дала
            // единицу, увеличиваем КОЛИЧЕСТВО товара в лоте, а не дробим алмаз.
            double lowerBid = bid.numerator().doubleValue() / bid.denominator().doubleValue();
            double lowerAsk = PriceEngine.ask(PriceEngine.round(item.basePrice()*item.minMultiplier()),config.spreadPercent());
            int fitted = WholeLotMath.fit(initial, lowerBid, lowerAsk, item.sellEnabled());
            sizes.put(item.material(), fitted);
        }
        batches = Map.copyOf(sizes);
    }
    private static long turnover(long minor) { return DiamondMoney.ceilWhole(minor); }
    public ShopConfig.ShopConfigData config() { return config; }
    public ShopStore store() { return store; }
    /** Дневные акции или {@code null}, если аддон собран без них (тесты/старые вызовы). */
    public ShopPromoService promos() { return promos; }

    /**
     * Персональный дневной лимит оборота игрока в целых алмазах.
     * {@code 0} — владелец выключил лимит ({@code daily-cap.enabled: false}).
     * Явный {@code pricing.daily-turnover-limit > 0} зажимает лимит сверху.
     */
    public double dailyLimit(Player player) {
        // Дневной оборот больше не ограничивает заработок или покупки.
        // Старые pricing.daily-cap.* намеренно игнорируются после обновления.
        return 0;
    }

    /** Разбор лимита по источникам — для {@code /shopadmin cap} и диагностики. */
    public ShopCapMath.Breakdown dailyLimitBreakdown(Player player) {
        ShopCapMath.Settings settings = config.dailyCap();
        if (settings == null || !settings.enabled()) {
            return new ShopCapMath.Breakdown(0, 0, 0, 0, 0, 0, 0, 0);
        }
        ShopCapMath.Breakdown parts = ShopCapMath.breakdown(settings.base(), settings, capContext(player));
        double manual = config.dailyTurnoverLimit();
        return manual > 0 && parts.total() > manual
                ? new ShopCapMath.Breakdown(parts.base(), parts.rank(), parts.prestige(), parts.levels(),
                        parts.pass(), parts.boost(), parts.entitlement(), (long) manual)
                : parts;
    }

    /** Сбор прокачки: недоступное ядро или отсутствующий CHRDK_Skills дают нули. */
    private ShopCapMath.Context capContext(Player player) {
        if (player == null) {
            return ShopCapMath.Context.NONE;
        }
        String name = player.getName();
        ChrdkRegisterApi core = api.get();
        RankBonus bonus = core == null ? RankBonus.NONE : core.rankBonus(name);
        if (bonus == null) {
            bonus = RankBonus.NONE;
        }
        int prestige = 0, level = 0;
        var skills = skillsApi.get();
        if (skills != null) {
            prestige = skills.totalPrestige(player);
            level = skills.totalLevel(player);
        }
        ShopCapMath.Settings settings = config.dailyCap();
        String entitlementId = settings == null ? "" : settings.entitlementId();
        boolean pass = core != null && core.hasActivePass(name);
        boolean boost = core != null && core.rewardBoostRemainingMillis(name) > 0;
        boolean entitlement = core != null && entitlementId != null && !entitlementId.isEmpty()
                && core.hasEntitlement(name, entitlementId);
        return new ShopCapMath.Context(bonus.level(), bonus.xpPercent(), bonus.dropPercent(),
                bonus.extraSkill(), prestige, level, pass, boost, entitlement);
    }
    public double priceOf(ShopConfig.ShopItem item) {
        return PriceEngine.price(item.basePrice(), store.stockOf(item, System.currentTimeMillis(), config.stockRecoveryPerHour()),
                item.refStock(), item.minMultiplier(), item.maxMultiplier());
    }
    public int batchSize(ShopConfig.ShopItem item) { return batches.getOrDefault(item.material(),0); }
    public long askPrice(ShopConfig.ShopItem item) { return askPrice(item,priceOf(item)); }
    private long askPrice(ShopConfig.ShopItem item,double spot) {
        long price=WholeTrades.minorPrice(PriceEngine.ask(spot,config.spreadPercent()),batchSize(item),true,item.purchaseMultiplier());
        if (price < 0) return -1;
        long currency=(long)config.currencyRates().getOrDefault(item.material(),0)*batchSize(item)*DiamondMoney.SCALE;
        return Math.max(price,currency)>WholeTrades.MAX ? -1 : Math.max(price,currency);
    }
    public long bidPrice(ShopConfig.ShopItem item) { return bidPrice(item,priceOf(item)); }
    private long bidPrice(ShopConfig.ShopItem item,double spot) {
        if (!item.sellEnabled() || config.item(item.material()).filter(item::equals).isEmpty()) return 0;
        long bid = Math.max(0,WholeTrades.minorPrice(PriceEngine.bid(spot,config.spreadPercent()),batchSize(item),false,1));
        var cost = recipeFloors.get(item.material());
        return cost == null ? bid : Math.min(bid,RecipeEconomy.minorCap(cost,batchSize(item),config.spreadPercent()));
    }
    /** Котировка не трогает инвентарь, банк, WAL, остаток sellall или суточный оборот. */
    public long quantityQuote(ShopConfig.ShopItem item,int units,boolean buy) {
        if(config.item(item.material()).filter(item::equals).isEmpty()||(!buy&&!item.sellEnabled())) return -1;
        double spot=peekPrice(item,System.currentTimeMillis());
        return TradeAmounts.quote(batchSize(item),buy?askPrice(item,spot):bidPrice(item,spot),units,buy);
    }
    private double peekPrice(ShopConfig.ShopItem item,long now) {
        return PriceEngine.price(item.basePrice(),store.peekStock(item,now,config.stockRecoveryPerHour()),
                item.refStock(),item.minMultiplier(),item.maxMultiplier());
    }
    public record QuoteAudit(int items,int buyOnly,int errors,int capped) { }
    public QuoteAudit auditQuotes() {
        int buyOnly=0,errors=0,capped=0;long now=System.currentTimeMillis();
        for(var item:config.items()) {
            double current=peekPrice(item,now);long ask=askPrice(item,current),bid=bidPrice(item,current);int batch=batchSize(item);
            if(!item.sellEnabled()) buyOnly++;
            if(batch<1||batch>2304||ask<1||ask>WholeTrades.MAX||bid<0||bid>ask||(!item.sellEnabled()&&bid!=0)) errors++;
            var cost=recipeFloors.get(item.material());
            if(item.sellEnabled()&&cost!=null) {
                long cap=RecipeEconomy.minorCap(cost,batch,config.spreadPercent());
                if(bid>cap) errors++;
                long spot=Math.max(0,WholeTrades.minorPrice(PriceEngine.bid(current,config.spreadPercent()),batch,false,1));
                if(bid<spot) capped++;
            }
        }
        return new QuoteAudit(config.items().size(),buyOnly,errors,capped);
    }

    public void recordPricePoint(ShopConfig.ShopItem item) { store.recordPrice(item, priceOf(item), System.currentTimeMillis(), config.historyMaxPoints(), config.historyIntervalMillis()); }
    public double[] historyOf(ShopConfig.ShopItem item) { return store.historyOf(item.material()); }
    /** Диагностическая команда может восстановить только доказуемо пустую привязку. */
    public boolean restoreBinding() { return binding(); }
    private boolean binding() {
        var core=api.get(); if(core==null||!Bukkit.isPrimaryThread()||!store.legacyAvailable()||!store.ensureMarket()) return false;
        if(core.shopRegistered(store.market())) return store.markRegistered();
        // Core мог потерять/восстановить lineage отдельно от shop-data.json.
        // Повторная регистрация того же UUID безопасна только до первой сделки.
        if(!ShopRegistration.canRestore(store.sequence(),store.pending()!=null)) return false;
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
    public long balanceMinor(Player player) { var core=api.get(); return core==null?0:Math.max(0,core.personalDiamondsMinor(player)); }
    public String balanceText(Player player) { var core=api.get(); String text=core==null?null:core.personalDiamondsText(player); return text==null?"—":text; }
    public long physical(Player player) {
        long value = 0;
        for (var stack : player.getInventory().getStorageContents()) if (plain(stack))
            value += (long) config.currencyRates().getOrDefault(stack.getType().name(), 0) * stack.getAmount();
        return value;
    }
    private static boolean plain(ItemStack item) { return item != null && !item.getType().isAir() && !item.hasItemMeta() && item.getAmount() > 0 && item.getAmount() <= item.getMaxStackSize(); }
    private static ItemStack[] copy(ItemStack[] items) { return Arrays.stream(items).map(i -> i == null ? null : i.clone()).toArray(ItemStack[]::new); }
    public record TradeResult(boolean success, long money, String errorKey, long units,
                              long bankPaid, long physicalPaid, long change, long bonus) {
        /** Прежний контракт без акций: бонус равен нулю. */
        public TradeResult(boolean success, long money, String errorKey, long units,
                           long bankPaid, long physicalPaid, long change) {
            this(success, money, errorKey, units, bankPaid, physicalPaid, change, 0);
        }
    }
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
        long now = System.currentTimeMillis();
        double limit = dailyLimit(player); // один снимок прокачки на всю сделку
        // Дневная акция добавляет алмазы ВНУТРЬ этой же сделки: WAL, журнал и
        // дневной оборот видят одну выплату, отдельной эмиссии не существует.
        ShopPromoService.Bonus promo = ShopPromoService.Bonus.none();
        if (!buy && promos != null && money > 0) {
            long askTotal = ShopPromoMath.askTotal(askPrice(item), batch, units);
            double used = store.usedToday(player.getUniqueId(), now);
            long headroom = limit > 0
                    ? Math.max(0L,(long)Math.floor((limit-used)*100L-money))
                    : WholeTrades.MAX;
            promo = promos.quote(player.getUniqueId(), item, money, askTotal, units, headroom, now);
            long extra = promo.amount();
            if (extra > 0) {
                if (carry) {
                    // validRemainder() пересчитывает выплату из salePrice, поэтому
                    // в сделках с переносом остатка бонус входит в цену партии.
                    long perLot = Math.min(extra / ShopPromoMath.lots(batch, units),
                            Math.max(0, WholeTrades.MAX - price));
                    if (perLot > 0) {
                        long effectivePrice = price + perLot;
                        try {
                            exact = ShopSaleRemainder.quote(remainderBefore, batch, effectivePrice, units);
                        } catch (RuntimeException error) {
                            return fail("trading.unavailable", money);
                        }
                        long raised = exact.money();
                        long actual = raised - money;
                        if (actual > 0 && raised <= WholeTrades.MAX) {
                            money = raised;
                            price = effectivePrice;
                            promo = promo.withAmount(Math.min(actual, extra));
                        } else {
                            promo = ShopPromoService.Bonus.none();
                        }
                    } else {
                        promo = ShopPromoService.Bonus.none();
                    }
                } else {
                    money += extra;
                }
                if (money > WholeTrades.MAX) return fail("trading.invalid-number", money);
            }
        }
        var marketQuote=api.get().diamondRate();
        if(carry) {
            price=marketQuote.scaleMinor(price);
            try { exact=ShopSaleRemainder.quote(remainderBefore,batch,price,units); money=exact.money(); }
            catch(RuntimeException error) { return fail("trading.unavailable",money); }
        } else money=marketQuote.scaleMinor(money);
        var spec = item.spec();
        Material material = spec == null ? Material.matchMaterial(item.material()) : null;
        if (spec == null && (material == null || !material.isItem())) return fail("trading.unknown-item", 0);
        var before = copy(player.getInventory().getStorageContents()); var after = copy(before);
        long debit = 0, credit = buy ? 0 : money, spent = 0, change = 0;
        if (buy) {
            long wallet = balanceMinor(player), neededMinor = Math.max(0, money - wallet);
            long physicalMinor=marketQuote.creditMinor(1),needed=(neededMinor+physicalMinor-1)/physicalMinor;
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
            debit=Math.min(wallet,money);
            long paidMinor=Math.addExact(debit,Math.multiplyExact(spent,physicalMinor));
            if(paidMinor<money)return fail("trading.not-enough-balance",money);
            credit=paidMinor-money; change=credit;
            if(wallet-debit>WholeTrades.MAX-credit)return fail("trading.invalid-number",money);
            if (spec != null) {
                String error = insertBooks(after, spec, units);
                if (error != null) return fail(error, money);
            } else if (!insert(after, material, units)) {
                return fail("trading.no-space", money);
            }
        } else {
            if (spec != null ? !removeBooks(after, spec, units) : !remove(after, material, units))
                return fail("quantity.not-enough-items", money);
        }
        if (money>0 && !store.canRecordTurnover(player.getUniqueId(), turnover(money), limit, now)) return fail(store.legacyAvailable() ? "trading.daily-limit" : "trading.unavailable", money);
        var frozenRates=new LinkedHashMap<String,Integer>();
        config.currencyRates().forEach((key,value)->{ var m=Material.matchMaterial(key); if(m!=null&&m.isItem()&&m.name().equals(key)) frozenRates.put(key,value); });
        String beforeSnapshot=ChargeInventory.encode(before), afterSnapshot=ChargeInventory.encode(after);
        var t = spec == null
                ? new ShopExchange(UUID.randomUUID(),store.market(),store.sequence()+1,player.getUniqueId(),buy,item.material(),units,money,debit,credit,spent,frozenRates,beforeSnapshot,afterSnapshot)
                : new ShopExchange(UUID.randomUUID(),store.market(),store.sequence()+1,player.getUniqueId(),buy,item.material(),units,money,debit,credit,spent,frozenRates,beforeSnapshot,afterSnapshot,0,0,null,null,spec.key(),null);
        if(carry) t=new ShopExchange(t.transaction(),t.market(),t.sequence(),t.owner(),false,t.material(),t.units(),t.money(),0,t.credit(),0,t.rates(),t.before(),t.after(),batch,price,remainderBefore,exact.remainder(),t.book(),null,true,marketQuote.rateBps());
        else t=ShopExchange.fractional(t,marketQuote.rateBps());
        if(!ShopInventory.valid(t)) return fail("trading.unavailable",money);
        long stock=store.stockOf(item,now,config.stockRecoveryPerHour());
        long afterStock; try { afterStock=Math.max(1,Math.addExact(stock,buy?-units:units)); } catch(ArithmeticException e) { return fail("trading.unavailable",money); }
        double afterPrice=PriceEngine.price(item.basePrice(),afterStock,item.refStock(),item.minMultiplier(),item.maxMultiplier());
        if(!store.prepare(t,item,now,config.stockRecoveryPerHour(),limit,config.historyMaxPoints(),config.historyIntervalMillis(),afterPrice)) {
            if(!store.legacyAvailable()) lock(player); return fail("trading.unavailable",money);
        }
        if(!recoverPending()) {
            if(store.pending()!=null||!store.legacyAvailable()) { lock(player); return fail("trading.pending",money); }
            return fail("trading.unavailable",money);
        }
        // Прогресс акций копится на каждой ПРОДАЖЕ, даже если бонус сегодня не
        // выпал. Покупки в зачёт не идут: иначе заказ/серию можно было бы закрыть,
        // просто купив товар в магазине.
        if (!buy && promos != null) {
            promos.record(player.getUniqueId(), item.material(), units, promo, now);
        }
        return new TradeResult(true, money, null, units, debit, spent, change, promo.amount());
    }
    public record SellAllResult(long income, long units, int kinds, long bonus) {
        /** Прежний контракт без акций. */
        public SellAllResult(long income, long units, int kinds) { this(income, units, kinds, 0); }
    }
    private TradeAmounts.Sale maximumSalePlan(Player player,ShopConfig.ShopItem item,int batch,long bid) {
        if (!item.sellEnabled() || config.item(item.material()).filter(item::equals).isEmpty()) return null;
        long budget=Math.max(0,WholeTrades.MAX-balanceMinor(player));
        double limit=dailyLimit(player);
        if(limit>0)budget=Math.min(budget,(long)Math.floor(Math.max(0,limit-store.usedToday(player.getUniqueId(),System.currentTimeMillis()))*100L));
        return TradeAmounts.selectSale(batch,bid,countSellable(player.getInventory(),item),budget);
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
        if(item.spec()==null){
            var material=Material.matchMaterial(item.material());
            if(material==null || config.currencyRates().containsKey(item.material())) return fail("quantity.sellall-none",0);
        }
        int batch=batchSize(item); long price=bidPrice(item);
        long budget=Math.max(0,WholeTrades.MAX-balanceMinor(player));
        double limit=dailyLimit(player);
        if(limit>0) budget=Math.min(budget,(long)Math.floor(Math.max(0,limit-store.usedToday(player.getUniqueId(),System.currentTimeMillis()))*100L));
        int units=TradeAmounts.saleAll(batch,price,countSellable(player.getInventory(),item),budget);
        return units==0 ? fail("quantity.sellall-none",0) : trade(player,item,units,false,batch,price,false);
    }
    public SellAllResult sellAll(Player player) {
        long total=0,units=0;int kinds=0;long bonus=0;
        for(var item:config.items()){
            if (!item.sellEnabled()) continue;
            if(!ready(player)||player.getGameMode()!=GameMode.SURVIVAL)break;
            var result=sellAllUnits(player,item);
            if(result.success()){total+=result.money();units+=result.units();kinds++;bonus+=result.bonus();}
            else if(store.pending()!=null||!store.legacyAvailable())break;
        }
        return new SellAllResult(total,units,kinds,bonus);
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
    /** Сколько товара реально можно продать: обычные предметы или помеченные книги. */
    public static long countSellable(PlayerInventory inventory, ShopConfig.ShopItem item) {
        var spec = item.spec();
        if (spec != null) return countBooks(inventory, spec);
        Material material = Material.matchMaterial(item.material());
        return material == null ? 0 : countItems(inventory, material);
    }

    /** Только книги с меткой магазина и точным совпадением чары и уровня. */
    public static long countBooks(PlayerInventory inventory, ShopBookKey.Spec spec) {
        if (spec == null) return 0;
        long count = 0;
        for (var stack : inventory.getStorageContents()) if (ShopBooks.matches(stack, spec)) count++;
        return count;
    }

    /** {@code null} — успех, иначе ключ ошибки. Книги с NBT не стакаются: по слоту на книгу. */
    private static String insertBooks(ItemStack[] items, ShopBookKey.Spec spec, long units) {
        // Выданная книга называется так же, как иконка в витрине: игрок получает
        // «Громобой III», а не «Книга чар: thunderstrike III».
        ItemStack template = ShopBooks.create(spec, ShopBookInfo.displayName(spec));
        if (template == null) return "trading.unknown-item";
        long left = units;
        for (int i = 0; i < items.length && left > 0; i++) {
            if (items[i] == null || items[i].getType().isAir()) {
                items[i] = template.clone();
                left--;
            }
        }
        return left == 0 ? null : "trading.no-space";
    }

    private static boolean removeBooks(ItemStack[] items, ShopBookKey.Spec spec, long units) {
        long left = units;
        for (int i = 0; i < items.length && left > 0; i++) {
            if (ShopBooks.matches(items[i], spec)) {
                items[i] = null;
                left--;
            }
        }
        return left == 0;
    }

    public Optional<ShopConfig.ShopItem> findItem(String input) { return config.item(input); }
}
