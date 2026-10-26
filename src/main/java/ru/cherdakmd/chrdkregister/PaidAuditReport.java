package ru.cherdakmd.chrdkregister;

import com.google.gson.GsonBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Снимок для ручной сверки. Сумма оплат и последний источник НЕ доказывают состав оплаченного срока. */
final class PaidAuditReport {
    private PaidAuditReport() { }

    static String render(List<RegistrationStore.PlayerAccount> accounts, Map<String, String> receipts, long now) {
        List<Map<String, Object>> rows = accounts.stream().map(account -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("player", account.playerName);
            row.put("rankLevel", account.rankLevel);
            row.put("rankPermanent", account.rankPermanent);
            row.put("tempRankLevel", account.tempRankLevel);
            row.put("passUntil", account.passUntil);
            row.put("lastGrantSource", account.lastGrantSource);
            row.put("lastGrantAt", account.lastGrantAt);
            row.put("lastGrantHours", account.lastGrantHours);
            row.put("lastGrantDays", account.lastGrantDays);
            row.put("paymentsCount", account.paymentsCount);
            row.put("totalPaidKopecks", account.totalPaidKopecks);
            row.put("entitlements", account.entitlements);
            row.put("rewardBoostMillis", account.rewardBoostMillis);
            row.put("requiresPaymentHistory", account.hasRank(now) || !account.entitlements.isEmpty());
            return row;
        }).toList();
        return new GsonBuilder().setPrettyPrinting().create().toJson(Map.of(
                "generatedAt", now, "mode", "READ_ONLY_NO_REVOCATION",
                "warning", "Compare provider receipts, shop sale records, audit and backups. Lifetime total and last source are not proof of purchased rank/time. Reserved or failed receipts must not be replayed blindly.",
                "paymentReceipts", receipts, "accounts", rows));
    }
}
