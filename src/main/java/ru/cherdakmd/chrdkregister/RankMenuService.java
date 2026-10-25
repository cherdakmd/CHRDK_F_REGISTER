package ru.cherdakmd.chrdkregister;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Витрина донатных рангов {@code /rank} (1.4.167).
 *
 * <p>Пять карточек-иконок в ряд, под ними — «Мой ранг», «Покровители», «Правила»
 * и «Закрыть». Карточка показывает цену за 30 дней и за год, список возможностей
 * ступени и своё состояние: ваш ранг, ниже вашего или доступен к покупке. Клик
 * по доступной карточке открывает ссылку витрины TradeMC — покупка идёт на
 * стороне магазина, ядро только выдаёт ранг по оплате.</p>
 *
 * <p>Меню — чистый слой показа: чисел лимитов оно не знает, их печатают аддоны
 * в своих конфигах. Здесь только цены из каталога и оформление из
 * {@code ranks.levels}, поэтому витрина не расходится с лестницей.</p>
 */
public final class RankMenuService implements Listener {

    /** Размер инвентаря: 5 рядов — карточки, кнопки и воздух. */
    private static final int SIZE = 45;
    /** Слоты пяти карточек: второй ряд, по центру. */
    private static final int[] CARD_SLOTS = {10, 11, 12, 13, 14};
    private static final int SLOT_MY = 29;
    private static final int SLOT_TOP = 31;
    private static final int SLOT_RULES = 33;
    private static final int SLOT_CLOSE = 40;
    /** Иконки ступеней: от компаса до звезды. */
    private static final Material[] ICONS = {
            Material.COMPASS, Material.MAP, Material.TRIAL_KEY, Material.GOLD_INGOT, Material.NETHER_STAR
    };

    private final RegisterPlugin plugin;
    private final AccessManager access;
    private volatile Messages messages;

    public RankMenuService(RegisterPlugin plugin, AccessManager access, Messages messages) {
        this.plugin = plugin;
        this.access = access;
        this.messages = messages;
    }

    /** Обновляет тексты после {@code /pass reload}. */
    public void setMessages(Messages messages) {
        this.messages = messages;
    }

    /** Витрина рангов включена в конфиге ядра. */
    public boolean enabled() {
        return access.config().ranks().enabled();
    }

    /* ------------------------------- открытие ------------------------------- */

    /** Открывает GUI; консоли и выключенной витрине отвечает текстом. */
    public void open(org.bukkit.command.CommandSender sender) {
        if (!enabled()) {
            messages.send(sender, "rank.disabled");
            return;
        }
        if (!(sender instanceof Player player)) {
            sendShop(sender);
            return;
        }
        RankHolder holder = new RankHolder(player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, SIZE, messages.get("rank.menu-title"));
        holder.bind(inventory);
        paint(player, inventory);
        player.openInventory(inventory);
    }

