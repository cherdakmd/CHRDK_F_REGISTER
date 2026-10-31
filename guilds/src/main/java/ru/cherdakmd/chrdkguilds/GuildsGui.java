package ru.cherdakmd.chrdkguilds;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Меню гильдии (1.4.163).
 *
 * <p>Один экран: шапка с уровнем и казной, состав, казна и война. Клик по
 * участнику печатает его карточку, клик по уровню покупает следующий — через
 * тот же {@link GuildService}, что и команда, поэтому меню не может обойти
 * проверку прав или казны.</p>
 */
final class GuildsGui implements Listener {

    private static final int SIZE = 54;
    private static final int INFO_SLOT = 4;
    private static final int BACK_SLOT = 45;
    private static final int TREASURY_SLOT = 46;
    private static final int UPGRADE_SLOT = 47;
    private static final int WAR_SLOT = 48;
    private static final int CLOSE_SLOT = 49;
    private static final int MODE_SLOT = 50;
    private static final int CLAIMS_SLOT = 52;
    private static final int PERSONAL_SLOT = 51;
    private static final int[] MEMBER_SLOTS = {10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43};

    /** Владелец меню: чужой игрок не может нажимать кнопки. */
    static final class Holder implements InventoryHolder {
        private final UUID owner;
        private Inventory inventory;

