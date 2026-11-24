package ru.cherdakmd.chrdkfishing.bukkit;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.plugin.ServicesManager;
import ru.cherdakmd.chrdkfishing.AlbumRewardJournal;

/** Cumulative milestone receipts in minor units, isolated from all other reward sources. */
final class CoreAlbumBank implements AlbumRewardJournal.Bank {
    static final String SOURCE = "fishing-album";
    private final ServicesManager services;
    private final Class<?> api;
    private final Method available, receipts, credit;
    CoreAlbumBank(ServicesManager services, Class<?> api) throws ReflectiveOperationException {
        this.services = Objects.requireNonNull(services); this.api = Objects.requireNonNull(api);
        available = api.getMethod("incomeSourceAvailable", String.class, UUID.class);
        receipts = api.getMethod("incomeSourceReceipts", String.class, UUID.class);
        credit = api.getMethod("creditEntitlementMinor", String.class, UUID.class, String.class, UUID.class, long.class);
        if (available.getReturnType() != boolean.class || !Map.class.isAssignableFrom(receipts.getReturnType()) || credit.getReturnType() != boolean.class)
            throw new NoSuchMethodException("Core album receipt API");
    }
    private Object ready(UUID lineage) throws ReflectiveOperationException {
        Object service = services.load(api);
        return service != null && Boolean.TRUE.equals(available.invoke(service, SOURCE, lineage)) ? service : null;
    }
    @Override public Map<UUID, Long> receipts(UUID lineage) throws Exception {
        Object service = ready(lineage); if (service == null) return null;
        Object raw = receipts.invoke(service, SOURCE, lineage);
        if (!(raw instanceof Map<?, ?> map)) return null;
        Map<UUID, Long> result = new LinkedHashMap<>();
        for (var ownerRow : map.entrySet()) {
            if (!(ownerRow.getKey() instanceof UUID owner) || !(ownerRow.getValue() instanceof Map<?, ?> entries) || entries.size() != 1)
                throw new IllegalStateException("invalid album receipt owner");
            var value = entries.get(owner.toString());
            if (!(value instanceof Long minor) || minor < 0) throw new IllegalStateException("invalid album receipt key");
            result.put(owner, minor);
        }
        return Map.copyOf(result);
    }
    @Override public boolean credit(UUID lineage, UUID owner, long total) throws Exception {
        Object service = ready(lineage);
        return service != null && Boolean.TRUE.equals(credit.invoke(service, SOURCE, lineage, owner.toString(), owner, total));
    }
}
