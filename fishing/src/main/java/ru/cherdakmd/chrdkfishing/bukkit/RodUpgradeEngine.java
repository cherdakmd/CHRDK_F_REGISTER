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
import ru.cherdakmd.chrdkfishing.RodUpgradeJournal;

/** Main-thread adapter; a separate source lineage is required before wiring this to a GUI. */
final class RodUpgradeEngine implements AutoCloseable {
    private static final NamespacedKey WITNESS = new NamespacedKey("chrdk_fishing", "rod_upgrade_transfer");
    private final RodUpgradeJournal journal;
    private final CoreRodBank bank;
    private final RodItemCodec codec;
    private final byte[] key;
    private final Predicate<Player> authenticated;
    private final Method encode, decode, matches;
    RodUpgradeEngine(RodUpgradeJournal journal, CoreRodBank bank, RodItemCodec codec, byte[] key,
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
        return player != null && Bukkit.isPrimaryThread() && player.isOnline() && !player.isDead()
                && !player.hasMetadata("NPC") && authenticated.test(player) && player.hasPermission("chrdkfishing.use")
                && (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE);
    }
    int rodLevel(ItemStack item) throws Exception {
        if (item == null || item.getType() != org.bukkit.Material.FISHING_ROD) return 1;
        var token = codec.read(item);
        if (token.isEmpty() || !journal.ready(bank) || !journal.current(token.get())) return 1;
        return token.get().level();
    }
    UUID pendingOwner() throws java.sql.SQLException { return journal.pendingOwner(); }
    RodUpgradeJournal.Result cancelUnpaid(Player player) throws Exception {
        if (!available(player)) return RodUpgradeJournal.Result.WAIT;
        UUID owner = journal.pendingOwner();
        if (owner != null && !owner.equals(player.getUniqueId())) return RodUpgradeJournal.Result.CONFLICT;
        return journal.cancelUnpaid(bank);
    }
    static final class InsufficientFunds extends Exception { }
    static final class BalanceUnavailable extends Exception { }
    static void requireBalance(long available, long price) throws InsufficientFunds, BalanceUnavailable {
        if (available < 0) throw new BalanceUnavailable();
        if (available < price) throw new InsufficientFunds();
    }
    private void requireBalance(Player player, long price) throws Exception {
        requireBalance(bank.spendable(player.getUniqueId()), price);
    }
    RodUpgradePlanner.Plan quote(Player player, int slot) throws Exception {
        if (!available(player) || journal.pendingOwner() != null) throw new IllegalStateException("upgrade unavailable");
        var plan = RodUpgradePlanner.plan(player.getInventory().getStorageContents(), slot, codec, key, UUID.randomUUID());
        if (plan.oldToken() != null && !journal.current(plan.oldToken())) throw new IllegalArgumentException("rod identity not current");
        requireBalance(player, plan.cost().minor());
        return plan;
    }
    RodUpgradeJournal.Result confirm(Player player, RodUpgradePlanner.Plan plan) throws Exception {
        if (!available(player)) return RodUpgradeJournal.Result.WAIT;
        if (plan == null || journal.pendingOwner() != null) return RodUpgradeJournal.Result.CONFLICT;
        String before = (String) encode.invoke(null, (Object) plan.before());
        String after = (String) encode.invoke(null, (Object) plan.after());
        if (!(boolean) matches.invoke(null, player.getInventory().getStorageContents(), before)) return RodUpgradeJournal.Result.CONFLICT;
        // This preflight is advisory, not a reservation; the journal still handles uncertain commits.
        requireBalance(player, plan.cost().minor());
        var prepared = journal.prepare(player.getUniqueId(), UUID.randomUUID(), plan.oldToken(), plan.newToken(),
                plan.cost().minor(), before, after, bank);
        if (prepared != RodUpgradeJournal.Result.WAIT) return prepared;
        // Only this newly confirmed intent is authorized to initiate a debit.
        return recover(player, true);
    }
    RodUpgradeJournal.Result recover(Player player) throws Exception {
        // Login/menu/background recovery may finish a committed payment but never initiate one.
        return recover(player, false);
    }
    private RodUpgradeJournal.Result recover(Player player, boolean allowNewCharge) throws Exception {
        if (!available(player)) return RodUpgradeJournal.Result.WAIT;
        UUID owner = journal.pendingOwner();
        if (owner != null && !owner.equals(player.getUniqueId())) return RodUpgradeJournal.Result.WAIT;
        return journal.recover(bank, (expectedOwner, id, before, after) -> {
            if (!available(player) || !expectedOwner.equals(player.getUniqueId())) return RodUpgradeJournal.Transfer.WAIT;
            var pdc = player.getPersistentDataContainer();
            if (id.toString().equals(pdc.get(WITNESS, PersistentDataType.STRING))) {
                player.saveData();
                return id.toString().equals(pdc.get(WITNESS, PersistentDataType.STRING))
                        ? RodUpgradeJournal.Transfer.APPLIED : RodUpgradeJournal.Transfer.CONFLICT;
            }
            ItemStack[] current = player.getInventory().getStorageContents();
            if (!(boolean) matches.invoke(null, current, before) || (boolean) matches.invoke(null, current, after))
                return RodUpgradeJournal.Transfer.CONFLICT;
            player.getInventory().setStorageContents((ItemStack[]) decode.invoke(null, after));
            pdc.set(WITNESS, PersistentDataType.STRING, id.toString());
            player.saveData();
            return id.toString().equals(pdc.get(WITNESS, PersistentDataType.STRING))
                    && (boolean) matches.invoke(null, player.getInventory().getStorageContents(), after)
                    ? RodUpgradeJournal.Transfer.APPLIED : RodUpgradeJournal.Transfer.CONFLICT;
        }, allowNewCharge);
    }
    @Override public void close() throws Exception {
        try { journal.close(); } finally { java.util.Arrays.fill(key, (byte) 0); }
    }
}
