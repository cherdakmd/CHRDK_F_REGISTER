package ru.cherdakmd.chrdkfishing.bukkit;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import ru.cherdakmd.chrdkfishing.FishSaleJournal;

/**
 * Native storage-only sale bridge. The sale journal owns the payout decision;
 * the nonce and modified storage are checkpointed in the same playerdata file.
 * The caller must provide an already reconciled durable bank source and journal.
 * Source-wide receipt reconciliation catches another owner's payout after a journal rollback.
 */
final class FishSaleEngine implements AutoCloseable {
    private static final NamespacedKey WITNESS = new NamespacedKey("chrdk_fishing", "sale_transfer");
    private final FishSaleJournal journal;
    private final CoreSaleBank bank;
    private final FishItemCodec codec;
    private final byte[] key;
    private final boolean dishTextures;
    private final Predicate<Player> authenticated;
    private final Method encode, decode, matches;

    FishSaleEngine(FishSaleJournal journal, CoreSaleBank bank, FishItemCodec codec,
                   byte[] key, Predicate<Player> authenticated, ClassLoader coreLoader, boolean dishTextures) throws ReflectiveOperationException {
        this.journal = Objects.requireNonNull(journal); this.bank = Objects.requireNonNull(bank);
        this.codec = Objects.requireNonNull(codec); this.authenticated = Objects.requireNonNull(authenticated);
        this.key = Objects.requireNonNull(key).clone(); this.dishTextures = dishTextures;
        Class<?> nativeCodec = Class.forName("ru.cherdakmd.chrdkregister.api.ChargeInventory", true, coreLoader);
        encode = nativeCodec.getMethod("encode", ItemStack[].class);
        decode = nativeCodec.getMethod("decode", String.class);
        matches = nativeCodec.getMethod("matches", ItemStack[].class, String.class);
    }
    boolean available(Player player) {
        return player != null && Bukkit.isPrimaryThread() && player.isOnline()
                && !player.hasMetadata("NPC") && authenticated.test(player);
    }
    boolean hasPending(Player player) throws java.sql.SQLException {
        return player != null && (journal.pending(player.getUniqueId()) != null || journal.pendingCook(player.getUniqueId()) != null || journal.pendingFind(player.getUniqueId()) != null);
    }
    FishSalePlanner.Plan quote(Player player, List<Integer> slots) {
        if (!available(player)) throw new IllegalStateException("player unavailable");
        return FishSalePlanner.plan(player.getInventory().getStorageContents(), slots, codec);
    }
    /** Check the entire quoted inventory again, then persist PREPARED before removing anything. */
    FishSaleJournal.Result confirm(Player player, FishSalePlanner.Plan quote) throws Exception {
        if (!available(player)) return FishSaleJournal.Result.WAIT;
        if (!journal.historyMatchesAll(bank.allReceipts())) return FishSaleJournal.Result.CONFLICT;
        if (quote == null || hasPending(player))
            return FishSaleJournal.Result.CONFLICT;
        String before = (String) encode.invoke(null, (Object) quote.before());
        String after = (String) encode.invoke(null, (Object) quote.after());
        if (!(boolean) matches.invoke(null, player.getInventory().getStorageContents(), before))
            return FishSaleJournal.Result.CONFLICT;
        UUID transaction = UUID.randomUUID();
        var prepared = journal.prepareMixed(player.getUniqueId(), transaction, before, after, quote.fish(), quote.vanilla(), key, bank);
        if (prepared != FishSaleJournal.Result.WAIT) return prepared;
        return recover(player);
    }
    boolean orderClaimed(Player player, ru.cherdakmd.chrdkfishing.FishingOrders.Order order) throws java.sql.SQLException {
        return journal.orderClaimed(player.getUniqueId(), order.day(), order.slot());
    }
    FishSalePlanner.Plan quoteOrder(Player player, int slot, ru.cherdakmd.chrdkfishing.FishingOrders.Order order) throws Exception {
        if (!available(player) || hasPending(player) || order.day() != ru.cherdakmd.chrdkfishing.FishingOrders.utcDay()
                || orderClaimed(player, order)) throw new IllegalStateException("order unavailable");
        var plan = FishSalePlanner.plan(player.getInventory().getStorageContents(), List.of(slot), codec);
        if (plan.fish().size() != 1 || !order.matches(plan.fish().get(0))) throw new IllegalArgumentException("wrong order fish");
        return plan;
    }
    FishSaleJournal.Result confirmOrder(Player player, FishSalePlanner.Plan plan, ru.cherdakmd.chrdkfishing.FishingOrders.Order order) throws Exception {
        if (!available(player)) return FishSaleJournal.Result.WAIT;
        if (plan == null || order == null || hasPending(player) || plan.fish().size() != 1
                || !journal.historyMatchesAll(bank.allReceipts())) return FishSaleJournal.Result.CONFLICT;
        String before = (String) encode.invoke(null, (Object) plan.before());
        String after = (String) encode.invoke(null, (Object) plan.after());
        if (!(boolean) matches.invoke(null, player.getInventory().getStorageContents(), before)) return FishSaleJournal.Result.CONFLICT;
        var result = journal.prepareOrder(player.getUniqueId(), UUID.randomUUID(), before, after, plan.fish().get(0), key, bank, order);
        return result == FishSaleJournal.Result.WAIT ? recover(player) : result;
    }
    FishCookingPlanner.Plan quoteCook(Player player, int slot, FishCookingPlanner.Recipe recipe) throws Exception {
        if (!available(player) || hasPending(player)) throw new IllegalStateException("cooking unavailable");
        return FishCookingPlanner.plan(player.getInventory().getStorageContents(), slot, codec, recipe, dishTextures);
    }
    FishSaleJournal.Result confirmCook(Player player, FishCookingPlanner.Plan plan) throws Exception {
        if (!available(player)) return FishSaleJournal.Result.WAIT;
        if (plan == null || hasPending(player) || !journal.historyMatchesAll(bank.allReceipts())) return FishSaleJournal.Result.CONFLICT;
        String before = (String) encode.invoke(null, (Object) plan.before());
        String after = (String) encode.invoke(null, (Object) plan.after());
        if (!(boolean) matches.invoke(null, player.getInventory().getStorageContents(), before)) return FishSaleJournal.Result.CONFLICT;
        var prepared = journal.prepareCook(player.getUniqueId(), UUID.randomUUID(), before, after, plan.fish(), key);
        if (prepared != FishSaleJournal.Result.WAIT) return prepared;
        return recover(player);
    }
    /** Adds one ordinary resource to an empty slot without moving or consuming the caught fish. */
    FishSaleJournal.Result awardFind(Player player, ru.cherdakmd.chrdkfishing.FishToken.Token token, org.bukkit.Material material) throws Exception {
        if (!available(player)) return FishSaleJournal.Result.WAIT;
        if (hasPending(player) || !journal.historyMatchesAll(bank.allReceipts())) return FishSaleJournal.Result.CONFLICT;
        if (material != org.bukkit.Material.AMETHYST_SHARD && material != org.bukkit.Material.PRISMARINE_SHARD
                && material != org.bukkit.Material.IRON_NUGGET) throw new IllegalArgumentException("find material");
        ItemStack[] before = player.getInventory().getStorageContents();
        ItemStack[] after = java.util.Arrays.stream(before).map(item -> item == null ? null : item.clone()).toArray(ItemStack[]::new);
        int slot = -1;
        for (int i = 0; i < after.length; i++) if (after[i] == null || after[i].getType() == org.bukkit.Material.AIR) { slot = i; break; }
        if (slot < 0) return FishSaleJournal.Result.WAIT;
        after[slot] = new ItemStack(material, 1);
        String old = (String) encode.invoke(null, (Object) before), next = (String) encode.invoke(null, (Object) after);
        if (!(boolean) matches.invoke(null, player.getInventory().getStorageContents(), old)) return FishSaleJournal.Result.CONFLICT;
        var result = journal.prepareFind(player.getUniqueId(), UUID.randomUUID(), old, next, token, key);
        return result == FishSaleJournal.Result.WAIT ? recover(player) : result;
    }
    FishSaleJournal.Result recover(Player player) throws Exception {
        if (!available(player)) return FishSaleJournal.Result.WAIT;
        if (!journal.historyMatchesAll(bank.allReceipts())) return FishSaleJournal.Result.CONFLICT;
        UUID owner = player.getUniqueId(), transaction = journal.pending(owner), cooking = journal.pendingCook(owner), find = journal.pendingFind(owner);
        int pendingCount = (transaction == null ? 0 : 1) + (cooking == null ? 0 : 1) + (find == null ? 0 : 1);
        if (pendingCount > 1) return FishSaleJournal.Result.CONFLICT;
        if (pendingCount == 0) return FishSaleJournal.Result.DONE;
        FishSaleJournal.Inventory inventory = (expectedOwner, id, before, after) -> {
            if (!available(player) || !expectedOwner.equals(player.getUniqueId())) return FishSaleJournal.TransferResult.WAIT;
            String witness = player.getPersistentDataContainer().get(WITNESS, PersistentDataType.STRING);
            if (id.toString().equals(witness)) {
                // Checkpoint the current state again; a prior save may have thrown after applying it.
                player.saveData();
                return id.toString().equals(player.getPersistentDataContainer().get(WITNESS, PersistentDataType.STRING))
                        ? FishSaleJournal.TransferResult.APPLIED : FishSaleJournal.TransferResult.CONFLICT;
            }
            ItemStack[] current = player.getInventory().getStorageContents();
            if (!(boolean) matches.invoke(null, current, before) || (boolean) matches.invoke(null, current, after))
                return FishSaleJournal.TransferResult.CONFLICT;
            ItemStack[] target = (ItemStack[]) decode.invoke(null, after);
            player.getInventory().setStorageContents(target);
            player.getPersistentDataContainer().set(WITNESS, PersistentDataType.STRING, id.toString());
            player.saveData();
            return id.toString().equals(player.getPersistentDataContainer().get(WITNESS, PersistentDataType.STRING))
                    && (boolean) matches.invoke(null, player.getInventory().getStorageContents(), after)
                    ? FishSaleJournal.TransferResult.APPLIED : FishSaleJournal.TransferResult.CONFLICT;
        };
        return transaction != null ? journal.recover(owner, transaction, inventory, bank)
                : cooking != null ? journal.recoverCook(owner, cooking, inventory)
                : journal.recoverFind(owner, find, inventory);
    }
    @Override public void close() throws Exception {
        try { journal.close(); } finally { java.util.Arrays.fill(key, (byte) 0); }
    }
}
