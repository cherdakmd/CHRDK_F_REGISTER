package ru.cherdakmd.chrdkguilds;

import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/** Предпросмотр доступен участникам; менять оформление может только текущий глава. */
final class GuildStyleGui implements Listener {
    static final List<String> KINDS = List.of("name", "emblem", "frame", "animation");
    static final int[] OPTIONS = {19,20,21,22,23,24,25,28,29,30,31,32,33,34};
    static final class Holder implements InventoryHolder {
        final UUID owner;
        final String guild;
        final String kind;
        final int page;
        final Map<Integer, String[]> actions = new HashMap<>();
        Inventory inventory;
        Holder(UUID owner, String guild, String kind, int page) {
            this.owner = owner; this.guild = guild; this.kind = kind; this.page = Math.max(0, page);
        }
        @Override public Inventory getInventory() { return inventory; }
    }
    private final GuildsPlugin plugin;
    GuildStyleGui(GuildsPlugin plugin) { this.plugin = plugin; }

    void open(Player player, String kind, int page) {
        if (!allowed(player)) { player.closeInventory(); return; }
        Guild guild = plugin.service().guildOf(player.getUniqueId());
        if (guild == null) { plugin.messages().send(player, "not-in-guild"); player.closeInventory(); return; }
        if (!KINDS.contains(kind)) return;
        Holder holder = new Holder(player.getUniqueId(), guild.id(), kind, page);
        holder.inventory = Bukkit.createInventory(holder, 54, plugin.messages().get("style-gui-title"));
        fill(player, guild, holder);
        player.openInventory(holder.inventory);
    }
    private boolean allowed(Player player) {
        return player != null && plugin.visible(player) && player.hasPermission("chrdkguilds.use") && plugin.config().enabled();
    }
    private void fill(Player player, Guild guild, Holder h) {
        Inventory inv = h.inventory;
        inv.clear(); h.actions.clear();
        boolean manager = GuildStylePolicy.canManage(guild, player.getUniqueId());
        GuildStyleStore.Row row = plugin.styles().selection(player.getUniqueId());
        List<Component> info = new ArrayList<>();
        info.add(plugin.messages().get("style-gui-boundary"));
        info.add(plugin.messages().get("style-gui-chat"));
        info.add(plugin.messages().get("style-gui-animation"));
        if (row != null) {
            Guild bound = plugin.store().guild(row.guild());
            info.add(plugin.messages().get("style-binding", "clan",
                    bound == null ? plugin.messages().plain("style-disbanded") : bound.name()));
        }
        if (!manager) info.add(plugin.messages().get("style-leader-only"));
        String animation = plugin.styles().active(guild, "animation");
        info.add(plugin.messages().get("style-animation-status", "effect", animation == null
                ? plugin.messages().plain("style-animation-inactive")
                : label("animation", animation)));
        inv.setItem(4, item(Material.WHITE_BANNER, plugin.messages().markup(plugin.styles().name(guild)), info));
        for (int index = 0; index < KINDS.size(); index++) {
            String kind = KINDS.get(index);
            long until = plugin.coreApi().entitlementUntil(player.getName(), GuildStyleDefs.PRODUCTS.get(kind));
            List<Component> lore = List.of(plugin.messages().get("style-license", "kind", plugin.messages().plain("style-kind-" + kind),
                    "until", until > System.currentTimeMillis() ? GuildPeaceController.time(until) : plugin.messages().plain("style-not-owned")),
                    plugin.messages().get("style-gui-category"));
            inv.setItem(10 + index, item(icon(kind), plugin.messages().get("style-kind-" + kind), lore));
        }
        int index = 0;
        for (String preset : GuildStyleDefs.options(h.kind)) {
            String current = plugin.styles().active(guild, h.kind);
            String personal = row != null && row.guild().equals(guild.id()) && row.choices().containsKey(h.kind) ? row.choices().get(h.kind).preset() : "";
            List<Component> lore = new ArrayList<>();
            lore.add(plugin.messages().get("style-gui-choice", "choice", label(h.kind, preset)));
            lore.add(plugin.messages().get("style-gui-preset", "preset", preset));
            if (h.kind.equals("animation")) lore.add(plugin.messages().get("style-effect-" + preset));
            lore.add(plugin.messages().get("style-gui-selected", "personal", preset.equals(personal) ? "✓" : "—", "active", preset.equals(current) ? "✓" : "—"));
            lore.add(plugin.messages().get(manager ? "style-gui-apply" : "style-leader-only"));
            if (manager && row != null && !row.guild().equals(guild.id())) lore.add(plugin.messages().get("style-gui-transfer-warning"));
            Component preview = preview(guild, h.kind, preset);
            inv.setItem(OPTIONS[index], item(material(h.kind, preset), preview, lore));
            if (manager) h.actions.put(OPTIONS[index], new String[]{"style", h.kind, preset});
            index++;
        }
        if (manager) inv.setItem(46, button(Material.BARRIER, "style-gui-off"));
        inv.setItem(45, button(Material.ARROW, "style-gui-back"));
        inv.setItem(49, button(Material.OAK_DOOR, "style-gui-close"));
        plugin.styles().frame(inv, guild);
    }
    private String label(String kind, String preset) { return plugin.messages().plain("style-preset-" + kind + "-" + preset); }
    private Component preview(Guild guild, String kind, String preset) {
        if (kind.equals("frame")) return plugin.messages().get("style-preset-frame-" + preset);
        String name = kind.equals("name") ? preset : plugin.styles().active(guild, "name");
        String emblem = kind.equals("emblem") ? preset : plugin.styles().active(guild, "emblem");
        String animation = kind.equals("animation") ? preset : null;
        return plugin.messages().markup(GuildStyleAnimation.render(guild.name(), name, emblem, animation, System.currentTimeMillis()));
    }
    private Material icon(String kind) {
        return switch (kind) { case "name" -> Material.NAME_TAG; case "emblem" -> Material.NETHER_STAR;
            case "frame" -> Material.PAINTING; case "animation" -> Material.AMETHYST_SHARD; default -> Material.PAPER; };
    }
    private Material material(String kind, String preset) {
        return kind.equals("frame") ? Material.valueOf(GuildStyleDefs.FRAMES.get(preset))
                : kind.equals("animation") ? animationIcon(preset) : icon(kind);
    }
    static Material animationIcon(String preset) {
        return switch (preset) {
            case "aurora" -> Material.AMETHYST_SHARD;
            case "shine" -> Material.GLOWSTONE_DUST;
            case "pulse" -> Material.HEART_OF_THE_SEA;
            case "prism" -> Material.PRISMARINE_CRYSTALS;
            case "flame" -> Material.BLAZE_POWDER;
            default -> Material.PAPER;
        };
    }
    private ItemStack button(Material type, String key) { return item(type, plugin.messages().get(key), List.of()); }
    private ItemStack item(Material type, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(type); var meta = item.getItemMeta();
        meta.displayName(name); meta.lore(lore); item.setItemMeta(meta); return item;
    }
    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !h.owner.equals(player.getUniqueId())) return;
        Guild guild = plugin.service().guildOf(player.getUniqueId());
        if (!allowed(player) || guild == null || !guild.id().equals(h.guild)) { player.closeInventory(); return; }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 54 || event.getClick() != org.bukkit.event.inventory.ClickType.LEFT && event.getClick() != org.bukkit.event.inventory.ClickType.RIGHT) return;
        String[] action = h.actions.get(slot);
        // Переход выполняется после события; номера слотов не превращаются в команды из lore/PDC.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!allowed(player) || player.getOpenInventory().getTopInventory().getHolder() != h) return;
            Guild current = plugin.service().guildOf(player.getUniqueId());
            if (current == null || !h.guild.equals(current.id())) { player.closeInventory(); return; }
            if (slot >= 10 && slot < 14) { open(player, KINDS.get(slot - 10), 0); return; }
            if (slot == 45) { plugin.gui().open(player); return; }
            if (slot == 49) { player.closeInventory(); return; }
            if (!GuildStylePolicy.canManage(current, player.getUniqueId())) {
                plugin.messages().send(player, "style-leader-only"); return;
            }
            if (slot == 46) {
                plugin.styles().command(player, new String[]{"style", "off", h.kind}); return;
            }
            if (action != null) plugin.styles().command(player, action);
        });
    }
    @EventHandler public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }
    void refresh() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof Holder h)) continue;
            Guild guild = plugin.service().guildOf(player.getUniqueId());
            if (!allowed(player) || guild == null || !guild.id().equals(h.guild)) { player.closeInventory(); continue; }
            fill(player, guild, h);
        }
    }
}
