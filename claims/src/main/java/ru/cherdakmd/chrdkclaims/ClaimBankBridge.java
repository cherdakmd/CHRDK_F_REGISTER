package ru.cherdakmd.chrdkclaims;

import org.bukkit.Bukkit;
import ru.cherdakmd.chrdkregister.api.ClaimExchange;
import java.util.*;

/** Production bank port. Legacy callback is deliberately unavailable: only frozen domain WAL may settle. */
final class ClaimBankBridge implements ClaimFinance.Bank {
    private final ClaimsPlugin plugin;
    ClaimBankBridge(ClaimsPlugin plugin) { this.plugin=plugin; }
    public boolean registered(UUID id) { return plugin.coreApi()!=null&&plugin.coreApi().claimSourceRegistered(id); }
    public boolean register(UUID id) { return plugin.coreApi()!=null&&plugin.coreApi().registerClaimSource(id); }
    public boolean ready(String name) {
        return Bukkit.isPrimaryThread()&&plugin.store().moneyAvailable()&&plugin.store().financialPending()==null&&plugin.coreApi()!=null&&name!=null&&!name.isBlank()
            &&plugin.finance()!=null&&plugin.finance().binding()&&plugin.coreApi().importLegacyClaims(name,plugin.store().legacyTreasuryOf(name));
    }
    public boolean minor() { return true; }
    public long balance(String name) {
        if(!ready(name))return 0;long minor=plugin.coreApi().bankBalanceMinor(name);if(minor<0)return 0;
        return (minor+99)/100;
    }
    public long spendable(String name) {
        long bank=balance(name); var player=Bukkit.getPlayerExact(name);
        long physical=player==null?0:DiamondItems.count(player.getInventory());
        return Math.addExact(bank,Math.multiplyExact(physical,(long)plugin.coreApi().diamondRateBps())/100);
    }
    public boolean exchange(Map<String,Long> changes,String payer) { return false; }
    public Map<UUID,Long> resolve(Map<String,Long> changes,String payer) {
        if(!Bukkit.isPrimaryThread()||plugin.coreApi()==null) return null;
        var resolved=new LinkedHashMap<UUID,Long>();
        try {
            for(var e:changes.entrySet()) {
                if(!ready(e.getKey())) return null;
                var id=plugin.coreApi().knownBankPlayer(e.getKey()); if(id==null) return null;
                resolved.merge(id,e.getValue(),Math::addExact);
                long value=plugin.coreApi().claimBankBalance(e.getKey())+e.getValue();
                if(value>1_000_000_000L||value<0&&(payer==null||!payer.equalsIgnoreCase(e.getKey()))) return null;
            }
        } catch(ArithmeticException e) { return null; }
        if(payer!=null) {
            var player=Bukkit.getPlayerExact(payer); long cost=Math.max(0,-changes.getOrDefault(payer.toLowerCase(Locale.ROOT),0L));
            if(player==null||!plugin.coreApi().fundClaimPaymentMinor(player,plugin.coreApi().diamondRate().scaleMinor(cost*100L))) return null;
        }
        for(var e:resolved.entrySet()) if(e.getValue() < -1_000_000_000L||e.getValue()>1_000_000_000L) return null;
        resolved.replaceAll((id,value)->plugin.coreApi().diamondRate().scaleMinor(value*100L));
        return resolved;
    }
    public ClaimExchange.Status status(ClaimExchange t) { return plugin.coreApi()==null?ClaimExchange.Status.UNAVAILABLE:plugin.coreApi().claimExchangeStatus(t); }
    public ClaimExchange.Status commit(ClaimExchange t) { return plugin.coreApi()==null?ClaimExchange.Status.UNAVAILABLE:plugin.coreApi().commitClaimExchange(t); }
}
