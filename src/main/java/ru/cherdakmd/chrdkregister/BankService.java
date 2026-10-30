package ru.cherdakmd.chrdkregister;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Банк алмазов: депозит, снятие, переводы и топ.
 *
 * <p>Единственное место, где встречаются инвентарь и счёт: все операции
 * выполняются на главном потоке. Депозит сначала проверяет предел счёта
 * и готовит точный storage-снимок; отказ не выдаёт предметы в мир. Депозит принимает только обычные алмазы и алмазные блоки —
 * именованные и зачарованные предметы не трогаются (их можно продать, но не
 * «разменять» случайно). Снятие сначала проверяет место в инвентаре и только
 * потом списывает.</p>
 *
 * <p>Перевод идёт по счёту, а не предметами: получатель может быть офлайн, а
 * сумма не зависит от свободных слотов. Комиссия ({@code economy.pay-tax-percent})
 * не зачисляется никому — это не доход сервера, а сдерживающий налог.</p>
 */
public final class BankService implements org.bukkit.event.Listener {

    /** Результат снятия. */
    public enum WithdrawStatus {
        OK,
        NOT_ENOUGH,
        NO_SPACE,
        UNAVAILABLE
    }

    public record WithdrawResult(WithdrawStatus status, long amount, long balance) {
    }

    /** Результат перевода для командного слоя. */
    public record PayResult(BankStore.Status status, String targetName, long amount, long tax,
                           long credited, long fromBalance, long cooldownSeconds) {
        public boolean ok() {
            return status == BankStore.Status.OK;
        }
    }

    private final RegisterPlugin plugin;
    private final BankStore store;
    private final Supplier<EconomySettings> settings;
    private org.bukkit.scheduler.BukkitTask cashTask;
    private int cashCursor;
    private static final org.bukkit.NamespacedKey CASH_KEY=new org.bukkit.NamespacedKey("chrdk_core","cash_transfer");
    /** Пауза между переводами: uuid → момент последнего перевода (мс). */
    private final Map<UUID, Long> lastPay = new ConcurrentHashMap<>();

    /** Ленивая инициализация: юнит-тесты разбора сумм не должны трогать Bukkit-предметы. */
    private static final class Plain {
        private static final ItemStack DIAMOND = new ItemStack(Material.DIAMOND);
        private static final ItemStack BLOCK = new ItemStack(Material.DIAMOND_BLOCK);

        private Plain() {
        }
    }

    public BankService(RegisterPlugin plugin, BankStore store, Supplier<EconomySettings> settings) {
        this.plugin = plugin;
        this.store = store;
        this.settings = settings;
    }

    public BankStore store() {
        return store;
    }

    public EconomySettings settings() {
        return settings.get();
    }

    public boolean enabled() {
        return settings().enabled() && store.available();
    }

    /* ---------------------------------- счёт ---------------------------------- */

    /** Открывает счёт при первом обращении и обновляет ник. */
    public long balance(Player player) {
        if (player == null) {
            return 0L;
        }
        return store.open(player.getUniqueId(), player.getName()).balance;
    }

    /** Баланс по нику (онлайн или сохранённый офлайн-счёт). */
    public long balanceOf(String name) {
        return store.balanceOf(name);
    }

    /** Счёт существует и имя известно банку. */
    public boolean known(String name) {
        return store.idByName(name) != null;
    }

    /* -------------------------------- депозит --------------------------------- */

    /**
     * Складывает алмазы в банк.
     *
     * @param handOnly только предмет в руке (true) или весь инвентарь (false)
     * @return сколько алмазов зачислено
     */
    public long deposit(Player player, boolean handOnly) {
        return depositPlanned(player, handOnly, -1);
    }

    /** Числовой депозит принимает только алмазы, без автоматического размена блоков. */
    public long depositAmount(Player player, long amount) {
        return amount <= 0 ? 0 : depositPlanned(player, false, amount);
    }

