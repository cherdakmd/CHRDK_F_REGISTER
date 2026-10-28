package ru.cherdakmd.chrdkauction;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.inventory.ItemStack;
import ru.cherdakmd.chrdkregister.api.AuctionPayment;
import ru.cherdakmd.chrdkregister.api.events.PlayerAuthCompletedEvent;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

public final class AuctionService implements Listener {
    private final AuctionPlugin plugin;
    final AuctionStore store;
    private boolean migrationStarted;
    private org.bukkit.scheduler.BukkitTask migrationTask;
    AuctionService(AuctionPlugin plugin, AuctionStore store) { this.plugin = plugin; this.store = store; }
    boolean authenticated(Player p) {
        if (!p.hasPermission("chrdkauction.use")) { tell(p, "auction.permission"); return false; }
        if (!plugin.api().isAuthenticated(p)) { tell(p, "auction.auth"); return false; }
        return true;
    }
    private boolean financeAvailable() {
        return plugin.api() != null && store.healthy()
                && plugin.api().auctionBankAvailable();
    }
    long bankBalance(Player p) { return plugin.api().auctionBankBalance(p.getUniqueId()); }
    private boolean importOwner(UUID owner) {
        return store.bankMode() && plugin.api().importLegacyAuction(store.market(), owner, store.balance(owner));
    }
    void start() throws IOException {
        var pending = store.pending();
        if (pending != null && pending.payment() != null && pending.payment().cash() == 0) recoverBankOnly();
        if (store.pending() != null) return;
        store.enableBank();
        if (migrationStarted) return;
        migrationStarted = true;
        var rows = store.legacyMoney().entrySet().iterator();
        if (!rows.hasNext()) return;
        migrationTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!store.healthy() || store.pending() != null || !financeAvailable()) return;
            if (!rows.hasNext()) { migrationTask.cancel(); migrationTask = null; return; }
            var row = rows.next();
            if (!importOwner(row.getKey())) plugin.getLogger().warning("Не перенесена старая выручка аукциона " + row.getKey()
                    + ": предел /bal, несовместимая квитанция или недоступный банк. Источник сохранён; повтор при обращении владельца/покупке.");
        }, 1L, 1L);
    }
    void shutdown() { if (migrationTask != null) migrationTask.cancel(); }
    boolean ready(Player p) {
        if (!authenticated(p)) return false;
        if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) {
            tell(p, "auction.mode"); return false;
        }
        if (!store.healthy()) { tell(p, "auction.error"); return false; }
        try {
            start();
            if (store.pending() != null) { tell(p, "auction.recovery"); return false; }
            if (!financeAvailable() || !importOwner(p.getUniqueId())) { tell(p, "auction.finance-unavailable"); return false; }
            plugin.api().personalDiamonds(p); // Индекс имени — только из настоящего UUID игрока.
            store.expire(System.currentTimeMillis()); return true;
        } catch (Exception error) { failed(p, error); return false; }
    }
    void tell(Player p, String key, Object... args) { plugin.messages().send(p, key, args); }
    @FunctionalInterface interface Prepare { void run(String before, String after) throws IOException; }
    private boolean transfer(Player p, ItemStack[] before, ItemStack[] after, Prepare prepare) {
        try {
            prepare.run(ItemCodec.inventory(before), ItemCodec.inventory(after));
            p.getInventory().setStorageContents(after); p.saveData(); store.complete(); return true;
        } catch (Exception error) {
            failed(p, error);
            if (!store.healthy() || store.pending() != null && store.pending().player().equals(p.getUniqueId())) lockPlayer(p);
            return false;
        }
    }
    void sell(Player p, int price) {
        if (!ready(p)) return;
        AuctionConfig c = plugin.config();
        if (price < c.minPrice() || price > c.maxPrice()) { tell(p, "auction.price", "min", c.minPrice(), "max", c.maxPrice()); return; }
        ItemStack item = p.getInventory().getItemInMainHand();
        if (item.getType().isAir()) { tell(p, "auction.empty"); return; }
        if (c.blocked().contains(item.getType()) || item.getAmount() > item.getMaxStackSize()) { tell(p, "auction.blocked"); return; }
        String payload;
        try { payload = ItemCodec.encode(item); } catch (RuntimeException e) { tell(p, "auction.blocked"); return; }
        int rank = plugin.api() == null ? 0 : plugin.api().rankLevel(p.getName());
        int lotsLimit = c.lotsFor(rank);
        if (store.lots().size() >= c.maxLots() || store.lots().stream().filter(l -> l.seller().equals(p.getUniqueId())).count() >= lotsLimit) {
            tell(p, "auction.limit"); return;
        }
        ItemStack[] before = ItemCodec.copy(p.getInventory().getStorageContents()), after = ItemCodec.copy(before);
        after[p.getInventory().getHeldItemSlot()] = null;
        long now = System.currentTimeMillis(); long[] id = new long[1];
        if (transfer(p, before, after, (a,b) -> id[0] = store.prepareListing(p.getUniqueId(), p.getName(), payload,
                price, c.feePercent(), now + c.hours() * 3600000L, now, lotsLimit, c.maxLots(), a,b)))
            tell(p, "auction.listed", "id", id[0], "price", price, "fee", AuctionStore.fee(price,c.feePercent()),
                    "net", price - AuctionStore.fee(price,c.feePercent()));
    }
    void buy(Player p, long id) {
        if (!ready(p)) return;
        var lot = store.lot(id);
        if (lot == null || lot.expires() <= System.currentTimeMillis() || lot.seller().equals(p.getUniqueId())) {
            tell(p, "auction.unavailable"); return;
        }
        if (!importOwner(lot.seller())) { tell(p, "auction.seller-unavailable"); return; }
        var before = ItemCodec.copy(p.getInventory().getStorageContents());
        var plan = AuctionWallet.plan(before, lot.price(), bankBalance(p));
        if (plan == null) { tell(p, "auction.bank-no-diamonds", "price", lot.price()); return; }
        try {
            String a = ItemCodec.inventory(before), b = ItemCodec.inventory(plan.after());
            var payment = new AuctionPayment(UUID.randomUUID(), store.market(), store.nextBankSequence(), lot.id(), p.getUniqueId(), lot.seller(),
                    lot.price(), lot.fee(), plan.debit(), plan.cash(), plan.change(), AuctionPayment.hash(a,b));
            store.prepareBankBuy(payment, System.currentTimeMillis(), a, b);
            plugin.api().commitAuctionPayment(p, payment, before);
            var status = plugin.api().auctionPaymentStatus(payment);
            if (status != AuctionPayment.Status.COMMITTED) {
                if (status == AuctionPayment.Status.MISSING && ItemCodec.matches(p.getInventory().getStorageContents(), a)) {
                    store.rollbackBank(payment); tell(p, "auction.finance-refused");
                } else { tell(p, "auction.finance-unavailable"); if (payment.cash() > 0) lockPlayer(p); }
                return;
            }
            if (plan.cash() > 0) {
                if (!ItemCodec.matches(p.getInventory().getStorageContents(), a)) throw new IllegalStateException("Storage изменён после оплаты");
                p.getInventory().setStorageContents(plan.after()); p.saveData();
            }
            store.completeBank(payment);
            tell(p, "auction.bank-bought", "id", id, "change", plan.change());
            var seller = Bukkit.getPlayer(lot.seller());
            if (seller != null && plugin.api().isAuthenticated(seller)) tell(seller, "auction.sold", "id", id, "money", lot.price() - lot.fee());
        } catch (Exception error) {
            failed(p, error);
            if (!store.healthy() || store.pending() != null && store.pending().payment() != null && store.pending().payment().cash() > 0) lockPlayer(p);
        }
    }
    void cancel(Player p,long id) {
        if (!ready(p)) return;
        try { store.cancel(p.getUniqueId(),id); tell(p,"auction.cancelled","id",id); }
        catch (IllegalArgumentException e) { tell(p,"auction.unavailable"); }
        catch (Exception e) { failed(p,e); }
    }
    void claim(Player p,long id) {
        if (!ready(p)) return;
        var parcel=store.mail(p.getUniqueId()).stream().filter(x -> x.id()==id).findFirst().orElse(null);
        if (parcel==null) { tell(p,"auction.unavailable"); return; }
        ItemStack[] before=ItemCodec.copy(p.getInventory().getStorageContents());
        ItemStack[] after=ItemCodec.insert(before,ItemCodec.decode(parcel.item()));
        if (after==null) { tell(p,"auction.full"); return; }
        if (transfer(p,before,after,(a,b)->store.prepareItemClaim(p.getUniqueId(),id,a,b))) tell(p,"auction.claimed","id",id);
    }
    /** Совместимость команды: отдельного расходуемого кошелька больше нет. */
    void money(Player p) {
        if (ready(p)) tell(p, "auction.bank-balance", "balance", bankBalance(p));
    }
    private void lockPlayer(Player p) {
        try { p.kick(plugin.messages().get("auction.bank-recovery-kick")); }
        catch (RuntimeException error) { p.kick(net.kyori.adventure.text.Component.text("Аукцион: незавершённая операция. Вход закрыт до восстановления.")); }
    }
    private boolean recoverBankOnly() throws IOException {
        var pending = store.pending();
        if (pending == null || pending.payment() == null || pending.payment().cash() != 0) return false;
        if (!AuctionWallet.valid(pending)) throw new IllegalStateException("Некорректный банковский план");
        var decision = AuctionRecovery.decide(plugin.api().auctionPaymentStatus(pending.payment()), 0, false, false);
        if (decision == AuctionRecovery.Decision.COMPLETE) store.completeBank(pending.payment());
        else if (decision == AuctionRecovery.Decision.ROLLBACK) store.rollbackBank(pending.payment());
        return store.pending() == null;
    }
    private boolean recoverBank(Player p) throws IOException {
        var pending = store.pending(); var payment = pending.payment();
        if (!AuctionWallet.valid(pending)) throw new IllegalStateException("Некорректный физический план");
        // Полностью банковская покупка не читает/не восстанавливает актуальный storage владельца.
        if (payment.cash() == 0) return recoverBankOnly();
        var contents = p.getInventory().getStorageContents();
        var decision = AuctionRecovery.decide(plugin.api().auctionPaymentStatus(payment), payment.cash(),
                ItemCodec.matches(contents, pending.before()), ItemCodec.matches(contents, pending.after()));
        switch (decision) {
            case APPLY_AFTER -> { p.getInventory().setStorageContents(ItemCodec.inventory(pending.after())); p.saveData(); store.completeBank(payment); }
            case COMPLETE -> { p.saveData(); store.completeBank(payment); }
            case ROLLBACK -> store.rollbackBank(payment);
            case WAIT, CONFLICT -> { lockPlayer(p); return false; }
        }
        return true;
    }
    @EventHandler public void onAuth(PlayerAuthCompletedEvent event) {
        Player p = event.getPlayer();
        if (store.pending() != null && store.pending().player().equals(p.getUniqueId()) && !recover(p)) return;
        if (!store.healthy() || store.pending() != null || !p.isOnline()) return;
        try { start(); } catch (Exception error) { failed(p, error); return; }
        if (!financeAvailable() || !importOwner(p.getUniqueId())) { tell(p, "auction.finance-unavailable"); return; }
        int count = store.mail(p.getUniqueId()).size();
        if (count > 0 || store.balance(p.getUniqueId()) > 0) tell(p, "auction.bank-pending-mail", "count", count, "balance", bankBalance(p));
    }
    boolean recover(Player p) {
        var pending = store.pending();
        if (pending == null) return true;
        if (!pending.player().equals(p.getUniqueId()) || !plugin.api().isAuthenticated(p)) return false;
        if (!store.healthy()) { lockPlayer(p); return false; }
        try {
            if (pending.payment() != null) {
                if (!recoverBank(p)) return false;
            } else {
                var inventory = p.getInventory().getStorageContents();
                switch (RecoveryDecision.of(ItemCodec.matches(inventory,pending.before()), ItemCodec.matches(inventory,pending.after()))) {
                    case COMPLETE -> { p.saveData(); store.complete(); }
                    case ROLLBACK -> store.rollback();
                    case CONFLICT -> throw new IllegalStateException("Конфликт снимков. Нужна согласованная резервная копия; автоматический возврат запрещён.");
                }
            }
            start(); tell(p, "auction.bank-recovered"); return true;
        } catch (Exception error) { failed(p, error); lockPlayer(p); return false; }
    }
    boolean recoverWithoutPlayer() {
        if (!store.healthy()) return false;
        try { boolean recovered = recoverBankOnly(); if (recovered) start(); return recovered; }
        catch (Exception error) { plugin.getLogger().log(Level.SEVERE, "Не удалось восстановить банковскую покупку", error); return false; }
    }
    private void failed(Player p, Exception error) {
        plugin.getLogger().log(Level.SEVERE, "Auction operation failed. Inspect journal before manual refunds.", error);
        try { tell(p, "auction.error"); } catch (RuntimeException ignored) { plugin.getLogger().warning("Не удалось показать сообщение об ошибке"); }
    }
}
