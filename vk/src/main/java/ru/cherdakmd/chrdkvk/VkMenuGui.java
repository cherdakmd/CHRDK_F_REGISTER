package ru.cherdakmd.chrdkvk;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Интерактивное меню {@code /vk} (27 слотов): статус привязки ВКонтакте, выдача кода в один клик,
 * тумблеры личных уведомлений, видимости моста беседы {@code [ВК-чат]} и пересылки {@code @упоминаний},
 * переход в {@code /labels} и двухшаговая защита от случайной отвязки.
 */
public final class VkMenuGui implements Listener {

    private static final int SIZE = 27;
    private static final int SLOT_LINK = 10;
    private static final int SLOT_NOTIFY = 12;
    private static final int SLOT_CHAT = 13;
    private static final int SLOT_BADGE = 14;
    private static final int SLOT_SECURITY = 16;
    private static final int SLOT_CLOSE = 22;
    private static final long UNLINK_ARM_MILLIS = 15_000L;

    private final VkPlugin plugin;
    private final VkService service;
    private final VkCommandHandler commands;
    private final Map<UUID, Long> unlinkArmedUntil = new ConcurrentHashMap<>();

    public VkMenuGui(VkPlugin plugin, VkService service, VkCommandHandler commands) {
        this.plugin = plugin;
        this.service = service;
        this.commands = commands;
    }

    public void open(Player player) {
        Messages messages = plugin.messages();
        Holder holder = new Holder();
        Inventory inv = Bukkit.createInventory(holder, SIZE, messages.get("vk.gui-title"));
        holder.inventory = inv;
        render(player, inv);
        player.openInventory(inv);
    }

