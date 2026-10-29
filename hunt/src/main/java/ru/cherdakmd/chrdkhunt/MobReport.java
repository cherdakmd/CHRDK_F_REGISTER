package ru.cherdakmd.chrdkhunt;

import org.bukkit.FluidCollisionMode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import ru.cherdakmd.chrdkregister.Messages;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Только личные сведения и настройки: без выдачи предметов, наград и поиска по всему миру. */
final class MobReport {
    private final HuntPlugin plugin;
    MobReport(HuntPlugin plugin) { this.plugin = plugin; }
    boolean command(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { plugin.messages().send(sender, "player-only"); return true; }
        if (!player.hasPermission("chrdkhunt.use")) { plugin.messages().send(player, "no-permission"); return true; }
        if (plugin.coreApi() == null) { plugin.messages().send(player, "mob-api-unavailable"); return true; }
        if (!plugin.coreApi().isAuthenticated(player)) { plugin.messages().send(player, "mob-need-auth"); return true; }
        if (!player.isOnline() || player.isDead()) return true;
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("history")) return history(player, args);
        if (args.length != 1 && !(action.equals("hud") && args.length == 2
                && (args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off")))) {
            plugin.messages().send(player, "mob-help"); return true;
        }
        if (action.equals("hud")) {
            if (args.length == 2) {
                plugin.store().mobHud(player.getUniqueId(), player.getName(), args[1].equalsIgnoreCase("on"));
                plugin.requestSave();
            }
            plugin.messages().send(player, "mob-hud-state", "state", plugin.store().mobHud(player.getUniqueId())
                    ? plugin.messages().mini("mob-on") : plugin.messages().mini("mob-off"));
            return true;
        }
        if (action.equals("observations")) {
            Set<Integer> levels = plugin.store().observedLevels(player.getUniqueId());
            plugin.messages().send(player, "mob-observations", "count", levels.size(), "levels", numbers(levels)); return true;
        }
        if (action.equals("elites")) {
            Set<Integer> elites = plugin.store().observedElites(player.getUniqueId());
            plugin.messages().send(player, "mob-elite-collection", "count", elites.size(), "total", ru.cherdakmd.chrdkregister.api.MobProgressionKeys.MAX_ELITE,
                    "variants", Messages.trusted(elites.isEmpty() ? "—" : elites.stream().sorted()
                            .map(id -> plugin.messages().raw("mob-variant-" + id)).collect(Collectors.joining(", "))));
            return true;
        }
        MobProgression progression = plugin.progression();
        if (progression == null || !progression.available(player)) { plugin.messages().send(player, "mob-progression-disabled"); return true; }
        if (action.equals("danger")) {
            var location = player.getLocation(); var center = player.getWorld().getSpawnLocation();
            double distance = Math.hypot(location.getX() - center.getX(), location.getZ() - center.getZ());
            String world = player.getWorld().getEnvironment().name();
            var range = MobProgressionPolicy.range(world, distance);
            plugin.messages().send(player, "mob-danger", "min", range.min(), "max", range.max(), "distance", (long) distance,
                    "personal", MobProgressionPolicy.level(world, distance, progression.skills(player)));
            if (progression.groupSettings().enabled()) plugin.messages().send(player, "mob-group-mode",
                    "count", progression.groupSettings().limit(), "radius", progression.groupSettings().radius());
            return true;
        }
        if (!action.equals("inspect")) { plugin.messages().send(player, "mob-help"); return true; }
        var eye = player.getEyeLocation();
        var trace = player.getWorld().rayTrace(eye, eye.getDirection(), 16, FluidCollisionMode.NEVER, true, .15,
                entity -> entity instanceof LivingEntity && entity != player);
        LivingEntity mob = trace != null && trace.getHitEntity() instanceof LivingEntity living ? living : null;
        MobProgression.View view = mob == null || mob.isDead() || !mob.isValid() ? null : progression.view(mob);
        if (view == null) { plugin.messages().send(player, "mob-inspect-none"); return true; }
        progression.observe(player, view);
        var health = mob.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        plugin.messages().send(player, "mob-inspect", "level", view.level(), "kind", plugin.messages().mini("mob-variant-" + view.elite()),
                "hp", decimal(mob.getHealth()), "max", health == null ? "?" : decimal(health.getValue()));
        return true;
    }
    private boolean history(Player player, String[] args) {
        var rows = plugin.store().history(player.getUniqueId());
        int page = args.length <= 2 ? HuntHistory.page(args.length == 2 ? args[1] : null, rows.size()) : 0;
        if (page == 0) { plugin.messages().send(player, "mob-history-help", "pages", HuntHistory.pages(rows.size())); return true; }
        if (rows.isEmpty()) { plugin.messages().send(player, "mob-history-empty"); return true; }
        plugin.messages().send(player, "mob-history-header", "page", page, "pages", HuntHistory.pages(rows.size()), "count", rows.size());
        int start = (page - 1) * HuntHistory.PAGE_SIZE;
        for (int i = start; i < Math.min(rows.size(), start + HuntHistory.PAGE_SIZE); i++) {
            var row = rows.get(i);
            plugin.messages().send(player, "mob-history-row", "num", i + 1,
                    "time", HISTORY_TIME.format(java.time.Instant.ofEpochMilli(row.time())),
                    "mob", Messages.trusted("<lang:entity.minecraft." + row.type().toLowerCase(Locale.ROOT) + ">"),
                    "level", row.level(), "kind", plugin.messages().mini("mob-variant-" + row.elite()), "world", row.world());
        }
        return true;
    }
    private static final java.time.format.DateTimeFormatter HISTORY_TIME = java.time.format.DateTimeFormatter
            .ofPattern("dd.MM.uuuu HH:mm", Locale.ROOT).withZone(java.time.ZoneOffset.UTC);
    static String numbers(Set<Integer> values) {
        return values.isEmpty() ? "—" : values.stream().sorted().map(String::valueOf).collect(Collectors.joining(", "));
    }
    private static String decimal(double value) { return Double.isFinite(value) ? String.format(Locale.ROOT, "%.1f", value) : "?"; }
}
