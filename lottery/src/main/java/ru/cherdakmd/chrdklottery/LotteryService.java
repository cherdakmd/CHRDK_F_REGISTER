package ru.cherdakmd.chrdklottery;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.api.events.PlayerAuthCompletedEvent;

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
    private long announcedBoundary;
    private BukkitTask task;

    public LotteryService(LotteryPlugin plugin, LotteryStore store) { this.plugin = plugin; this.store = store; }
    public void start() throws IOException {
        LotteryConfig c = plugin.config();
        long boundary = DrawSchedule.lastBoundary(ZonedDateTime.now(c.zone()), c.drawDay(), c.drawTime())
                .toInstant().toEpochMilli();
        // First install is not a missed draw; do not raffle newly purchased tickets 30 seconds later.
        if (store.lastDrawAt() == 0 || !c.catchUpOnStart()) store.markDrawnNow(boundary);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 600L, 600L);
    }
    public void shutdown() { if (task != null) { task.cancel(); task = null; } }
    private void tick() {
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
                // Prize remains in the ledger until explicitly claimed, including for online winners.
                for (Player p : Bukkit.getOnlinePlayers()) remindPrize(p);
            }
            return true;
        } catch (IOException | RuntimeException e) { fail(e); return false; }
    }
    @EventHandler public void onAuthenticated(PlayerAuthCompletedEvent event) { remindPrize(event.getPlayer()); }
    private void remindPrize(Player p) {
        if (plugin.coreApi().isAuthenticated(p) && store.pendingPayoutOf(p.getUniqueId()) > 0)
            plugin.messages().send(p, "lottery.pending-ready", "payout", store.pendingPayoutOf(p.getUniqueId()));
    }
    public boolean authenticated(Player p) {
        if (!plugin.coreApi().isAuthenticated(p)) { plugin.messages().send(p, "lottery.need-auth"); return false; }
        return true;
    }
    public void buyTickets(Player p, int count) {
        if (!authenticated(p)) return;
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
        ItemStack[] before = snapshot(p);
        if (!DiamondWallet.charge(p, cost)) {
            plugin.messages().send(p, "lottery.buy-no-diamonds", "cost", cost, "have", DiamondWallet.count(p)); return;
        }
        try { store.purchase(p.getUniqueId(), p.getName(), count, cost); }
        catch (IOException | RuntimeException e) {
            p.getInventory().setStorageContents(before); fail(e); plugin.messages().send(p, "lottery.storage-error"); return;
        }
        p.saveData();
        plugin.messages().send(p, "lottery.buy-success", "count", count, "cost", cost, "total", store.ticketsOf(p.getUniqueId()));
    }
    public void claimPrize(Player p) {
        if (!authenticated(p)) return;
        int pending = store.pendingPayoutOf(p.getUniqueId());
        if (pending <= 0) { plugin.messages().send(p, "lottery.no-prize"); return; }
        int amount = Math.min(pending, DiamondWallet.capacity(p));
        if (amount == 0) { plugin.messages().send(p, "lottery.inventory-full"); return; }
        ItemStack[] before = snapshot(p);
        DiamondWallet.give(p, amount);
        try { store.debitPrize(p.getUniqueId(), amount); }
        catch (IOException | RuntimeException e) {
            p.getInventory().setStorageContents(before); fail(e); plugin.messages().send(p, "lottery.storage-error"); return;
        }
        p.saveData();
        plugin.messages().send(p, "lottery.pending-paid", "payout", amount, "remaining", pending - amount);
    }
    private static ItemStack[] snapshot(Player p) {
        ItemStack[] result = p.getInventory().getStorageContents();
        for (int i = 0; i < result.length; i++) if (result[i] != null) result[i] = result[i].clone();
        return result;
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
