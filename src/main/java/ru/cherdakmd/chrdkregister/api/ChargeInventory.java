package ru.cherdakmd.chrdkregister.api;

import com.google.gson.Gson;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.io.IOException;
import java.util.*;

/** Native Paper-кодек и storage-only план. Не трогает offhand, броню, курсор и предметы с метаданными. */
public final class ChargeInventory {
    private static final Gson JSON = new Gson();
    public record Plan(ItemStack[] after, long debit, long cash, long change) { }
    public interface Source {
        String snapshot();
        void publish(String before, String after) throws IOException;
    }
    public static ItemStack[] copy(ItemStack[] items) { return Arrays.stream(items).map(i -> i == null ? null : i.clone()).toArray(ItemStack[]::new); }
    public static String encode(ItemStack[] items) {
        if (items == null || items.length != 36) throw new IllegalArgumentException("storage length");
        String[] encoded = new String[36];
        for (int i=0; i<36; i++) {
            var item = items[i]; if (item == null || item.getType().isAir()) continue;
            if (item.getAmount() <= 0 || item.getAmount() > item.getMaxStackSize()) throw new IllegalArgumentException("stack size");
            encoded[i] = Base64.getEncoder().encodeToString(item.serializeAsBytes());
            if (encoded[i].length() > 90_000) throw new IllegalArgumentException("stack capacity");
        }
        return JSON.toJson(encoded);
    }
    public static ItemStack[] decode(String encoded) {
        String[] values = JSON.fromJson(encoded, String[].class);
        if (values == null || values.length != 36) throw new IllegalArgumentException("storage snapshot");
        ItemStack[] items = new ItemStack[36];
        for (int i=0; i<36; i++) if (values[i] != null) {
            if (values[i].length() > 90_000) throw new IllegalArgumentException("stack capacity");
            var item = ItemStack.deserializeBytes(Base64.getDecoder().decode(values[i]));
            if (item == null || item.getType().isAir() || item.getAmount() <= 0 || item.getAmount() > item.getMaxStackSize()) throw new IllegalArgumentException("stack snapshot");
            items[i] = item;
        }
        return items;
    }
    public static boolean matches(ItemStack[] items, String snapshot) {
        var expected = decode(snapshot); if (items == null || items.length != 36) return false;
        for (int i=0; i<36; i++) {
            var item = items[i]; if (item != null && item.getType().isAir()) item = null;
            if (!Objects.equals(item, expected[i])) return false;
        }
        return true;
    }
    /** API 19: стоимость обычного предмета ровно по правилам предметной доплаты. */
    public static long cashValue(ItemStack item) {
        if (item == null || item.hasItemMeta() || item.getAmount() <= 0 || item.getAmount() > 64) return 0;
        int unit = switch (item.getType()) {
            case DIAMOND, DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE -> 1;
            case DIAMOND_BLOCK -> 9;
            default -> 0;
        };
        return (long) unit * item.getAmount();
    }
    public static Plan plan(ItemStack[] before, long price, long balance) {
        if (before == null || before.length != 36) return null;
        int[] counts = new int[36], values = new int[36];
        for (int i=0; i<36; i++) {
            var item = before[i];
            long cash = cashValue(item);
            if (cash > 0) { values[i] = (int)(cash / item.getAmount()); counts[i] = item.getAmount(); }
        }
        var tender = AuctionTender.plan(price, balance, counts, values); if (tender == null) return null;
        var after = copy(before);
        for (int i=0; i<36; i++) if (counts[i] != tender.remaining()[i]) {
            if (tender.remaining()[i] == 0) after[i] = null; else after[i].setAmount(tender.remaining()[i]);
        }
        return new Plan(after, tender.debit(), tender.cash(), tender.change());
    }
    public static boolean valid(ChargeJournal.Pending pending) {
        if (pending == null || pending.payment() == null || !pending.payment().valid()) return false;
        var c = pending.payment(); var plan = plan(decode(pending.beforeInventory()), c.price(), c.debit());
        return plan != null && plan.debit == c.debit() && plan.cash == c.cash() && plan.change == c.change()
                && matches(plan.after, pending.afterInventory())
                && c.inventoryHash().equals(AuctionPayment.hash(pending.beforeInventory(), pending.afterInventory()))
                && c.stateHash().equals(AuctionPayment.hash(pending.beforeState(), pending.afterState()));
    }
    /** Nonce сохраняется в том же playerdata, что и storage, и переживает очистку module WAL. */
    public static org.bukkit.NamespacedKey witnessKey(String source) {
        if (source == null || !source.matches("[a-z][a-z0-9-]{0,47}")) throw new IllegalArgumentException("source");
        return new org.bukkit.NamespacedKey("chrdk_core", "charge_" + source);
    }
    /** Общий native-порт: storage-only, отдельный nonce для каждого namespace. */
    public static InventoryTransfer.Inventory inventory(Player player,String source,java.util.function.BooleanSupplier authenticated) {
        return inventory(player,witnessKey(source),authenticated);
    }
    /** Отдельный key для небанковских/auction WAL: charge_* зарезервированы Root BankChargeGuard. */
    public static InventoryTransfer.Inventory inventory(Player player,org.bukkit.NamespacedKey key,java.util.function.BooleanSupplier authenticated) {
        if(player==null) return null;
        java.util.Objects.requireNonNull(key);
        return new InventoryTransfer.Inventory() {
            public UUID owner() { return player.getUniqueId(); }
            public boolean available() { return org.bukkit.Bukkit.isPrimaryThread()&&player.isOnline()&&!player.hasMetadata("NPC")&&authenticated.getAsBoolean(); }
            public String witness() { return player.getPersistentDataContainer().get(key,org.bukkit.persistence.PersistentDataType.STRING); }
            public boolean matches(String snapshot) { return ChargeInventory.matches(player.getInventory().getStorageContents(),snapshot); }
            public void apply(String after,String token) {
                player.getInventory().setStorageContents(decode(after));
                player.getPersistentDataContainer().set(key,org.bukkit.persistence.PersistentDataType.STRING,token);
                checkpoint();
            }
            public void checkpoint() { player.saveData(); }
        };
    }
    /** При cash=0 актуальный playerdata не нужен; при доплате нужен durable native nonce. */
    public static ChargeRecovery.Decision recover(ChargeJournal journal,ChrdkRegisterApi api,Player player,Source source) throws IOException {
        var pending=journal.pending(); if(pending==null) return ChargeRecovery.Decision.COMPLETE;
        if(!journal.healthy()||!valid(pending)) return ChargeRecovery.Decision.CONFLICT;
        if(api==null) return ChargeRecovery.Decision.WAIT;
        return ChargeRecovery.recover(journal,api.bankChargeStatus(pending.payment()),
                pending.payment().cash()==0?null:inventory(player,pending.payment().source(),()->api.isAuthenticated(player)),source);
    }
    private ChargeInventory() { }
}
