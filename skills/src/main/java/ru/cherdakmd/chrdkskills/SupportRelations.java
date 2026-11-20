package ru.cherdakmd.chrdkskills;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/** Optional, read-only Guilds adapter. No caching; UNKNOWN never grants support. */
final class SupportRelations {
    record Snapshot(SupportTargetRules.GuildRelation guild, SupportTargetRules.GroupRelation group) { }
    private static final Snapshot UNKNOWN = new Snapshot(SupportTargetRules.GuildRelation.UNKNOWN,
            SupportTargetRules.GroupRelation.UNKNOWN);

    /** Caller must use the returned evidence only in the same server-thread operation. */
    static Snapshot current(UUID first, UUID second) {
        if (first == null || second == null || first.equals(second) || !Bukkit.isPrimaryThread()) return UNKNOWN;
        try {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("CHRDK_Guilds");
            if (plugin == null || !plugin.isEnabled()) return UNKNOWN;
            return read(plugin, first, second);
        } catch (RuntimeException | LinkageError e) {
            return UNKNOWN;
        }
    }

    static Snapshot read(Object plugin, UUID first, UUID second) {
        if (plugin == null || first == null || second == null || first.equals(second)
                || !plugin.getClass().getName().equals("ru.cherdakmd.chrdkguilds.GuildsPlugin")) return UNKNOWN;
        try {
            Object service = plugin.getClass().getMethod("service").invoke(plugin);
            if (service == null || !service.getClass().getName().equals("ru.cherdakmd.chrdkguilds.GuildService")) return UNKNOWN;
            Object guild = service.getClass().getMethod("relation", UUID.class, UUID.class).invoke(service, first, second);
            Object group = plugin.getClass().getMethod("groupRelation", UUID.class, UUID.class).invoke(plugin, first, second);
            return translate(guild, group);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return UNKNOWN;
        }
    }

    static Snapshot translate(Object guild, Object group) {
        var g = SupportTargetRules.GuildRelation.UNKNOWN;
        var p = SupportTargetRules.GroupRelation.UNKNOWN;
        if (guild instanceof Enum<?> value && value.getDeclaringClass().getName().equals("ru.cherdakmd.chrdkguilds.GuildRelation")) {
            try { g = SupportTargetRules.GuildRelation.valueOf(value.name()); }
            catch (IllegalArgumentException ignored) { }
        }
        if (group instanceof Enum<?> value && value.getDeclaringClass().getName().equals("ru.cherdakmd.chrdkguilds.AdventureGroupRelation")) {
            try { p = SupportTargetRules.GroupRelation.valueOf(value.name()); }
            catch (IllegalArgumentException ignored) { }
        }
        return new Snapshot(g, p);
    }

    private SupportRelations() { }
}
