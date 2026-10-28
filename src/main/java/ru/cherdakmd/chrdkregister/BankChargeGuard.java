package ru.cherdakmd.chrdkregister;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.persistence.PersistentDataType;
import ru.cherdakmd.chrdkregister.api.events.PlayerAuthCompletedEvent;
import java.util.*;

/** Root остаётся защитой и при выключенном addon: откатившийся playerdata не возвращает потраченные предметы в игру. */
final class BankChargeGuard implements Listener {
    private final RegisterPlugin plugin;
    private final BankStore bank;
    BankChargeGuard(RegisterPlugin plugin, BankStore bank) { this.plugin=plugin; this.bank=bank; }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAuthenticated(PlayerAuthCompletedEvent event) { check(event.getPlayer()); }
    void checkOnline() {
        var api = Bukkit.getServicesManager().load(ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi.class);
        if (api == null) return;
        for (var p:Bukkit.getOnlinePlayers()) if (api.isAuthenticated(p)) check(p);
    }
    static boolean matches(Map<String, UUID> expected, Map<String, String> actual) {
        if (expected == null || actual == null || expected.size() != actual.size()) return false;
        for (var row:expected.entrySet()) if (!row.getValue().toString().equals(actual.get(row.getKey()))) return false;
        return true;
    }
    private void check(Player p) {
        if (!p.isOnline()) return;
        var expected=bank.chargeWitnesses(p.getUniqueId()); var actual=new HashMap<String, String>();
        // Проверяем также orphan-маркеры: исчезнувшая банковская запись не означает «не было оплаты».
        try {
            for (var key:p.getPersistentDataContainer().getKeys()) if (key.getNamespace().equals("chrdk_core") && key.getKey().startsWith("charge_"))
                actual.put(key.getKey().substring("charge_".length()),p.getPersistentDataContainer().get(key,PersistentDataType.STRING));
        } catch (RuntimeException error) {
            plugin.getLogger().warning("Банк: невозможно прочитать playerdata marker " + p.getUniqueId() + ": " + error.getMessage());
            actual = null; // Ошибка чтения не выпускает игрока из проверки.
        }
        if (matches(expected,actual)) return;
        plugin.getLogger().severe("Банк: playerdata не подтверждает предметную оплату для " + p.getUniqueId() + "; игра заблокирована до сверки. Ожидаемые метки: " + expected);
        net.kyori.adventure.text.Component reason = net.kyori.adventure.text.Component.text("Банк: предметная оплата требует сверки playerdata администратором.");
        try { reason = plugin.messages().get("economy.charge-playerdata-lock"); }
        catch (RuntimeException error) { plugin.getLogger().warning("Некорректное сообщение финансовой блокировки: " + error.getMessage()); }
        p.kick(reason);
    }
}
