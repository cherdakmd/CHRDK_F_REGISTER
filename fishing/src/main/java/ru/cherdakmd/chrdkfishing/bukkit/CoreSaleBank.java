package ru.cherdakmd.chrdkfishing.bukkit;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.plugin.ServicesManager;
import ru.cherdakmd.chrdkfishing.FishSaleJournal;

/**
 * Experimental adapter to Core API 52. This does not register a source or start
 * sales: its caller must durably establish and reconcile the source lineage first.
 * Core and journal use different key formats; no foreign key is silently ignored.
 */
final class CoreSaleBank implements FishSaleJournal.Bank {
    static final String SOURCE = "fishing-sale";
    private static final String PREFIX = "fishing:sale:";
    private final ServicesManager services;
    private final Class<?> api;
    private final UUID lineage;
    private final String source;
    private final Method available, receipts, allReceipts, credited, credit;

    CoreSaleBank(ServicesManager services, Class<?> api, UUID lineage, String source) throws ReflectiveOperationException {
        if (!java.util.Set.of(SOURCE, "fishing-horizons-sale").contains(source)) throw new IllegalArgumentException("sale source");
        this.source = source;
        this.services = Objects.requireNonNull(services); this.api = Objects.requireNonNull(api);
        this.lineage = Objects.requireNonNull(lineage);
        available = api.getMethod("incomeSourceAvailable", String.class, UUID.class);
        receipts = api.getMethod("incomeReceipts", String.class, UUID.class, UUID.class);
        allReceipts = api.getMethod("incomeSourceReceipts", String.class, UUID.class);
        credited = api.getMethod("creditedEntitlement", String.class, UUID.class, String.class, UUID.class);
        credit = api.getMethod("creditEntitlementMinor", String.class, UUID.class, String.class, UUID.class, long.class);
        if (available.getReturnType() != boolean.class || !Map.class.isAssignableFrom(receipts.getReturnType())
                || !Map.class.isAssignableFrom(allReceipts.getReturnType()) || credited.getReturnType() != long.class || credit.getReturnType() != boolean.class)
            throw new NoSuchMethodException("unsupported Core sale API");
    }
    private Object ready() throws ReflectiveOperationException {
        Object service = services.load(api);
        if (service == null || !Boolean.TRUE.equals(available.invoke(service, source, lineage))) return null;
        return service;
    }
    private static String id(String key) {
        if (key == null || !key.startsWith(PREFIX)) throw new IllegalArgumentException("receipt key");
        String id = key.substring(PREFIX.length());
        UUID parsed = UUID.fromString(id);
        if (!parsed.toString().equals(id)) throw new IllegalArgumentException("receipt identity");
        return id;
    }
    @Override public Map<String, Long> receipts(UUID owner) throws Exception {
        Objects.requireNonNull(owner);
        Object service = ready();
        if (service == null) return null;
        Object raw = receipts.invoke(service, source, lineage, owner);
        if (!(raw instanceof Map<?, ?> map)) return null;
        Map<String, Long> out = new LinkedHashMap<>();
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof Long amount) || amount < 0)
                throw new IllegalStateException("invalid Core receipt");
            String full = PREFIX + key;
            id(full);
            if (out.put(full, amount) != null) throw new IllegalStateException("duplicate receipt");
        }
        return Map.copyOf(out);
    }
    Map<UUID, Map<String, Long>> allReceipts() throws Exception {
        Object service = ready();
        if (service == null) return null;
        Object raw = allReceipts.invoke(service, source, lineage);
        if (!(raw instanceof Map<?, ?> map)) return null;
        Map<UUID, Map<String, Long>> result = new LinkedHashMap<>();
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof UUID owner) || !(entry.getValue() instanceof Map<?, ?> rows))
                throw new IllegalStateException("invalid Core source receipts");
            Map<String, Long> translated = new LinkedHashMap<>();
            for (var row : rows.entrySet()) {
                if (!(row.getKey() instanceof String key) || !(row.getValue() instanceof Long amount) || amount < 0)
                    throw new IllegalStateException("invalid Core source receipt");
                String full = PREFIX + key;
                id(full);
                if (translated.put(full, amount) != null) throw new IllegalStateException("duplicate receipt");
            }
            result.put(owner, Map.copyOf(translated));
        }
        return Map.copyOf(result);
    }
    @Override public boolean accept(UUID owner, String key, long amount) throws Exception {
        if (owner == null || amount <= 0 || amount > 1_000_000) return false;
        String id = id(key);
        Object service = ready();
        if (service == null) return false;
        long prior = (long) credited.invoke(service, source, lineage, id, owner);
        if (prior == amount) return true;
        if (prior != 0) return false;
        return Boolean.TRUE.equals(credit.invoke(service, source, lineage, id, owner, amount));
    }
}