    public boolean authenticated(Player player) {
        var api=Bukkit.getServicesManager().load(ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi.class);
        return Bukkit.isPrimaryThread()&&player!=null&&player.isOnline()&&!player.hasMetadata("NPC")&&api!=null&&api.isAuthenticated(player);
    }
    private boolean cashReady(Player player) {
        if(!enabled()||!authenticated(player)||player.isDead()||!player.hasPermission("chrdkregister.bal")
                ||(player.getGameMode()!=org.bukkit.GameMode.SURVIVAL&&player.getGameMode()!=org.bukkit.GameMode.ADVENTURE)) return false;
        // Нельзя разменять физические деньги уже принятой оплаты аддона до её native-checkpoint.
        if(!new BankChargeGuard(plugin,store).check(player)) return false;
        boolean pending=store.cashLocked(player.getUniqueId());
        var result=recoverCash(player);
        // Повтор команды сначала завершает старый intent, но не создаёт второй обмен тем же нажатием.
        return !pending&&result==ru.cherdakmd.chrdkregister.api.InventoryTransfer.Decision.APPLIED;
    }
    private ru.cherdakmd.chrdkregister.api.InventoryTransfer.Decision recoverCash(Player player) {
        try {
            var pending=store.cashPending(player.getUniqueId());
            if(pending!=null&&!BankCashInventory.valid(pending)) {
                store.quarantineCash(); throw new IllegalStateException("Повреждён native-план bank.json; файл не перезаписывается");
            }
            var port=ru.cherdakmd.chrdkregister.api.ChargeInventory.inventory(player,CASH_KEY,()->authenticated(player));
            var result=BankCash.recover(store,player.getUniqueId(),port);
            if(result!=ru.cherdakmd.chrdkregister.api.InventoryTransfer.Decision.APPLIED) lockCash(player);
            return result;
        } catch(Exception error) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE,"Банк: обмен с playerdata не подтверждён; не удаляйте bank.json",error);
            lockCash(player); return ru.cherdakmd.chrdkregister.api.InventoryTransfer.Decision.WAIT;
        }
    }
    public boolean fundClaimPayment(Player player,long cost) {
        if(!enabled()||!authenticated(player)||player.isDead()||player.getGameMode()!=org.bukkit.GameMode.SURVIVAL||!player.hasPermission("chrdkclaims.use")||cost<0||cost>BankStore.MAX_BALANCE||store.cashLocked(player.getUniqueId())||!new BankChargeGuard(plugin,store).check(player)) return false;
        if(recoverCash(player)!=ru.cherdakmd.chrdkregister.api.InventoryTransfer.Decision.APPLIED) return false;
        long balance=store.balance(player.getUniqueId()); if(balance>=cost) return true;
        var before=ru.cherdakmd.chrdkregister.api.ChargeInventory.copy(player.getInventory().getStorageContents());
        var tender=ru.cherdakmd.chrdkregister.api.ChargeInventory.plan(before,cost,balance);
        if(tender==null||tender.cash()>BankStore.MAX_BALANCE-balance) return false;
        return exchangeCash(player,BankCash.Kind.CLAIM_FUND,new BankCashInventory.Plan(tender.after(),tender.cash()),before,9,-1,cost);
    }
    public boolean shopReady(Player player) {
        return enabled()&&authenticated(player)&&!player.isDead()&&player.getGameMode()==org.bukkit.GameMode.SURVIVAL&&player.hasPermission("chrdkshop.use")
            &&!store.cashLocked(player.getUniqueId())&&new BankChargeGuard(plugin,store).check(player)
            &&recoverCash(player)==ru.cherdakmd.chrdkregister.api.InventoryTransfer.Decision.APPLIED;
    }
    public ru.cherdakmd.chrdkregister.api.ShopExchange.Status shopExchange(Player player,ru.cherdakmd.chrdkregister.api.ShopExchange t) {
        var unavailable=ru.cherdakmd.chrdkregister.api.ShopExchange.Status.UNAVAILABLE;
        if(!authenticated(player)||t==null||!player.getUniqueId().equals(t.owner())) return unavailable;
        var status=store.shopStatus(t);
        if(status==ru.cherdakmd.chrdkregister.api.ShopExchange.Status.COMMITTED) return status;
        if(status==ru.cherdakmd.chrdkregister.api.ShopExchange.Status.MISSING) {
            // Trusted market API accepts only a previously durable source intent: eligibility was frozen by ShopService.
            // Auth/native evidence remain live; changed config/permissions/mode cannot strand accepted obligations.
            if(!new BankChargeGuard(plugin,store).check(player)||!ru.cherdakmd.chrdkregister.api.ShopInventory.valid(t)) return unavailable;
            if(recoverCash(player)!=ru.cherdakmd.chrdkregister.api.InventoryTransfer.Decision.APPLIED) return unavailable;
            if(!ru.cherdakmd.chrdkregister.api.ChargeInventory.matches(player.getInventory().getStorageContents(),t.before())||!store.prepareShop(t,player.getName())) return unavailable;
        } else if(status!=ru.cherdakmd.chrdkregister.api.ShopExchange.Status.PREPARED) return status;
        recoverCash(player);
        return store.shopStatus(t);
    }
    private void lockCash(Player player) {
        try { player.kick(plugin.messages().get("economy.cash-playerdata-lock")); }
        catch(RuntimeException error) { player.kick(net.kyori.adventure.text.Component.text("Банк: обмен предметов требует сверки playerdata.")); }
    }
    @org.bukkit.event.EventHandler(priority=org.bukkit.event.EventPriority.HIGHEST)
    public void cashAuthenticated(ru.cherdakmd.chrdkregister.api.events.PlayerAuthCompletedEvent event) {
        if(authenticated(event.getPlayer())) recoverCash(event.getPlayer());
    }
    private long depositPlanned(Player player,boolean handOnly,long amount) {
        if(!cashReady(player)) return -1;
        var before=ru.cherdakmd.chrdkregister.api.ChargeInventory.copy(player.getInventory().getStorageContents());
        int selected=handOnly?player.getInventory().getHeldItemSlot():-1;
        int worth=settings().blockWorth();
        var plan=BankCashInventory.deposit(before,selected,amount,worth,store.balance(player.getUniqueId()));
        if(plan==null) return 0;
        return exchangeCash(player,BankCash.Kind.DEPOSIT,plan,before,worth,selected,amount)?plan.amount():-1;
    }
    private boolean exchangeCash(Player player,BankCash.Kind kind,BankCashInventory.Plan plan,ItemStack[] before,int worth,int selected,long request) {
        try {
            var intent=store.prepareCash(player.getUniqueId(),player.getName(),kind,plan.amount(),worth,selected,request,
                    ru.cherdakmd.chrdkregister.api.ChargeInventory.encode(before),ru.cherdakmd.chrdkregister.api.ChargeInventory.encode(plan.after()));
            if(intent==null) { if(!store.available()||store.cashLocked(player.getUniqueId())) lockCash(player); return false; }
            return recoverCash(player)==ru.cherdakmd.chrdkregister.api.InventoryTransfer.Decision.APPLIED;
        } catch(Exception error) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE,"Банковский обмен ожидает сверки, автоматического возврата нет",error);
            if(!store.available()||store.cashLocked(player.getUniqueId())) lockCash(player); return false;
        }
    }

    /* --------------------------------- снятие --------------------------------- */

    /** Точный storage-only grant; intent и резерв счёта durable до native-выдачи. */
    public WithdrawResult withdraw(Player player,long amount) {
        if(player==null||amount<=0) return new WithdrawResult(WithdrawStatus.NOT_ENOUGH,Math.max(0,amount),0);
        if(!cashReady(player)) return new WithdrawResult(WithdrawStatus.UNAVAILABLE,amount,store.balance(player.getUniqueId()));
        long balance=store.balance(player.getUniqueId());
        if(balance<amount) return new WithdrawResult(WithdrawStatus.NOT_ENOUGH,amount,balance);
        var before=ru.cherdakmd.chrdkregister.api.ChargeInventory.copy(player.getInventory().getStorageContents());
        var plan=BankCashInventory.withdraw(before,amount);
        if(plan==null) return new WithdrawResult(WithdrawStatus.NO_SPACE,amount,balance);
        boolean done=exchangeCash(player,BankCash.Kind.WITHDRAW,plan,before,1,-1,amount);
        return new WithdrawResult(done?WithdrawStatus.OK:WithdrawStatus.UNAVAILABLE,amount,store.balance(player.getUniqueId()));
    }

    /** Сколько алмазов влезает в инвентарь с учётом неполных стаков обычных алмазов. */
    public static long capacity(Player player) {
        long capacity = 0L;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.getType().isAir()) {
                capacity += 64L;
            } else if (stack.isSimilar(Plain.DIAMOND)) {
                capacity += Math.max(0, 64 - stack.getAmount());
            }
        }
        return capacity;
    }

    /* -------------------------------- переводы -------------------------------- */

    /** Остаток паузы в секундах (0 — можно платить). */
    public long cooldownLeft(Player player) {
        int cooldown = settings().payCooldownSeconds();
        if (cooldown <= 0 || player == null) {
            return 0L;
        }
        Long last = lastPay.get(player.getUniqueId());
        if (last == null) {
            return 0L;
        }
        long passed = System.currentTimeMillis() - last;
        long left = cooldown * 1000L - passed;
        return left <= 0L ? 0L : (left + 999L) / 1000L;
    }

    /**
     * Перевод другому игроку.
     *
     * @param targetName ник получателя (онлайн или сохранённый в банке)
     */
    public PayResult pay(Player source, String targetName, long amount) {
        EconomySettings config = settings();
        if (!enabled() || !authenticated(source) || !source.hasPermission("chrdkregister.pay"))
            return new PayResult(BankStore.Status.UNAVAILABLE,targetName,amount,0,0,0,0);
        String name = targetName == null ? "" : targetName.trim();
        if (source == null || name.isEmpty()) {
            return new PayResult(BankStore.Status.UNKNOWN_PLAYER, name, amount, 0, 0, 0, 0);
        }
        long cooldown = cooldownLeft(source);
        if (cooldown > 0L) {
            return new PayResult(BankStore.Status.COOLDOWN, name, amount, 0, 0, 0, cooldown);
        }
        if (amount < config.minPay() || amount > config.maxPay()) {
            return new PayResult(BankStore.Status.INVALID_AMOUNT, name, amount, 0, 0, 0, 0);
        }
        Player online = Bukkit.getPlayerExact(name);
        UUID targetId;
        String targetDisplay;
        if (online != null) {
            targetId = online.getUniqueId();
            targetDisplay = online.getName();
            store.open(targetId, targetDisplay);
        } else {
            targetId = config.allowOfflinePay() ? store.idByName(name) : null;
            targetDisplay = name;
        }
        if (targetId == null) {
            return new PayResult(BankStore.Status.UNKNOWN_PLAYER, name, amount, 0, 0, 0, 0);
        }
        if (targetId.equals(source.getUniqueId())) {
            return new PayResult(BankStore.Status.SELF_TRANSFER, name, amount, 0, 0, 0, 0);
        }
        store.open(source.getUniqueId(), source.getName());
        BankStore.TransferResult result = store.transfer(source.getUniqueId(), source.getName(),
                targetId, targetDisplay, amount, config.payTaxPercent());
        if (!result.ok()) {
            return new PayResult(result.status(), targetDisplay, amount, result.tax(),
                    result.credited(), result.fromBalance(), 0L);
        }
        lastPay.put(source.getUniqueId(), System.currentTimeMillis());
        return new PayResult(BankStore.Status.OK, targetDisplay, amount, result.tax(),
                result.credited(), result.fromBalance(), 0L);
    }

    public record PayQuote(BankStore.Status status, UUID recipient, String name, long amount,
                           long tax, long net, int percent) { }
    public PayQuote previewPay(Player player, String name, long amount) {
        var c=settings();
        if (!enabled() || !authenticated(player) || !player.hasPermission("chrdkregister.pay"))
            return new PayQuote(BankStore.Status.UNAVAILABLE,null,name,amount,0,0,c.payTaxPercent());
        Player online=Bukkit.getPlayerExact(name);
        UUID id=online!=null?online.getUniqueId():(c.allowOfflinePay()?store.idByName(name):null);
        String display=online==null?name:online.getName();
        if(amount<c.minPay()||amount>c.maxPay()) return new PayQuote(BankStore.Status.INVALID_AMOUNT,id,display,amount,0,0,c.payTaxPercent());
        var q=store.previewTransfer(player.getUniqueId(),id,amount,c.payTaxPercent());
        return new PayQuote(q.status(),id,display,amount,q.tax(),q.credited(),c.payTaxPercent());
    }

    /* ---------------------------------- топ ----------------------------------- */

    public List<BankStore.Entry> baltop() {
        return store.top(settings().baltopSize());
    }

    /* -------------------------------- утилиты --------------------------------- */

    /** Разбор суммы: {@code 1 000}, {@code 1k}, {@code 1.5k}, {@code all}. */
    public static long parseAmount(String raw) {
        if (raw == null) {
            return -1L;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "");
        if (value.isEmpty() || value.equals("all") || value.equals("все") || value.equals("всё")) {
            return -1L;
        }
        long multiplier = 1L;
        if (value.endsWith("k") || value.endsWith("к")) {
            multiplier = 1_000L;
            value = value.substring(0, value.length() - 1);
        } else if (value.endsWith("m") || value.endsWith("м")) {
            multiplier = 1_000_000L;
            value = value.substring(0, value.length() - 1);
        }
        try {
            if(value.length()>64||!value.matches("[0-9]+(?:\\.[0-9]+)?")) return -1L;
            long amount=new java.math.BigDecimal(value).multiply(java.math.BigDecimal.valueOf(multiplier)).longValueExact();
            return amount<1||amount>BankStore.MAX_BALANCE?-1L:amount;
        } catch (NumberFormatException | ArithmeticException exception) {
            return -1L;
        }
    }

    /** «1234» → «1 234» (разряды через узкий пробел — читается в чате). */
    public static String format(long amount) {
        return String.format(Locale.ROOT, "%,d", amount).replace(',', '\u202F');
    }

    /** Планировщик: периодическая запись файла. */
    public void start() {
        if(store.available()) for(var pending:store.cashPendingSnapshot()) if(!BankCashInventory.valid(pending)) {
            store.quarantineCash(); plugin.getLogger().severe("Банк остановлен: повреждён native-план cashPending. Сохраните bank.json для сверки."); break;
        }
        Bukkit.getPluginManager().registerEvents(this,plugin);
        cashTask=Bukkit.getScheduler().runTaskTimer(plugin,()->{
            var players=new java.util.ArrayList<>(Bukkit.getOnlinePlayers());
            for(int i=0;i<Math.min(16,players.size());i++) {
                var player=players.get(Math.floorMod(cashCursor++,players.size()));
                if(authenticated(player)) recoverCash(player);
            }
        },20L,20L);
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (store.isDirty()) {
                store.saveNow();
            }
        }, 100L, 100L);
    }

    public void shutdown() {
        if(cashTask!=null) cashTask.cancel();
        org.bukkit.event.HandlerList.unregisterAll(this);
        store.flush();
        lastPay.clear();
    }
}
