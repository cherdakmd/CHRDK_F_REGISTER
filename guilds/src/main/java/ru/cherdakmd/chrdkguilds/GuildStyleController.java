package ru.cherdakmd.chrdkguilds;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import ru.cherdakmd.chrdkregister.Messages;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Подтверждение главы, личная лицензия и сериализованные асинхронные настройки. */
final class GuildStyleController implements AutoCloseable {
    private record Offer(UUID owner, String name, String guild, String kind, String preset, long expires) { }
    private final GuildsPlugin plugin;
    private final GuildStyleStore store;
    private final Map<UUID, Offer> offers = new HashMap<>();
    private final Set<UUID> busy = new HashSet<>();
    private final ExecutorService writer = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32), runnable -> {
                Thread thread = new Thread(runnable, "CHRDK-Clan-Styles"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    GuildStyleController(GuildsPlugin plugin) {
        this.plugin = plugin; this.store = new GuildStyleStore(plugin.getDataFolder().toPath(), plugin.getLogger());
    }
    void command(Player actor, String[] args) {
        if (!plugin.visible(actor)) return;
        if (!store.healthy() || plugin.payments().paused()) { send(actor, "style-unavailable"); return; }
        offers.values().removeIf(offer -> offer.expires() <= System.currentTimeMillis());
        if (args.length == 1) { status(actor); return; }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("off")) {
            if (args.length != 3 || !GuildStyleDefs.PRODUCTS.containsKey(args[2])) { send(actor, "style-help"); return; }
            submit(actor, actor.getUniqueId(), () -> store.remove(actor.getUniqueId(), args[2])
                    ? GuildStyleStore.Result.OK : GuildStyleStore.Result.FAILED);
            return;
        }
        Guild guild = plugin.service().guildOf(actor.getUniqueId());
        if (guild == null) { send(actor, "not-in-guild"); return; }
        if (action.equals("accept")) {
            if (!guild.leader().equals(actor.getUniqueId())) { send(actor, "not-leader"); return; }
            if (args.length != 3) { send(actor, "style-help"); return; }
            Player owner = Bukkit.getPlayerExact(args[2]);
            Offer offer = owner == null ? null : offers.get(owner.getUniqueId());
            if (offer == null || !offer.guild().equals(guild.id()) || !guild.isMember(owner.getUniqueId())
                    || !plugin.visible(owner) || offer.expires() <= System.currentTimeMillis()) { send(actor, "style-no-offer"); return; }
            if (!licensed(owner, offer.kind())) { send(actor, "style-license-missing"); return; }
            offers.remove(owner.getUniqueId());
            apply(actor, offer); return;
        }
        if (action.equals("clear")) {
            if (args.length != 3 || !GuildStyleDefs.PRODUCTS.containsKey(args[2])) { send(actor, "style-help"); return; }
            if (!guild.leader().equals(actor.getUniqueId())) { send(actor, "not-leader"); return; }
            String kind = args[2];
            submit(actor, actor.getUniqueId(), () -> store.clear(guild.id(), kind)
                    ? GuildStyleStore.Result.OK : GuildStyleStore.Result.FAILED);
            return;
        }
        if (args.length != 3 || !GuildStyleDefs.valid(action, args[2])) { status(actor); return; }
        if (!licensed(actor, action)) { send(actor, "style-license-missing"); return; }
        Offer offer = new Offer(actor.getUniqueId(), actor.getName(), guild.id(), action, args[2], System.currentTimeMillis() + 120_000L);
        if (guild.leader().equals(actor.getUniqueId())) { apply(actor, offer); return; }
        if (offers.size() >= 256 && !offers.containsKey(actor.getUniqueId())) { send(actor, "style-unavailable"); return; }
        offers.put(actor.getUniqueId(), offer);
        send(actor, "style-offered");
        Player leader = Bukkit.getPlayer(guild.leader());
        if (plugin.visible(leader)) plugin.messages().send(leader, "style-approval", "player", actor.getName(), "kind", action, "preset", args[2]);
    }
    private boolean licensed(Player owner, String kind) {
        return plugin.coreApi() != null && owner.getUniqueId().equals(plugin.coreApi().knownBankPlayer(owner.getName()))
                && plugin.coreApi().hasEntitlement(owner.getName(), GuildStyleDefs.PRODUCTS.get(kind));
    }
    private void apply(Player actor, Offer offer) {
        submit(actor, offer.owner(), () -> store.select(offer.owner(), offer.name(), offer.guild(), offer.kind(), offer.preset(), System.currentTimeMillis()));
    }
    private void submit(Player actor, UUID owner, Supplier<GuildStyleStore.Result> mutation) {
        if (!busy.add(owner)) { send(actor, "style-saving"); return; }
        try {
            writer.execute(() -> {
                GuildStyleStore.Result result;
                try { result = mutation.get(); } catch (RuntimeException error) { result = GuildStyleStore.Result.FAILED; }
                GuildStyleStore.Result finalResult = result;
                if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, () -> {
                    busy.remove(owner);
                    if (actor.isOnline()) send(actor, finalResult == GuildStyleStore.Result.OK ? "style-saved"
                            : finalResult == GuildStyleStore.Result.COOLDOWN ? "style-transfer-cooldown" : "style-save-failed");
                    if (finalResult == GuildStyleStore.Result.OK) plugin.gui().refresh();
                });
            });
            send(actor, "style-saving");
        } catch (RejectedExecutionException full) { busy.remove(owner); send(actor, "style-unavailable"); }
    }
    private void status(Player actor) {
        send(actor, "style-help"); send(actor, "style-rules");
        for (String kind : List.of("name", "emblem", "frame")) {
            long until = plugin.coreApi().entitlementUntil(actor.getName(), GuildStyleDefs.PRODUCTS.get(kind));
            plugin.messages().send(actor, "style-license", "kind", plugin.messages().plain("style-kind-" + kind),
                    "until", until > System.currentTimeMillis() ? GuildPeaceController.time(until) : plugin.messages().plain("style-not-owned"));
            plugin.messages().send(actor, "style-options", "kind", kind, "options", String.join(", ", GuildStyleDefs.options(kind)));
        }
        GuildStyleStore.Row row = store.of(actor.getUniqueId());
        if (row != null) {
            Guild guild = plugin.store().guild(row.guild());
            plugin.messages().send(actor, "style-binding", "clan", guild == null ? plugin.messages().plain("style-disbanded") : guild.name(),
                    "move", GuildPeaceController.time(row.movedAt() + GuildStyleStore.TRANSFER_COOLDOWN));
        }
    }
    String active(Guild guild, String kind) {
        if (guild == null || !store.healthy() || !plugin.config().enabled() || plugin.coreApi() == null || plugin.payments().paused()) return null;
        return GuildStylePolicy.active(store.forGuild(guild.id()), guild, kind, System.currentTimeMillis(),
                row -> plugin.coreApi().entitlementUntil(row.name(), GuildStyleDefs.PRODUCTS.get(kind)),
                row -> row.owner().equals(plugin.coreApi().knownBankPlayer(row.name())));
    }
    String name(Guild guild) { return GuildStyleDefs.name(guild.name(), active(guild, "name"), active(guild, "emblem")); }
    String tag(Guild guild) { return GuildStyleDefs.name(guild.tag(), active(guild, "name"), active(guild, "emblem")); }
    void frame(Inventory inventory, Guild guild) {
        String preset = active(guild, "frame"); if (preset == null) return;
        Material material = Material.matchMaterial(GuildStyleDefs.FRAMES.get(preset)); if (material == null) return;
        ItemStack filler = new ItemStack(material); var meta = filler.getItemMeta();
        meta.displayName(plugin.messages().get("style-frame-item")); filler.setItemMeta(meta);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if ((slot < 9 || slot >= inventory.getSize() - 9 || slot % 9 == 0 || slot % 9 == 8) && inventory.getItem(slot) == null)
                inventory.setItem(slot, filler.clone());
        }
    }
    private void send(Player actor, String key) { plugin.messages().send(actor, key); }
    @Override public void close() {
        offers.clear(); writer.shutdown();
        try { if (!writer.awaitTermination(5, TimeUnit.SECONDS)) writer.shutdownNow(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); writer.shutdownNow(); }
    }
}
