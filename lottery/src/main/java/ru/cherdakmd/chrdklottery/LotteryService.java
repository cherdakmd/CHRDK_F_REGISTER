package ru.cherdakmd.chrdklottery;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.api.events.PlayerAuthCompletedEvent;
import ru.cherdakmd.chrdkregister.api.*;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Level;

/** Main-thread orchestration; each ledger mutation is persisted synchronously before success is reported. */
public final class LotteryService implements Listener {
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d.MM.yyyy HH:mm z");
    private final LotteryPlugin plugin;
    private final LotteryStore store;
    private final SecureRandom random = new SecureRandom();
    private final Set<Integer> announced = new HashSet<>();
    private final Set<UUID> waitingWarned = new HashSet<>();
    private long announcedBoundary;
    private BukkitTask task;
    private ChargeJournal charges;
    private boolean awaitingStartBoundary;

    public LotteryService(LotteryPlugin plugin, LotteryStore store) { this.plugin = plugin; this.store = store; }
    public void start() throws IOException {
        store.enableBank();
        if (charges == null) {
            charges = new ChargeJournal(plugin.getDataFolder().toPath(), "lottery", store.incomeId());
            store.bindFinance(this::paymentsBusy);
            if (charges.pending() != null && !ChargeInventory.valid(charges.pending())) throw new IOException("Неверный платёжный план лотереи");
        }
        recoverCharge(null);
        if (!paymentsBusy()) ensureIncome();
        awaitingStartBoundary = true;
        finishStartBoundary();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 600L, 600L);
    }
    private void finishStartBoundary() throws IOException {
        if (!awaitingStartBoundary || paymentsBusy()) return;
        LotteryConfig c = plugin.config();
        long boundary = DrawSchedule.lastBoundary(ZonedDateTime.now(c.zone()), c.drawDay(), c.drawTime()).toInstant().toEpochMilli();
        if (store.lastDrawAt() == 0 || !c.catchUpOnStart()) store.markDrawnNow(boundary);
        awaitingStartBoundary = false;
    }
    public void shutdown() { if (task != null) { task.cancel(); task = null; } }
    private void tick() {
        recoverCharge(null);
        if (paymentsBusy() || !store.healthy()) return;
        try { finishStartBoundary(); } catch (IOException error) { fail(error); return; }
        settlePrizes();
        LotteryConfig c = plugin.config();
        ZonedDateTime now = ZonedDateTime.now(c.zone());
        long boundary = DrawSchedule.lastBoundary(now, c.drawDay(), c.drawTime()).toInstant().toEpochMilli();
        if (store.lastDrawAt() < boundary) { drawNow(); return; }
        ZonedDateTime next = DrawSchedule.nextBoundary(now, c.drawDay(), c.drawTime());
        long nextMillis = next.toInstant().toEpochMilli();
        if (nextMillis != announcedBoundary) { announced.clear(); announcedBoundary = nextMillis; }
        long seconds = Duration.between(now, next).getSeconds();
        for (int minutes : c.announceMinutes()) {
            if (seconds <= minutes * 60L && seconds > minutes * 60L - 30 && announced.add(minutes)) {
                broadcast("lottery.announce-soon", "left", humanizeLeft(seconds), "pot", store.pot(), "cost", c.costDiamonds());
            }
        }
    }
    public boolean drawNow() {
        if (paymentsBusy() || !ensureIncome()) return false;
        LotteryConfig c = plugin.config();
        int total = LotteryRound.totalTickets(store.ticketsSnapshot());
        long now = System.currentTimeMillis();
        if (now <= store.lastDrawAt()) return false;
        try {
            LotteryStore.LastWinner result = store.draw(now, c.payoutPercent(), random);
            if (result == null) broadcast("lottery.draw-no-tickets");
            else {
                broadcast("lottery.draw-broadcast", "player", result.playerName(), "pot", result.pot(),
                        "payout", result.payout(), "burn", result.pot() - result.payout(), "total", total);
                plugin.coreApi().notifyEvent("lottery.draw", plugin.messages().plain("lottery.notify-title"),
                        plugin.messages().plain("lottery.draw-broadcast", "player", result.playerName(),
                                "pot", result.pot(), "payout", result.payout(), "burn", result.pot() - result.payout(), "total", total), "good");
                settlePrizes();
                for (Player p : Bukkit.getOnlinePlayers()) remindPrize(p);
            }
            return true;
        } catch (IOException | RuntimeException e) { fail(e); return false; }
    }
    @EventHandler public void onAuthenticated(PlayerAuthCompletedEvent event) {
        Player p = event.getPlayer();
        var pending = charges == null ? null : charges.pending();
        if (pending != null && pending.payment().payer().equals(p.getUniqueId()) && !recoverCharge(p)) return;
        if (!paymentsBusy()) remindPrize(p);
    }
    private boolean ensureIncome() {
        if (!store.healthy() || paymentsBusy() || plugin.coreApi() == null || !plugin.coreApi().personalBankAvailable()) return false;
        if (store.bankRegistered()) return plugin.coreApi().incomeSourceAvailable("lottery", store.incomeId());
        if (!plugin.coreApi().registerIncomeSource("lottery", store.incomeId())) return false;
        try { store.markBankRegistered(); return true; }
        catch (IOException error) { fail(error); return false; }
    }
    private void settlePrizes() {
        if (!ensureIncome()) return;
        // UUID authoritative; totals are saved by draw BEFORE any bank credit. No debitPrize after credit.
        for (var row : store.entitlements().entrySet()) {
            UUID owner = UUID.fromString(row.getKey());
            if (!plugin.coreApi().creditEntitlement("lottery", store.incomeId(), row.getKey(), owner, row.getValue())) {
                if (waitingWarned.add(owner)) plugin.getLogger().warning("Выигрыш ожидает зачисления: " + owner + "; источник сохранён");
            } else waitingWarned.remove(owner);
        }
    }
    private void remindPrize(Player p) {
        if (!plugin.coreApi().isAuthenticated(p)) return;
        long total = store.entitlementOf(p.getUniqueId());
        if (total > 0) claimPrize(p);
    }
    public boolean authenticated(Player p) {
        if (!plugin.coreApi().isAuthenticated(p)) { plugin.messages().send(p, "lottery.need-auth"); return false; }
        return true;
    }
    public void buyTickets(Player p, int count) {
        if (!authenticated(p)) return;
        if (p.getGameMode() != org.bukkit.GameMode.SURVIVAL && p.getGameMode() != org.bukkit.GameMode.ADVENTURE || p.hasMetadata("NPC") || p.isDead()) {
            plugin.messages().send(p, "lottery.bank-mode"); return;
        }
        if (paymentsBusy()) { recoverCharge(p); if (paymentsBusy()) { plugin.messages().send(p, "lottery.bank-recovery"); return; } }
        if (!ensureIncome()) { plugin.messages().send(p, "lottery.bank-wait"); return; }
        LotteryConfig c = plugin.config();
        if (plugin.coreApi().rankLevel(p.getName()) < c.minRank()) {
            plugin.messages().send(p, "lottery.need-pass"); return;
        }
        if (count <= 0) { plugin.messages().send(p, "lottery.buy-not-a-number"); return; }
        if (count > c.maxPerPurchase()) {
            plugin.messages().send(p, "lottery.buy-limit-purchase", "max", c.maxPerPurchase()); return;
        }
        long due = DrawSchedule.lastBoundary(ZonedDateTime.now(c.zone()), c.drawDay(), c.drawTime()).toInstant().toEpochMilli();
        if (store.lastDrawAt() < due && !drawNow()) {
            plugin.messages().send(p, "lottery.storage-error"); return;
        }
        int owned = store.ticketsOf(p.getUniqueId());
        if (c.maxPerPlayer() > 0 && (long) owned + count > c.maxPerPlayer()) {
            plugin.messages().send(p, "lottery.buy-limit-player", "max", c.maxPerPlayer(), "tickets", owned); return;
        }
        long costLong = (long) count * c.costDiamonds();
        if (costLong + store.pot() > LotteryStore.MAX_POT
                || (long) count + LotteryRound.totalTickets(store.ticketsSnapshot()) > LotteryStore.MAX_TICKETS) {
            plugin.messages().send(p, "lottery.round-limit"); return;
        }
        int cost = (int) costLong;
        var before = ChargeInventory.copy(p.getInventory().getStorageContents());
        var plan = ChargeInventory.plan(before, cost, plugin.coreApi().auctionBankBalance(p.getUniqueId()));
        if (plan == null) { plugin.messages().send(p, "lottery.bank-not-enough", "cost", cost); return; }
        try {
            String afterState = store.previewPurchase(p.getUniqueId(), p.getName(), count, cost);
            String beforeState = store.snapshot();
            var payment = charges.prepare(p.getUniqueId(), cost, plan.debit(), plan.cash(), plan.change(),
                    ChargeInventory.encode(before), ChargeInventory.encode(plan.after()), beforeState, afterState);
            plugin.coreApi().commitBankCharge(p, payment, before);
            var decision = ChargeInventory.recover(charges, plugin.coreApi(), p, store);
            if (decision == ChargeRecovery.Decision.ROLLBACK) { plugin.messages().send(p, "lottery.bank-refused"); return; }
            if (decision == ChargeRecovery.Decision.WAIT || decision == ChargeRecovery.Decision.CONFLICT) {
                plugin.messages().send(p, "lottery.bank-recovery"); if (plan.cash() > 0) lock(p); return;
            }
            plugin.messages().send(p, "lottery.bank-bought", "count", count, "cost", cost, "change", plan.change(), "total", store.ticketsOf(p.getUniqueId()));
        } catch (Exception error) {
            fail(error);
            if (!charges.healthy() || !store.healthy() || charges.pending() != null && charges.pending().payment().cash() > 0) lock(p);
            plugin.messages().send(p, "lottery.storage-error");
        }
    }
    private boolean paymentsBusy() { return !store.healthy() || charges != null && (!charges.healthy() || charges.pending() != null); }
    private void lock(Player p) { p.kick(net.kyori.adventure.text.Component.text("Лотерея: незавершённая предметная оплата. Повторите вход после проверки банка; при конфликте нужен администратор.")); }
    private boolean recoverCharge(Player p) {
        if (charges == null || charges.pending() == null) return !paymentsBusy();
        var pending = charges.pending();
        try {
            var result = ChargeInventory.recover(charges, plugin.coreApi(), p, store);
            if (result == ChargeRecovery.Decision.WAIT || result == ChargeRecovery.Decision.CONFLICT) {
                if (p != null && pending.payment().payer().equals(p.getUniqueId()) && pending.payment().cash() > 0) lock(p);
                return false;
            }
            plugin.getLogger().info("Оплата лотереи восстановлена: " + pending.payment().transaction() + " / " + result); return true;
        } catch (Exception error) {
            fail(error); if (p != null && pending.payment().payer().equals(p.getUniqueId()) && pending.payment().cash() > 0) lock(p); return false;
        }
    }
    public boolean recoverPending() {
        var pending = charges == null ? null : charges.pending();
        return pending == null ? !paymentsBusy() : recoverCharge(pending.payment().cash() == 0 ? null : Bukkit.getPlayer(pending.payment().payer()));
    }
    public void claimPrize(Player p) {
        if (!authenticated(p)) return;
        long total = store.entitlementOf(p.getUniqueId());
        if (total <= 0) { plugin.messages().send(p, "lottery.no-prize"); return; }
        String key = p.getUniqueId().toString();
        if (!ensureIncome() || !plugin.coreApi().creditEntitlement("lottery", store.incomeId(), key, p.getUniqueId(), total)) {
            plugin.messages().send(p, "lottery.bank-wait"); return;
        }
        plugin.messages().send(p, "lottery.bank-paid", "balance", plugin.coreApi().auctionBankBalance(p.getUniqueId()));
    }
    private void fail(Exception e) { plugin.getLogger().log(Level.SEVERE, "Lottery operation failed; ledger not changed", e); }
    public void sendInfo(Player p) {
        if (!authenticated(p)) return;
        LotteryConfig c = plugin.config();
        Map<String, Integer> tickets = store.ticketsSnapshot();
        int total = LotteryRound.totalTickets(tickets);
        ZonedDateTime now = ZonedDateTime.now(c.zone());
        ZonedDateTime next = DrawSchedule.nextBoundary(now, c.drawDay(), c.drawTime());
        plugin.messages().send(p, "lottery.info", "pot", store.pot(), "total", total,
                "players", LotteryRound.participants(tickets), "when", WHEN.format(next),
                "left", humanizeLeft(Duration.between(now, next).getSeconds()), "cost", c.costDiamonds());
        int yours = store.ticketsOf(p.getUniqueId());
        plugin.messages().send(p, "lottery.info-yours", "tickets", yours,
                "chance", String.format(Locale.ROOT, "%.1f", LotteryRound.chancePermille(yours, total) / 10.0));
        remindPrize(p);
    }
    public void sendTop(CommandSender sender) {
        Map<String, Integer> tickets = store.ticketsSnapshot();
        if (tickets.isEmpty()) { plugin.messages().send(sender, "lottery.top-empty"); return; }
        plugin.messages().send(sender, "lottery.top-header");
        var sorted = tickets.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(10).toList();
        int place = 1;
        for (var entry : sorted) plugin.messages().send(sender, "lottery.top-line", "place", place++,
                "player", store.displayName(entry.getKey()), "tickets", entry.getValue());
    }
    public void sendLast(CommandSender sender) {
        var last = store.lastWinner();
        if (last == null) { plugin.messages().send(sender, "lottery.last-none"); return; }
        plugin.messages().send(sender, "lottery.last-won", "when", WHEN.format(Instant.ofEpochMilli(last.drawnAtEpochMillis()).atZone(plugin.config().zone())),
                "player", last.playerName(), "pot", last.pot(), "payout", last.payout());
    }
    private void broadcast(String key, Object... params) {
        for (Player p : Bukkit.getOnlinePlayers()) if (plugin.coreApi().isAuthenticated(p)) plugin.messages().send(p, key, params);
        plugin.getLogger().info(plugin.messages().plain(key, params));
    }
    public static String humanizeLeft(long seconds) {
        long minutes = Math.max(1, (seconds + 59) / 60);
        if (minutes >= 1440) return minutes / 1440 + " дн. " + minutes % 1440 / 60 + " ч.";
        if (minutes >= 60) return minutes / 60 + " ч. " + minutes % 60 + " мин.";
        return minutes + " мин.";
    }
}
