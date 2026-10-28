package ru.cherdakmd.chrdkclaims;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.inventory.ItemStack;
import java.util.*;

/** Никакого fallback в старую казну. Core отключён/UUID неизвестен/импорт не принят — деньги не трогаем. */
final class ClaimBankBridge implements ClaimAccounts {
    private final ClaimsPlugin plugin;
    private final boolean supported;
    ClaimBankBridge(ClaimsPlugin plugin) {
        this.plugin = plugin;
        boolean found;
        try {
            var api = ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi.class;
            api.getMethod("importLegacyClaims", String.class, long.class);
            api.getMethod("claimBankBalance", String.class);
            api.getMethod("exchangeClaimAccounts", Map.class, org.bukkit.entity.Player.class, ItemStack[].class, ItemStack[].class);
            found = true;
        } catch (ReflectiveOperationException | LinkageError error) {
            found = false;
            plugin.getLogger().warning("Финансы Claims приостановлены: требуется Core 1.4.222+; старый баланс не используется.");
        }
        supported = found;
    }
    @Override public boolean ready(String name) {
        return supported && Bukkit.isPrimaryThread() && plugin.store().moneyAvailable() && plugin.coreApi() != null
                && name != null && !name.isBlank()
                && plugin.coreApi().importLegacyClaims(name, plugin.store().legacyTreasuryOf(name));
    }
    @Override public long balance(String name) { if (!supported) return 0; ready(name); return plugin.coreApi() == null ? 0 : plugin.coreApi().claimBankBalance(name); }
    @Override public boolean exchange(Map<String, Long> deltas, String inventoryPayer) {
        if (!supported || deltas == null || deltas.isEmpty() || deltas.size() > 8) return false;
        var changes = new LinkedHashMap<String, Long>();
        for (var row : deltas.entrySet()) {
            if (!ready(row.getKey()) || row.getValue() == null) return false;
            changes.merge(row.getKey().toLowerCase(Locale.ROOT), row.getValue(), Long::sum);
        }
        if (inventoryPayer == null) return plugin.coreApi().exchangeClaimAccounts(changes, null, null, null);
        var player = Bukkit.getPlayerExact(inventoryPayer);
        if (player == null || !player.isOnline() || player.isDead() || player.hasMetadata("NPC")
                || player.getGameMode() != GameMode.SURVIVAL || !player.hasPermission("chrdkclaims.use")
                || !plugin.coreApi().isAuthenticated(player)) return false;
        String key = player.getName().toLowerCase(Locale.ROOT);
        long requested = changes.getOrDefault(key, 0L);
        if (requested > 0) return false; // Ручной плательщик, а не неявный получатель.
        var before = Arrays.stream(player.getInventory().getStorageContents()).map(i -> i == null ? null : i.clone()).toArray(ItemStack[]::new);
        int[] amounts = new int[36], values = new int[36];
        for (int i=0; i<before.length; i++) if (DiamondItems.plain(before[i])) {
            amounts[i] = before[i].getAmount(); values[i] = DiamondItems.valueOf(before[i].getType());
        }
        var plan = ClaimPayment.plan(-requested, balance(player.getName()), amounts, values);
        if (plan == null) return false;
        var after = Arrays.stream(before).map(i -> i == null ? null : i.clone()).toArray(ItemStack[]::new);
        for (int i=0; i<after.length; i++) if (amounts[i] != plan.remaining()[i]) {
            if (plan.remaining()[i] == 0) after[i] = null; else after[i].setAmount(plan.remaining()[i]);
        }
        changes.put(key, -plan.bank() + plan.change());
        boolean committed = plugin.coreApi().exchangeClaimAccounts(changes, player, before, after);
        if (committed) plugin.messages().send(player, "finance.payment", "bank", plan.bank(), "physical", plan.physical(), "change", plan.change());
        return committed;
    }
}
