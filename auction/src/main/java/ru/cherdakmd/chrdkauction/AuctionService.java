package ru.cherdakmd.chrdkauction;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.inventory.ItemStack;
import ru.cherdakmd.chrdkregister.api.events.PlayerAuthCompletedEvent;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

public final class AuctionService implements Listener {
    private final AuctionPlugin plugin;
    final AuctionStore store;
    AuctionService(AuctionPlugin plugin, AuctionStore store) { this.plugin = plugin; this.store = store; }
    boolean authenticated(Player p) {
        if (!p.hasPermission("chrdkauction.use")) { tell(p, "auction.permission"); return false; }
        if (!plugin.api().isAuthenticated(p)) { tell(p, "auction.auth"); return false; }
        return true;
    }
    boolean ready(Player p) {
        if (!authenticated(p)) return false;
        if (store.pending() != null) { tell(p, "auction.recovery"); return false; }
        if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) {
            tell(p, "auction.mode"); return false;
        }
        try { store.expire(System.currentTimeMillis()); return true; }
        catch (Exception e) { failed(p, e); return false; }
    }
    void tell(Player p, String key, Object... args) { plugin.messages().send(p, key, args); }
    @FunctionalInterface interface Prepare { void run(String before, String after) throws IOException; }
    private boolean transfer(Player p, ItemStack[] before, ItemStack[] after, Prepare prepare) {
        try {
            prepare.run(ItemCodec.inventory(before), ItemCodec.inventory(after));
            // After this point any failure retains the journal; never blindly undo or retry a transfer.
            p.getInventory().setStorageContents(after);
            p.saveData();
            store.complete();
            return true;
        } catch (Exception e) {
            failed(p, e);
            if (store.pending() != null && store.pending().player().equals(p.getUniqueId()))
                p.kick(plugin.messages().get("auction.recovery-kick"));
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
        AuctionStore.Lot lot = store.lot(id);
        if (lot == null || lot.seller().equals(p.getUniqueId()) || lot.expires() <= System.currentTimeMillis()) { tell(p,"auction.unavailable"); return; }
        ItemStack[] before = ItemCodec.copy(p.getInventory().getStorageContents());
        ItemStack[] after = charge(before, lot.price());
        if (after == null) { tell(p,"auction.no-diamonds","price",lot.price()); return; }
        if (transfer(p,before,after,(a,b) -> store.prepareBuy(p.getUniqueId(),id,System.currentTimeMillis(),a,b)))
            tell(p,"auction.bought","id",id);
    }
    /** Pure inventory planning: no live inventory mutation before the journal is durable. */
    static ItemStack[] charge(ItemStack[] before, int amount) {
        ItemStack[] after = ItemCodec.copy(before); int left = amount;
        for (int i=0; i<after.length && left>0; i++) if (DiamondWallet.isCurrency(after[i])) {
            int take = Math.min(left,after[i].getAmount()); int rest=after[i].getAmount()-take;
            if (rest==0) after[i]=null; else after[i].setAmount(rest);
            left-=take;
        }
        return left==0 ? after : null;
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
    void money(Player p) {
        if (!ready(p)) return;
        long balance=store.balance(p.getUniqueId()); int paid=(int)Math.min(balance,DiamondWallet.capacity(p));
        if (paid>0) {
            ItemStack[] before=ItemCodec.copy(p.getInventory().getStorageContents()), after=ItemCodec.copy(before);
            for (int left=paid;left>0;) {
                int n=Math.min(left,64); after=ItemCodec.insert(after,new ItemStack(Material.DIAMOND,n)); left-=n;
            }
            if (!transfer(p,before,after,(a,b)->store.prepareMoneyClaim(p.getUniqueId(),paid,a,b))) return;
        }
        tell(p,"auction.balance","balance",balance,"paid",paid,"remaining",balance-paid);
    }
    @EventHandler public void onAuth(PlayerAuthCompletedEvent event) {
        Player p=event.getPlayer();
        if (store.pending()!=null && store.pending().player().equals(p.getUniqueId()) && !recover(p)) return;
        int count=store.mail(p.getUniqueId()).size(); long balance=store.balance(p.getUniqueId());
        if (count>0 || balance>0) tell(p,"auction.pending-mail","count",count,"balance",balance);
    }
    boolean recover(Player p) {
        var pending=store.pending();
        if (pending==null) return true;
        if (!pending.player().equals(p.getUniqueId()) || !plugin.api().isAuthenticated(p)) return false;
        try {
            ItemStack[] inventory=p.getInventory().getStorageContents();
            switch (RecoveryDecision.of(ItemCodec.matches(inventory,pending.before()), ItemCodec.matches(inventory,pending.after()))) {
                case COMPLETE -> { p.saveData(); store.complete(); }
                case ROLLBACK -> store.rollback();
                case CONFLICT -> throw new IllegalStateException("Ambiguous inventory snapshots. Restore consistent backups; no automatic decision is safe.");
            }
            plugin.getLogger().warning("Recovered auction transfer for " + pending.player() + ": " + pending.operation());
            tell(p,"auction.recovered"); return true;
        } catch (Exception e) {
            failed(p,e); p.kick(plugin.messages().get("auction.recovery-kick")); return false;
        }
    }
    private void failed(Player p,Exception e) {
        plugin.getLogger().log(Level.SEVERE,"Auction operation failed. Inspect journal before manual refunds.",e);
        tell(p,"auction.error");
    }
}
