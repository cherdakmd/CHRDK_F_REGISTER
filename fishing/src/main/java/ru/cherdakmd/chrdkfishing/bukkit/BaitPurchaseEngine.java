package ru.cherdakmd.chrdkfishing.bukkit;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import ru.cherdakmd.chrdkfishing.BaitPurchaseJournal;
import ru.cherdakmd.chrdkfishing.RodUpgradeJournal;

/** Dev-only bank and native delivery adapter; not yet exposed to players. */
final class BaitPurchaseEngine implements AutoCloseable {
    private static final NamespacedKey WITNESS = new NamespacedKey("chrdk_fishing", "bait_purchase_transfer");
    private static final NamespacedKey EQUIP_WITNESS = new NamespacedKey("chrdk_fishing", "bait_equip_transfer");
    private final BaitPurchaseJournal journal;
    private final CoreRodBank bank;
    private final BaitItemCodec codec;
    private final byte[] key;
    private final Predicate<Player> authenticated;
    private final Method encode, decode, matches;
    BaitPurchaseEngine(BaitPurchaseJournal journal, CoreRodBank bank, BaitItemCodec codec, byte[] key,
                       Predicate<Player> authenticated, ClassLoader loader) throws ReflectiveOperationException {
        this.journal = Objects.requireNonNull(journal); this.bank = Objects.requireNonNull(bank);
        this.codec = Objects.requireNonNull(codec); this.key = Objects.requireNonNull(key).clone();
        this.authenticated = Objects.requireNonNull(authenticated);
        Class<?> nativeCodec = Class.forName("ru.cherdakmd.chrdkregister.api.ChargeInventory", true, loader);
        encode = nativeCodec.getMethod("encode", ItemStack[].class);
        decode = nativeCodec.getMethod("decode", String.class);
        matches = nativeCodec.getMethod("matches", ItemStack[].class, String.class);
    }
    boolean available(Player player) {
        return player != null && Bukkit.isPrimaryThread() && player.isOnline() && !player.isDead() && !player.hasMetadata("NPC")
                && authenticated.test(player) && player.hasPermission("chrdkfishing.use")
                && (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE);
    }
    UUID pendingOwner() throws java.sql.SQLException { return journal.pendingOwner(); }
    RodUpgradeJournal.Result cancelUnpaid(Player player) throws Exception {
        if (!available(player)) return RodUpgradeJournal.Result.WAIT;
        UUID owner = pendingOwner();
        if (owner != null && !owner.equals(player.getUniqueId())) return RodUpgradeJournal.Result.CONFLICT;
        return journal.cancelUnpaid(bank);
    }
    BaitPurchasePlanner.Plan quote(Player player, String kind) throws Exception {
        if (!available(player) || pendingOwner() != null) throw new IllegalStateException("bait purchase unavailable");
        return BaitPurchasePlanner.plan(player.getInventory().getStorageContents(), kind, codec, key, UUID.randomUUID());
    }
    RodUpgradeJournal.Result confirm(Player player, BaitPurchasePlanner.Plan plan) throws Exception {
        if (!available(player)) return RodUpgradeJournal.Result.WAIT;
        if (plan == null || pendingOwner() != null) return RodUpgradeJournal.Result.CONFLICT;
        String before = (String) encode.invoke(null, (Object) plan.before());
        String after = (String) encode.invoke(null, (Object) plan.after());
        if (!(boolean) matches.invoke(null, player.getInventory().getStorageContents(), before)) return RodUpgradeJournal.Result.CONFLICT;
        var result = journal.prepare(player.getUniqueId(), UUID.randomUUID(), plan.token(), before, after, bank);
        if (result != RodUpgradeJournal.Result.WAIT) return result;
        // WAIT can also mean the bank was unavailable before an intent was stored.
        // Do not report success or authorize a charge without a prepared intent.
        if (!player.getUniqueId().equals(pendingOwner())) return RodUpgradeJournal.Result.WAIT;
        return recover(player, true);
    }
    BaitPurchaseJournal.Equipped equipped(Player player) throws Exception {
        return player == null ? null : journal.equipped(player.getUniqueId());
    }
    RodUpgradeJournal.Result equip(Player player, int slot) throws Exception {
        if (!available(player)) return RodUpgradeJournal.Result.WAIT;
        ItemStack[] before = player.getInventory().getStorageContents();
        if (before.length != 36 || slot < 0 || slot >= 36) return RodUpgradeJournal.Result.CONFLICT;
        var token = codec.read(before[slot]).orElse(null);
        if (token == null) return RodUpgradeJournal.Result.CONFLICT;
        for (int i = 0; i < before.length; i++) {
            if (i == slot) continue;
            var other = codec.read(before[i]);
            if (other.isPresent() && other.get().id().equals(token.id())) return RodUpgradeJournal.Result.CONFLICT;
        }
        ItemStack[] after = java.util.Arrays.stream(before).map(item -> item == null ? null : item.clone()).toArray(ItemStack[]::new);
        after[slot] = null;
        String old = (String) encode.invoke(null, (Object) before), next = (String) encode.invoke(null, (Object) after);
        if (!(boolean) matches.invoke(null, player.getInventory().getStorageContents(), old)) return RodUpgradeJournal.Result.CONFLICT;
        var prepared = journal.prepareEquip(player.getUniqueId(), UUID.randomUUID(), token, old, next, bank);
        if (prepared != RodUpgradeJournal.Result.WAIT) return prepared;
        return recoverEquip(player);
    }
    RodUpgradeJournal.Result recoverEquip(Player player) throws Exception {
        if (!available(player)) return RodUpgradeJournal.Result.WAIT;
        return journal.recoverEquip(player.getUniqueId(), bank, inventory(player, EQUIP_WITNESS));
    }
    ru.cherdakmd.chrdkfishing.BaitCatalog.Bait reserve(Player player, UUID attempt) throws Exception {
        if (!available(player)) return null;
        return journal.reserveAttempt(player.getUniqueId(), attempt, bank);
    }
    boolean finish(UUID owner, UUID attempt, boolean success) throws java.sql.SQLException {
        return journal.finishAttempt(owner, attempt, success);
    }
    RodUpgradeJournal.Result recover(Player player) throws Exception {
        return recover(player, false);
    }
    private RodUpgradeJournal.Result recover(Player player, boolean allowNewCharge) throws Exception {
        if (!available(player)) return RodUpgradeJournal.Result.WAIT;
        UUID owner = pendingOwner();
        if (owner != null && !owner.equals(player.getUniqueId())) return RodUpgradeJournal.Result.WAIT;
        return journal.recover(bank, inventory(player, WITNESS), allowNewCharge);
    }
    private RodUpgradeJournal.Inventory inventory(Player player, NamespacedKey witness) {
        return (expected, id, before, after) -> {
            if (!available(player) || !expected.equals(player.getUniqueId())) return RodUpgradeJournal.Transfer.WAIT;
            var pdc = player.getPersistentDataContainer();
            if (id.toString().equals(pdc.get(witness, PersistentDataType.STRING))) {
                player.saveData();
                return id.toString().equals(pdc.get(witness, PersistentDataType.STRING))
                        ? RodUpgradeJournal.Transfer.APPLIED : RodUpgradeJournal.Transfer.CONFLICT;
            }
            ItemStack[] current = player.getInventory().getStorageContents();
            if (!(boolean) matches.invoke(null, current, before) || (boolean) matches.invoke(null, current, after))
                return RodUpgradeJournal.Transfer.CONFLICT;
            player.getInventory().setStorageContents((ItemStack[]) decode.invoke(null, after));
            pdc.set(witness, PersistentDataType.STRING, id.toString());
            player.saveData();
            return id.toString().equals(pdc.get(witness, PersistentDataType.STRING))
                    && (boolean) matches.invoke(null, player.getInventory().getStorageContents(), after)
                    ? RodUpgradeJournal.Transfer.APPLIED : RodUpgradeJournal.Transfer.CONFLICT;
        };
    }
    @Override public void close() throws Exception {
        try { journal.close(); } finally { java.util.Arrays.fill(key, (byte) 0); }
    }
}
