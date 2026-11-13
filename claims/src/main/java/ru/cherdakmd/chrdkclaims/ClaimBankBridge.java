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
    public UUID registeredLineage() { return plugin.coreApi()==null?null:plugin.coreApi().claimSourceLineage(); }
    public long sequence(UUID lineage) { return plugin.coreApi()==null?-1L:plugin.coreApi().claimExchangeSequence(lineage); }
    public boolean ready(String name) {
        return Bukkit.isPrimaryThread()&&plugin.store().moneyAvailable()&&plugin.store().financialPending()==null
            &&plugin.coreApi()!=null&&name!=null&&!name.isBlank()&&plugin.finance()!=null&&plugin.finance().binding()
            &&plugin.coreApi().knownBankPlayer(name)!=null&&plugin.coreApi().bankBalanceMinor(name)>=0;
    }
    public boolean minor() { return true; }
    /** Whole-unit view for legacy domain math; fractional balances are conservatively rounded down. */
    public long balance(String name) {
        if(!ready(name))return 0;long minor=plugin.coreApi().bankBalanceMinor(name);return minor<0?0:minor/100;
    }
    /** No inventory tender: only spendable personal-bank funds count. */
    public long spendable(String name) {
        if(!ready(name))return 0;
        var id=plugin.coreApi().knownBankPlayer(name); if(id==null)return 0;
        long minor=plugin.coreApi().spendableBankBalanceMinor(id);return minor<0?0:minor/100;
    }
    public boolean exchange(Map<String,Long> changes,String payer) { return false; }
    public Map<UUID,Long> resolve(Map<String,Long> changes,String payer) {
        if(!Bukkit.isPrimaryThread()||plugin.coreApi()==null||changes==null||changes.isEmpty()) return null;
        var baseDeltas=new LinkedHashMap<UUID,Long>(); var balances=new LinkedHashMap<UUID,Long>();
        try {
            for(var e:changes.entrySet()) {
                if(e.getValue()==null||!ready(e.getKey())) return null;
                var id=plugin.coreApi().knownBankPlayer(e.getKey()); if(id==null) return null;
                long minor=plugin.coreApi().bankBalanceMinor(e.getKey()); if(minor<0)return null;
                Long prior=balances.putIfAbsent(id,minor); if(prior!=null&&prior.longValue()!=minor)return null;
                baseDeltas.merge(id,e.getValue(),Math::addExact);
            }
            var quote=plugin.coreApi().diamondRate(); var resolved=new LinkedHashMap<UUID,Long>();
            for(var e:baseDeltas.entrySet()) {
                long base=e.getValue(); long magnitude=base<0?Math.negateExact(base):base;
                long scaled=quote.scaleMinor(Math.multiplyExact(magnitude,100L));
                long delta=base<0?Math.negateExact(scaled):scaled;
                long after=Math.addExact(balances.get(e.getKey()),delta);
                if(after<0||after>100_000_000_000L)return null;
                resolved.put(e.getKey(),delta);
            }
            return resolved;
        } catch(RuntimeException e) { return null; }
    }
    public ClaimExchange.Status status(ClaimExchange t) { return plugin.coreApi()==null?ClaimExchange.Status.UNAVAILABLE:plugin.coreApi().claimExchangeStatus(t); }
    public ClaimExchange.Status commit(ClaimExchange t) { return plugin.coreApi()==null?ClaimExchange.Status.UNAVAILABLE:plugin.coreApi().commitClaimExchange(t); }
}