    private void render(Player player, Inventory inv) {
        Messages messages = plugin.messages();
        VkConfig cfg = plugin.config();
        ChrdkRegisterApi api = plugin.coreApi();
        VkStore store = service.store();

        ItemStack pane = blankPane(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < SIZE; i++) {
            inv.setItem(i, pane);
        }

        Optional<VkStore.LinkSnapshot> opt = store.byPlayer(player.getName());
        boolean linked = opt.isPresent();
        long now = System.currentTimeMillis();

        // Слот 10 — Статус привязки / Получить код
        if (!linked) {
            inv.setItem(SLOT_LINK, item(
                    Material.TRIPWIRE_HOOK,
                    messages.get("vk.gui-link-unlinked-name"),
                    messages.getList("vk.gui-link-unlinked-lore",
                            "link_hours", Integer.toString(cfg.linkBoostHours()),
                            "member_hours", Integer.toString(cfg.memberBoostHours()))
            ));
        } else {
            VkStore.LinkSnapshot link = opt.get();
            boolean donutActive = store.donutPerkActive(player.getName(), now, cfg.donutGraceHours());
            inv.setItem(SLOT_LINK, item(
                    Material.EMERALD,
                    messages.get("vk.gui-link-linked-name", "vk_id", Long.toString(link.vkUserId())),
                    messages.getList("vk.gui-link-linked-lore",
                            "member", link.member() ? messages.mini("vk.state-yes") : messages.mini("vk.state-no"),
                            "donut", donutActive ? messages.mini("vk.state-yes") : messages.mini("vk.state-no"))
            ));
        }

        // Слот 12 — Уведомления о входах в ЛС ВК
        boolean notifyOn = opt.map(VkStore.LinkSnapshot::notifyLogins).orElse(false);
        inv.setItem(SLOT_NOTIFY, item(
                linked && notifyOn ? Material.BELL : Material.GRAY_DYE,
                messages.get("vk.gui-notify-name"),
                messages.getList("vk.gui-notify-lore",
                        "state", !linked
                                ? messages.mini("vk.state-no")
                                : (notifyOn ? messages.mini("vk.state-on") : messages.mini("vk.state-off")))
        ));

        // Слот 13 — Беседа ВК и анонсы стены (/vk chat и /vk mentions)
        boolean receiveChat = store.receivesGroupChat(player.getName());
        boolean mentionsOn = opt.map(VkStore.LinkSnapshot::allowVkChatMentions).orElse(false);
        inv.setItem(SLOT_CHAT, item(
                receiveChat ? Material.WRITABLE_BOOK : Material.BOOK,
                messages.get("vk.gui-chat-name"),
                messages.getList("vk.gui-chat-lore",
                        "bridge", service.groupChatBridgeReady()
                                ? messages.mini("vk.state-on") : messages.mini("vk.state-off"),
                        "receive", receiveChat ? messages.mini("vk.state-on") : messages.mini("vk.state-off"),
                        "mentions", !linked
                                ? messages.mini("vk.state-no")
                                : (mentionsOn ? messages.mini("vk.state-on") : messages.mini("vk.state-off")))
        ));

        // Слот 14 — Косметический значок у ника (/labels)
        boolean donutActive = linked && store.donutPerkActive(player.getName(), now, cfg.donutGraceHours());
        String badgePreview = donutActive ? cfg.donutTag() : cfg.memberTag();
        inv.setItem(SLOT_BADGE, item(
                Material.NAME_TAG,
                messages.get("vk.gui-badge-name"),
                messages.getList("vk.gui-badge-lore",
                        "badge", Messages.trusted(badgePreview))
        ));

        // Слот 16 — Безопасность и двухшаговая отвязка
        Long armedUntil = unlinkArmedUntil.get(player.getUniqueId());
        boolean armed = linked && armedUntil != null && armedUntil > now;
        if (!armed && armedUntil != null) {
            unlinkArmedUntil.remove(player.getUniqueId());
        }
        if (armed) {
            inv.setItem(SLOT_SECURITY, item(
                    Material.RED_STAINED_GLASS_PANE,
                    messages.get("vk.gui-unlink-confirm-name"),
                    messages.getList("vk.gui-unlink-confirm-lore")
            ));
        } else {
            boolean frozen = api != null && api.isLoginFrozen(player.getName());
            inv.setItem(SLOT_SECURITY, item(
                    Material.SHIELD,
                    messages.get("vk.gui-security-name"),
                    messages.getList("vk.gui-security-lore",
                            "frozen", frozen ? messages.mini("vk.state-frozen") : messages.mini("vk.state-active"))
            ));
        }

        // Слот 22 — Закрыть
        inv.setItem(SLOT_CLOSE, item(
                Material.BARRIER,
                messages.get("vk.gui-close-name"),
                List.of()
        ));
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ClickType click = event.getClick();
        if (click == ClickType.DOUBLE_CLICK || click == ClickType.UNKNOWN) {
            return;
        }
        if (event.getRawSlot() < 0 || event.getRawSlot() >= SIZE) {
            return;
        }
        ChrdkRegisterApi api = plugin.coreApi();
        if (api == null || !api.isAuthenticated(player)) {
            player.closeInventory();
            return;
        }

        int slot = event.getRawSlot();
        switch (slot) {
            case SLOT_LINK -> {
                player.closeInventory();
                if (service.store().byPlayer(player.getName()).isEmpty()) {
                    commands.issueLinkCode(player, plugin.config());
                } else {
                    commands.showStatus(player, api, plugin.config());
                }
            }
            case SLOT_NOTIFY -> {
                commands.toggleNotify(player);
                playClick(player);
                render(player, event.getInventory());
            }
            case SLOT_CHAT -> {
                if (event.isRightClick()) {
                    commands.toggleMentions(player);
                } else {
                    commands.toggleGroupChatVisibility(player);
                }
                playClick(player);
                render(player, event.getInventory());
            }
            case SLOT_BADGE -> {
                player.closeInventory();
                player.performCommand("labels");
            }
            case SLOT_SECURITY -> {
                if (service.store().byPlayer(player.getName()).isEmpty()) {
                    plugin.messages().send(player, "vk.not-linked");
                    return;
                }
                long now = System.currentTimeMillis();
                Long armedUntil = unlinkArmedUntil.get(player.getUniqueId());
                if (armedUntil != null && armedUntil > now) {
                    unlinkArmedUntil.remove(player.getUniqueId());
                    player.closeInventory();
                    commands.performUnlinkConfirmed(player, api);
                } else if (event.isShiftClick()) {
                    unlinkArmedUntil.put(player.getUniqueId(), now + UNLINK_ARM_MILLIS);
                    playClick(player);
                    render(player, event.getInventory());
                } else {
                    plugin.messages().send(player, "vk.gui-security-shift-hint");
                }
            }
            case SLOT_CLOSE -> player.closeInventory();
            default -> {
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    private static void playClick(Player player) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
    }

    private static ItemStack blankPane(Material material) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            meta.addItemFlags(ItemFlag.values());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            if (lore != null && !lore.isEmpty()) {
                meta.lore(lore);
            }
            meta.addItemFlags(ItemFlag.values());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static final class Holder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