    private void paint(Player player, Inventory inventory) {
        RankSettings ranks = access.config().ranks();
        int current = currentLevel(player);
        ItemStack filler = filler();
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, filler);
        }
        for (int index = 0; index < CARD_SLOTS.length; index++) {
            int level = index + 1;
            RankTier tier = ranks.tier(level);
            if (tier == null) {
                continue;
            }
            inventory.setItem(CARD_SLOTS[index], card(level, tier, current, player));
        }
        inventory.setItem(SLOT_MY, button(Material.PLAYER_HEAD, messages.get("rank.button-my")));
        inventory.setItem(SLOT_TOP, button(Material.WRITABLE_BOOK, messages.get("rank.button-top")));
        inventory.setItem(SLOT_RULES, button(Material.BOOK, messages.get("rank.button-rules")));
        inventory.setItem(SLOT_CLOSE, button(Material.BARRIER, messages.get("rank.button-close")));
    }

    /** Карточка ступени: иконка, цена, возможности и статус игрока. */
    private ItemStack card(int level, RankTier tier, int current, Player player) {
        Material material = ICONS[Math.min(level - 1, ICONS.length - 1)];
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(messages.get("rank.card-name",
                "badge", tier.badge(), "rank", tier.decorate(tier.name())));
        List<Component> lore = new ArrayList<>();
        ShopProduct month = cardProduct(level, false);
        ShopProduct year = cardProduct(level, true);
        if (month != null) {
            lore.add(messages.get("rank.card-price",
                    "month", money(month.priceRubles()),
                    "year", year == null ? "—" : money(year.priceRubles())));
        }
        lore.add(Component.empty());
        lore.addAll(abilityLines(level));
        lore.add(Component.empty());
        lore.add(messages.get(level == current ? "rank.card-current"
                : (level < current ? "rank.card-lower" : "rank.card-buy")));
        if (level == current) {
            lore.add(messages.get("rank.card-current-until", "until", untilText(player)));
        }
        if (level > current) {
            lore.add(messages.get("rank.card-how", "player", player.getName()));
        } else if (level == current) {
            lore.add(messages.get("rank.card-how-renew", "player", player.getName()));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** Строки возможностей ступени: литералы, чтобы контракт текстов видел ключи. */
    private List<Component> abilityLines(int level) {
        return switch (level) {
            case 1 -> messages.getList("rank.ability-1");
            case 2 -> messages.getList("rank.ability-2");
            case 3 -> messages.getList("rank.ability-3");
            case 4 -> messages.getList("rank.ability-4");
            case 5 -> messages.getList("rank.ability-5");
            default -> List.of();
        };
    }

    private ItemStack button(Material material, Component name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack filler() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            item.setItemMeta(meta);
        }
        return item;
    }

    /* ------------------------------- данные ------------------------------- */

    /** Ступень ранга игрока: 0 — без ранга. */
    private int currentLevel(Player player) {
        return access.store().rankLevel(player.getName(), System.currentTimeMillis());
    }

    /** Карточка ступени из каталога: месячная или годовая. */
    private ShopProduct cardProduct(int level, boolean year) {
        ShopProduct best = null;
        for (ShopProduct product : plugin.shop().catalog().products()) {
            if (product.rank() != level) {
                continue;
            }
            boolean isYear = product.days() > 180;
            if (isYear != year) {
                continue;
            }
            if (best == null || product.priceRubles() < best.priceRubles()) {
                best = product;
            }
        }
        return best;
    }

    private String untilText(Player player) {
        long until = access.store().rankUntil(player.getName());
        return until <= 0 ? messages.plain("rank.permanent") : AccessManager.formatTimestamp(until);
    }

    /** Цена с пробелом между разрядами: 3 650, как в витрине. */
    static String money(int rubles) {
        return String.format(Locale.ROOT, "%,d", rubles).replace(',', ' ');
    }

    /* ------------------------------- чат ------------------------------- */

    /** {@code /rank shop} — витрина текстом (консоль, телефоны, логи). */
    public void sendShop(org.bukkit.command.CommandSender sender) {
        if (!enabled()) {
            messages.send(sender, "rank.disabled");
            return;
        }
        RankSettings ranks = access.config().ranks();
        messages.send(sender, "rank.shop-header");
        for (RankTier tier : ranks.tiers()) {
            ShopProduct month = cardProduct(tier.level(), false);
            ShopProduct year = cardProduct(tier.level(), true);
            messages.send(sender, "rank.shop-entry",
                    "badge", tier.badge(),
                    "rank", tier.decorate(tier.name()),
                    "month", month == null ? "—" : money(month.priceRubles()),
                    "year", year == null ? "—" : money(year.priceRubles()));
        }
        if (access.config().hasEffectiveDonateUrl()) {
            messages.send(sender, "rank.shop-link",
                    "url", access.config().effectiveDonateUrl(),
                    "label", access.config().effectiveDonateLabel());
        } else {
            messages.send(sender, "rank.no-link");
        }
    }

    /** {@code /rank my} — мой ранг и срок. */
    public void sendMy(org.bukkit.command.CommandSender sender, String playerName) {
        if (!enabled()) {
            messages.send(sender, "rank.disabled");
            return;
        }
        long now = System.currentTimeMillis();
        int level = access.store().rankLevel(playerName, now);
        if (level <= 0) {
            messages.send(sender, "rank.my-none", "player", playerName,
                    "url", access.config().effectiveDonateUrl());
            return;
        }
        RankSettings ranks = access.config().ranks();
        long until = access.store().rankUntil(playerName);
        messages.send(sender, "rank.my-line",
                "player", playerName,
                "badge", ranks.badge(level),
                "rank", ranks.decorate(level, ranks.name(level)),
                "until", until <= 0 ? messages.raw("rank.permanent") : AccessManager.formatTimestamp(until),
                "days", until <= 0 ? "∞" : String.valueOf(PlaceholderValues.wholeDaysLeft(until, now)));
    }

    /** {@code /rank top} — действующие Меценаты и Покровители (IV–V). */
    public void sendTop(org.bukkit.command.CommandSender sender) {
        if (!enabled()) {
            messages.send(sender, "rank.disabled");
            return;
        }
        long now = System.currentTimeMillis();
        RankSettings ranks = access.config().ranks();
        List<RegistrationStore.PlayerAccount> holders = new ArrayList<>();
        for (RegistrationStore.PlayerAccount account : access.store().getAllAccounts()) {
            if (account == null || account.playerName == null) {
                continue;
            }
            if (account.effectiveRank(now) >= 4) {
                holders.add(account);
            }
        }
        holders.sort(Comparator
                .comparingInt((RegistrationStore.PlayerAccount account) -> account.effectiveRank(now))
                .reversed()
                .thenComparing(account -> account.playerName, String.CASE_INSENSITIVE_ORDER));
        if (holders.isEmpty()) {
            messages.send(sender, "rank.top-empty");
            return;
        }
        messages.send(sender, "rank.top-header", "count", holders.size());
        for (RegistrationStore.PlayerAccount account : holders) {
            int level = account.effectiveRank(now);
            long until = access.store().rankUntil(account.playerName);
            messages.send(sender, "rank.top-entry",
                    "badge", ranks.badge(level),
                    "rank", ranks.decorate(level, ranks.name(level)),
                    "player", account.playerName,
                    "until", account.rankPermanent || until <= 0
                            ? messages.raw("rank.permanent") : AccessManager.formatTimestamp(until));
        }
    }

    /** {@code /rank rules} — короткий текст «что мы не продаём». */
    public void sendRules(org.bukkit.command.CommandSender sender) {
        messages.sendList(sender, "rank.rules");
    }

    /* ------------------------------- клики ------------------------------- */

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof RankHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !holder.owner().equals(player.getUniqueId())) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot == SLOT_CLOSE) {
            player.closeInventory();
            return;
        }
        if (slot == SLOT_MY) {
            player.closeInventory();
            sendMy(player, player.getName());
            return;
        }
        if (slot == SLOT_TOP) {
            player.closeInventory();
            sendTop(player);
            return;
        }
        if (slot == SLOT_RULES) {
            player.closeInventory();
            sendRules(player);
            return;
        }
        int level = levelAt(slot);
        if (level <= 0) {
            return;
        }
        if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) {
            return;
        }
        player.closeInventory();
        buy(player, level);
    }

    /** Подсказка о покупке: ступень, цена и ссылка витрины. */
    private void buy(Player player, int level) {
        int current = currentLevel(player);
        RankSettings ranks = access.config().ranks();
        if (level <= current) {
            messages.send(player, level == current ? "rank.already-current" : "rank.already-higher",
                    "rank", ranks.decorate(level, ranks.name(level)));
            return;
        }
        ShopProduct month = cardProduct(level, false);
        if (month == null) {
            messages.send(player, "rank.no-link");
            return;
        }
        if (!access.config().hasEffectiveDonateUrl()) {
            messages.send(player, "rank.no-link");
            return;
        }
        messages.send(player, "rank.buy-link",
                "rank", ranks.decorate(level, ranks.name(level)),
                "price", money(month.priceRubles()),
                "url", access.config().effectiveDonateUrl(),
                "label", access.config().effectiveDonateLabel());
    }

    private static int levelAt(int slot) {
        for (int index = 0; index < CARD_SLOTS.length; index++) {
            if (CARD_SLOTS[index] == slot) {
                return index + 1;
            }
        }
        return 0;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof RankHolder) {
            event.setCancelled(true);
        }
    }

    /** Закрывает витрину при выключении плагина. */
    public void shutdown() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof RankHolder) {
                player.closeInventory();
            }
        }
    }

    /** Маркерный холдер: отличает нашу витрину от чужих инвентарей. */
    private static final class RankHolder implements InventoryHolder {
        private final UUID owner;
        private Inventory inventory;

        private RankHolder(UUID owner) {
            this.owner = owner;
        }

        UUID owner() {
            return owner;
        }

        void bind(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
