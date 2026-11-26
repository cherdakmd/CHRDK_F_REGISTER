package ru.cherdakmd.chrdkregister;

import java.util.Set;
import ru.cherdakmd.chrdkregister.api.AuctionPayment;
import ru.cherdakmd.chrdkregister.api.BankCharge;

/** Guard for a no-item-tender payment tied to the exact journal snapshot, not reserialization. */
final class FishingSnapshotChargeGuard {
    private static final Set<String> SOURCES = Set.of("fishing-upgrades", "fishing-bait", "fishing-horizons-rod", "fishing-horizons-bait");
    private static final int MAX_SNAPSHOT = 4 * 1024 * 1024;
    static boolean valid(BankCharge payment, String snapshot) {
        return payment != null && payment.valid() && SOURCES.contains(payment.source())
                && BankCharge.bankOnlySource(payment.source()) && payment.minor()
                && payment.debit() == payment.price() && payment.cash() == 0 && payment.change() == 0
                && snapshot != null && !snapshot.isEmpty() && snapshot.length() <= MAX_SNAPSHOT
                && payment.inventoryHash().equals(AuctionPayment.hash(snapshot, snapshot));
    }
    private FishingSnapshotChargeGuard() { }
}
