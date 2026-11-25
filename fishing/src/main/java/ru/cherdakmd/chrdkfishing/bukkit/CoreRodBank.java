package ru.cherdakmd.chrdkfishing.bukkit;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.ServicesManager;
import ru.cherdakmd.chrdkfishing.RodUpgradeJournal;

/** Core bank-only high-water adapter; bank is never allowed to tender resource items. */
final class CoreRodBank implements RodUpgradeJournal.Bank {
    static final String SOURCE = "fishing-upgrades";
    private static final int RATE_BPS = 10000;
    private final ServicesManager services;
    private final String source;
    private final Class<?> api, bankCharge;
    private final Constructor<?> constructor;
    private final Method sequence, status, commit, balance, hash, decode;
    CoreRodBank(ServicesManager services, Class<?> api, ClassLoader loader) throws ReflectiveOperationException {
        this(services, api, loader, SOURCE);
    }
    CoreRodBank(ServicesManager services, Class<?> api, ClassLoader loader, String source) throws ReflectiveOperationException {
        this.services = java.util.Objects.requireNonNull(services); this.api = java.util.Objects.requireNonNull(api);
        if (!java.util.Set.of("fishing-upgrades", "fishing-bait", "fishing-horizons-rod", "fishing-horizons-bait").contains(source))
            throw new IllegalArgumentException("bank source");
        this.source = source;
        bankCharge = Class.forName("ru.cherdakmd.chrdkregister.api.BankCharge", true, loader);
        constructor = bankCharge.getConstructor(String.class, UUID.class, long.class, UUID.class, UUID.class,
                long.class, long.class, long.class, long.class, String.class, String.class, boolean.class, int.class);
        sequence = api.getMethod("bankChargeSequence", String.class, UUID.class);
        status = api.getMethod("bankChargeStatus", bankCharge);
        commit = api.getMethod("commitBankCharge", Player.class, bankCharge, ItemStack[].class);
        balance = api.getMethod("spendableBankBalanceMinor", UUID.class);
        hash = Class.forName("ru.cherdakmd.chrdkregister.api.AuctionPayment", true, loader).getMethod("hash", String.class, String.class);
        decode = Class.forName("ru.cherdakmd.chrdkregister.api.ChargeInventory", true, loader).getMethod("decode", String.class);
    }
    private Object payment(RodUpgradeJournal.Charge charge) throws ReflectiveOperationException {
        String inv = (String) hash.invoke(null, charge.before(), charge.before());
        String state = (String) hash.invoke(null, charge.before(), charge.after());
        return constructor.newInstance(source, charge.lineage(), charge.sequence(), charge.transaction(), charge.owner(),
                charge.minor(), charge.minor(), 0L, 0L, inv, state, true, RATE_BPS);
    }
    long spendable(UUID owner) throws Exception {
        Object service = services.load(api);
        return service == null ? -1 : (long) balance.invoke(service, owner);
    }
    @Override public long cursor(UUID lineage) throws Exception {
        Object service = services.load(api);
        return service == null ? -1 : (long) sequence.invoke(service, source, lineage);
    }
    @Override public RodUpgradeJournal.Status status(RodUpgradeJournal.Charge charge) throws Exception {
        Object service = services.load(api);
        if (service == null) return RodUpgradeJournal.Status.UNAVAILABLE;
        Object value = status.invoke(service, payment(charge));
        if (!(value instanceof Enum<?> result)) return RodUpgradeJournal.Status.UNAVAILABLE;
        try { return RodUpgradeJournal.Status.valueOf(result.name()); }
        catch (IllegalArgumentException invalid) { return RodUpgradeJournal.Status.CONFLICT; }
    }
    @Override public void charge(RodUpgradeJournal.Charge charge) throws Exception {
        Object service = services.load(api); Player player = Bukkit.getPlayer(charge.owner());
        if (service == null || player == null) return;
        ItemStack[] expected = (ItemStack[]) decode.invoke(null, charge.before());
        commit.invoke(service, player, payment(charge), expected);
    }
}