        Holder(UUID owner) {
            this.owner = owner;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final GuildsPlugin plugin;

    GuildsGui(GuildsPlugin plugin) {
        this.plugin = plugin;
    }

    /* ---------------------------------- экран --------------------------------- */

    void open(Player player) {
        if (player == null) {
            return;
        }
        if (!plugin.visible(player)) { player.closeInventory(); return; }
        Guild guild = plugin.service().guildOf(player.getUniqueId());
        Holder holder = new Holder(player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, SIZE,
                plugin.messages().get("menu-title"));
        holder.inventory = inventory;
        inventory.setItem(CLAIMS_SLOT, item(Material.GRASS_BLOCK, "menu-claims-name", List.of(
                plugin.messages().get("menu-claims-lore"))));
        if(guild!=null)inventory.setItem(MODE_SLOT,item(guild.peaceful()?Material.SHIELD:Material.IRON_SWORD,guild.peaceful()?"menu-mode-peaceful":"menu-mode-battle",List.of(
                plugin.messages().get("peace-menu-mode","mode",guild.mode().label()),
                plugin.messages().get("peace-menu-price","rate",GuildPeaceService.DAILY,"total",(long)guild.size()*GuildPeaceService.DAILY),
                plugin.messages().get("peace-menu-hint"))));
        inventory.setItem(PERSONAL_SLOT, item(Material.EMERALD, "menu-personal-name", List.of(
                plugin.messages().get("menu-personal-lore", "level", String.valueOf(plugin.coreApi() == null ? 0
                        : plugin.coreApi().rankLevel(player.getName()))), plugin.messages().get("menu-personal-boundary"))));

        if (guild == null) {
            fillNoGuild(inventory, player);
            player.openInventory(inventory);
            return;
        }

        inventory.setItem(INFO_SLOT, infoItem(guild, player));
        List<UUID> members = guild.memberIds();
        for (int index = 0; index < members.size() && index < MEMBER_SLOTS.length; index++) {
            inventory.setItem(MEMBER_SLOTS[index], memberItem(guild, members.get(index)));
        }
        inventory.setItem(TREASURY_SLOT, treasuryItem(guild));
        inventory.setItem(UPGRADE_SLOT, upgradeItem(guild));
        inventory.setItem(WAR_SLOT, warItem(guild));
        inventory.setItem(BACK_SLOT, button(Material.ARROW, "menu-back"));
        inventory.setItem(CLOSE_SLOT, button(Material.BARRIER, "menu-close"));
        player.openInventory(inventory);
    }

    private void fillNoGuild(Inventory inventory, Player player) {
        List<Component> lore = new ArrayList<>();
        lore.add(plugin.messages().get("menu-no-guild", "cost",
                GuildMath.diamonds(plugin.config().creationCost())));
        lore.add(plugin.messages().get("menu-no-guild-hint"));
        inventory.setItem(INFO_SLOT, item(Material.WHITE_BANNER, "menu-info-no-guild", lore));
        GuildInvite invite = plugin.store().inviteOf(player.getUniqueId());
        if (invite != null && !invite.expired(System.currentTimeMillis())) {
            List<Component> inviteLore = new ArrayList<>();
            inviteLore.add(plugin.messages().get("menu-invite-lore",
                    "name", invite.guildName(),
                    "seconds", String.valueOf(invite.remainingSeconds(
                            System.currentTimeMillis()))));
            inventory.setItem(22, item(Material.PAPER, "menu-invite-name", inviteLore));
        }
        inventory.setItem(BACK_SLOT, button(Material.ARROW, "menu-back"));
        inventory.setItem(CLOSE_SLOT, button(Material.BARRIER, "menu-close"));
    }

    /* --------------------------------- предметы -------------------------------- */

    private ItemStack infoItem(Guild guild, Player player) {
        List<Component> lore = new ArrayList<>();
        lore.add(plugin.messages().get("menu-info-name", "name", guild.name(),
                "tag", guild.tag().isEmpty() ? "—" : guild.tag()));
        lore.add(plugin.messages().get("menu-info-rank",
                "rank", plugin.messages().mini("rank." + rankOf(guild, player.getUniqueId()))));
        lore.add(plugin.messages().get("menu-info-members",
                "count", String.valueOf(guild.size()),
                "cap", String.valueOf(GuildMath.memberCap(guild.level()))));
        lore.add(plugin.messages().get("menu-info-level",
                "level", GuildMath.roman(guild.level())));
        lore.add(plugin.messages().get("menu-info-glory",
                "glory", String.valueOf(guild.glory()),
                "won", String.valueOf(guild.warsWon()),
                "lost", String.valueOf(guild.warsLost())));
        lore.add(plugin.messages().get(guild.peaceful()?"menu-mode-peaceful":"menu-mode-battle"));
        return item(guild.peaceful()?Material.GREEN_BANNER:Material.RED_BANNER, "menu-info-title", lore);
    }

    private ItemStack memberItem(Guild guild, UUID member) {
        String name = Bukkit.getOfflinePlayer(member).getName();
        String display = name == null ? member.toString().substring(0, 8) : name;
        List<Component> lore = new ArrayList<>();
        lore.add(plugin.messages().get("menu-member-rank",
                "rank", plugin.messages().mini("rank." + rankOf(guild, member))));
        lore.add(plugin.messages().get("menu-member-click"));
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = head.getItemMeta();
        if (meta instanceof SkullMeta skull) {
            skull.setOwningPlayer(Bukkit.getOfflinePlayer(member));
            skull.displayName(plugin.messages().get("menu-item-name", "name", display));
            skull.lore(lore);
            head.setItemMeta(skull);
        }
        return head;
    }

    private ItemStack treasuryItem(Guild guild) {
        List<Component> lore = new ArrayList<>();
        lore.add(plugin.messages().get("menu-treasury-balance",
                "amount", GuildMath.diamonds(guild.treasury())));
        lore.add(plugin.messages().get("menu-treasury-hint"));
        return item(Material.DIAMOND_BLOCK, "menu-treasury-name", lore);
    }

    private ItemStack upgradeItem(Guild guild) {
        List<Component> lore = new ArrayList<>();
        lore.add(plugin.messages().get("menu-upgrade-level",
                "level", GuildMath.roman(guild.level()),
                "cap", String.valueOf(GuildMath.memberCap(guild.level()))));
        if (GuildMath.canUpgrade(guild.level())) {
            int cost = GuildMath.upgradeCost(guild.level());
            lore.add(plugin.messages().get("menu-upgrade-cost",
                    "cost", GuildMath.diamonds(cost),
                    "next", GuildMath.roman(guild.level() + 1),
                    "cap", String.valueOf(GuildMath.memberCap(guild.level() + 1))));
            lore.add(plugin.messages().get("menu-upgrade-click"));
        } else {
            lore.add(plugin.messages().get("menu-upgrade-max"));
        }
        return item(Material.ANVIL, "menu-upgrade-name", lore);
    }

    private ItemStack warItem(Guild guild) {
        List<Component> lore = new ArrayList<>();
        GuildWar war = plugin.service().warOf(guild.id());
        if (war == null) {
            lore.add(plugin.messages().get(guild.warCooldownUntil() > System.currentTimeMillis()
                    ? "menu-war-cooldown" : "menu-war-none",
                    "time", GuildMath.clock((guild.warCooldownUntil()
                            - System.currentTimeMillis() + 999) / 1_000L)));
            lore.add(plugin.messages().get("menu-war-hint",
                    "min", GuildMath.diamonds(plugin.config().minWager()),
                    "max", GuildMath.diamonds(plugin.config().maxWager())));
        } else {
            Guild opponent = plugin.store().guild(war.opponentOf(guild.id()));
            Object state = plugin.messages().mini(war.pending() ? "menu-war-pending"
                    : "menu-war-active");
            lore.add(plugin.messages().get("menu-war-opponent",
                    "name", opponent == null ? "?" : opponent.label(), "state", state));
            lore.add(plugin.messages().get("menu-war-score",
                    "score", war.pointsOf(guild.id()) + ":"
                            + war.pointsOf(war.opponentOf(guild.id())),
                    "wager", GuildMath.diamonds(war.wager())));
            if (war.active()) {
                lore.add(plugin.messages().get("menu-war-left",
                        "time", GuildMath.clock(war.secondsLeft(System.currentTimeMillis()))));
            }
        }
        return item(Material.CROSSBOW, "menu-war-name", lore);
    }

    private ItemStack item(Material material, String nameKey, List<Component> lore) {
        ItemStack stack = new ItemStack(material == null ? Material.PAPER : material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(plugin.messages().get(nameKey));
            if (!lore.isEmpty()) {
                meta.lore(lore);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack button(Material material, String nameKey) {
        return item(material, nameKey, List.of());
    }

    private String rankOf(Guild guild, UUID player) {
        GuildRank rank = guild.rankOf(player);
        return rank == null ? GuildRank.MEMBER.id() : rank.id();
    }

    /* --------------------------------- клики ---------------------------------- */

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !holder.owner.equals(player.getUniqueId())
                || event.getRawSlot() >= SIZE) {
            return;
        }
        if (!plugin.authenticated(player) || !player.hasPermission("chrdkguilds.use") || !plugin.config().enabled()) return;
        int slot = event.getRawSlot();
        if (slot == CLAIMS_SLOT || slot == PERSONAL_SLOT) {
            player.closeInventory();
            player.performCommand(slot == CLAIMS_SLOT ? "claim menu" : "rank my");
            return;
        }
        if (slot == CLOSE_SLOT || slot == BACK_SLOT) {
            player.closeInventory();
            return;
        }
        Guild guild = plugin.service().guildOf(player.getUniqueId());
        if (guild == null) {
            return;
        }
        if(slot==MODE_SLOT) {
            if(event.isRightClick() && guild.leader().equals(player.getUniqueId()))
                player.performCommand("clan mode "+(guild.peaceful()?"battle":"peaceful"));
            else player.performCommand("clan mode status");
            return;
        }
        if (slot == UPGRADE_SLOT) {
            GuildResult result = plugin.service().upgrade(player.getUniqueId());
            plugin.messages().send(player, result.key(), result.array());
            if (result.ok()) {
                plugin.requestSave();
                open(player);
            }
            return;
        }
        if (slot == TREASURY_SLOT) {
            plugin.messages().send(player, "treasury-info",
                    "amount", GuildMath.diamonds(guild.treasury()),
                    "cap", GuildMath.diamonds(Guild.MAX_TREASURY));
            if (guild.warReturns() > 0) plugin.messages().send(player,"treasury-war-returns","amount",GuildMath.diamonds(guild.warReturns()));
            return;
        }
        if (slot == WAR_SLOT) {
            describeWar(player, guild);
            return;
        }
        int index = indexOf(MEMBER_SLOTS, slot);
        if (index >= 0) {
            List<UUID> members = guild.memberIds();
            if (index < members.size()) {
                UUID member = members.get(index);
                String name = Bukkit.getOfflinePlayer(member).getName();
                plugin.messages().send(player, "member-info",
                        "name", name == null ? member.toString().substring(0, 8) : name,
                        "rank", plugin.messages().mini("rank." + rankOf(guild, member)),
                        "guild", guild.label());
            }
        }
    }

    private void describeWar(Player player, Guild guild) {
        GuildWar war = plugin.service().warOf(guild.id());
        if (war == null) {
            plugin.messages().send(player, "war-status-none");
            return;
        }
        Guild opponent = plugin.store().guild(war.opponentOf(guild.id()));
        plugin.messages().send(player, "war-status",
                "name", guild.label(),
                "opponent", opponent == null ? "?" : opponent.label(),
                "state", plugin.messages().mini(war.pending() ? "war-state-pending"
                        : "war-state-active"),
                "score", war.pointsOf(guild.id()) + ":"
                        + war.pointsOf(war.opponentOf(guild.id())),
                "wager", GuildMath.diamonds(war.wager()),
                "time", war.active()
                        ? GuildMath.clock(war.secondsLeft(System.currentTimeMillis())) : "—");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    /** Перерисовывает открытые меню после перезагрузки конфига. */
    void refresh() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Holder) {
                open(player);
            }
        }
    }

    private static int indexOf(int[] slots, int slot) {
        for (int index = 0; index < slots.length; index++) {
            if (slots[index] == slot) {
                return index;
            }
        }
        return -1;
    }
}
