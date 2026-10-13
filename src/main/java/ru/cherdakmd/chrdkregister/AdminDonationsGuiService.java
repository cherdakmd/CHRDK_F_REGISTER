package ru.cherdakmd.chrdkregister;

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
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Администраторское GUI-меню журнала донатов ({@code /pass donationsgui} или
 * кнопка «Журнал» в {@code /pass donate}): полный список донатов инвентарём
 * с фильтрами и страницами. Клик по записи в статусе «ждёт решения»:
 * ЛКМ — выдать проход (делегирует {@code /pass approve <id>}), ПКМ — отклонить
 * ({@code /pass reject <id>}), после действия меню обновляется само.
 *
 * <p>Верхний ряд — пульс управления: предыдущая страница, фильтр
 * (все → ждут → выданные → отклонённые → все), обновить, следующая страница.</p>
 */
public final class AdminDonationsGuiService implements Listener {

    /** Размер инвентаря (6 рядов); верхний ряд — управление, записи со слота 9. */
    public static final int INVENTORY_SIZE = 54;
    /** Записей на страницу (5 рядов по 9). */
    public static final int PAGE_SIZE = INVENTORY_SIZE - 9;

    /** Фильтры журнала: цикл ALL → PENDING → GRANTED → REJECTED → ALL. */
    public enum Filter {
        ALL,
        PENDING,
        GRANTED,
        REJECTED;

        public Filter next() {
            return values()[(ordinal() + 1) % values().length];
        }

        private boolean matches(RegistrationStore.DonationStatus status) {
            return switch (this) {
                case ALL -> true;
                case PENDING -> status == RegistrationStore.DonationStatus.PENDING_NAME
                        || status == RegistrationStore.DonationStatus.PENDING_AMOUNT
                        || status == RegistrationStore.DonationStatus.PENDING_REVIEW;
                case GRANTED -> status == RegistrationStore.DonationStatus.GRANTED;
                case REJECTED -> status == RegistrationStore.DonationStatus.REJECTED;
            };
        }
    }

    /** Страница записей для отображения. */
    private record Page(List<RegistrationStore.DonationRecord> records, Filter filter,
                        int page, int pages) {
    }

    /** Сколько живёт кэш кошелька DonatePay (не дёргаем /user на каждое открытие меню). */
    private static final long WALLET_CACHE_MS = 60_000L;

    /** Кэш кошелька DonatePay для иконки статуса. */
    private record CachedWallet(String name, long balance, String error, long fetchedAtMillis) {
    }

    private final AccessManager accessManager;
    private final org.bukkit.plugin.Plugin plugin;
    private volatile Messages messages;
    private volatile CachedWallet walletCache;
    /** Запрос баланса уже летит к API (возможно, ждёт слот 20-секундного окна) — не плодим дубли. */
    private volatile boolean walletRefreshInFlight;

    public AdminDonationsGuiService(AccessManager accessManager, Messages messages,
                                    org.bukkit.plugin.Plugin plugin) {
        this.accessManager = accessManager;
        this.messages = messages;
        this.plugin = plugin;
    }

    /** Обновляет тексты после {@code /pass reload}. */
    public void setMessages(Messages messages) {
        this.messages = messages;
    }

    /** Количество страниц для {@code total} записей (минимум 1). Отделено для тестов. */
    static int pageCount(int total) {
        return Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    /** Срез всех записей журнала под фильтр и страницу. */
    private Page page(Filter filter, int page) {
        List<RegistrationStore.DonationRecord> filtered = new ArrayList<>();
        for (RegistrationStore.DonationRecord record : accessManager.store().listDonations(null, 0)) {
            if (filter.matches(RegistrationStore.DonationStatus.parse(record.status))) {
                filtered.add(record);
            }
        }
        int pages = pageCount(filtered.size());
        int clampedPage = Math.clamp(page, 0, pages - 1);
        int from = Math.min(clampedPage * PAGE_SIZE, filtered.size());
        int to = Math.min(from + PAGE_SIZE, filtered.size());
        return new Page(filtered.subList(from, to), filter, clampedPage, pages);
    }

    /** Открывает журнал администратору на странице 0 с фильтром ALL. */
    public void openMenu(Player player) {
        openMenu(player, Filter.ALL, 0);
    }

    private void openMenu(Player player, Filter filter, int page) {
        if (!player.hasPermission("chrdkregister.admin")) {
            messages.send(player, "error.no-permission");
            return;
        }
        Page data = page(filter, page);
        DonationsGuiHolder holder = new DonationsGuiHolder(data.filter(), data.page());
        Inventory inventory = Bukkit.createInventory(holder, INVENTORY_SIZE,
                messages.get("pass.donationsgui.title",
                        "page", data.page() + 1, "pages", data.pages()));
        holder.bind(inventory);

        // управление
        inventory.setItem(0, control(Material.ARROW,
                messages.get("pass.donationsgui.prev-name"),
                List.of(messages.get("pass.donationsgui.prev-lore",
                        "page", data.page() + 1, "pages", data.pages()))));
        inventory.setItem(1, control(Material.HOPPER,
                messages.get("pass.donationsgui.filter-name",
                        "filter", filterLabel(data.filter())),
                List.of(messages.get("pass.donationsgui.filter-lore"))));
        inventory.setItem(2, control(Material.REDSTONE,
                messages.get("pass.donationsgui.refresh-name"),
                List.of(messages.get("pass.donationsgui.refresh-lore"))));
        inventory.setItem(4, statusIcon());
        inventory.setItem(8, control(Material.ARROW,
                messages.get("pass.donationsgui.next-name"),
                List.of(messages.get("pass.donationsgui.next-lore",
                        "page", data.page() + 1, "pages", data.pages()))));

        if (!walletFresh() && accessManager.config().donatePayReady()) {
            refreshWallet(); // просроченный кэш — подтянем баланс в фоне
        }

        // записи
        if (data.records().isEmpty()) {
            inventory.setItem(31, control(Material.PAPER,
                    messages.get("pass.donationsgui.empty-name"),
                    List.of(messages.get("pass.donationsgui.empty-lore"))));
        }
        for (int i = 0; i < data.records().size(); i++) {
            inventory.setItem(9 + i, donationIcon(data.records().get(i)));
        }
        player.openInventory(inventory);
    }

    /**
     * Иконка «статус подключения и баланс»: шерсть по худшему здоровью провайдеров,
     * в лоре — карточки опроса (сервис/состояние/курсор/латентность) и баланс
     * DonatePay из кэша (обновляется раз в {@link #WALLET_CACHE_MS} мс или по клику).
     */
    private String statusDetailsState(AccessManager.DonateHealthCard card) {
        String key = switch (card.state()) {
            case OK -> "donation.health-ok";
            case FLAKY -> "donation.health-flaky";
            case NO_CONTACT -> "donation.health-down";
        };
        return card.state() == DonatePulse.State.OK
                ? Messages.stripFormatting(messages.raw(key))
                : Messages.stripFormatting(messages.raw(key, "fails", card.failStreak()));
    }

    private ItemStack statusIcon() {
        List<AccessManager.DonateHealthCard> cards = accessManager.donateHealthCards();
        boolean anyReady = false;
        boolean flaky = false;
        boolean down = false;
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        for (AccessManager.DonateHealthCard card : cards) {
            if (!card.ready()) {
                lore.add(messages.get("pass.donationsgui.status-off", "service", card.serviceName()));
                continue;
            }
            anyReady = true;
            flaky |= card.state() == DonatePulse.State.FLAKY;
            down |= card.state() == DonatePulse.State.NO_CONTACT;
            lore.add(messages.get("pass.donationsgui.status-line",
                    "service", card.serviceName(),
                    "state", statusDetailsState(card),
                    "cursor", card.cursorId(),
                    "latency", card.avgLatencyMs()));
            if (card.apiWaitMillis() > 0) {
                // окно API DonatePay занято (раз в 20 с) — видно, почему ответ не мгновенный
                lore.add(messages.get("pass.donationsgui.status-api-window",
                        "seconds", AccessManager.waitSeconds(card.apiWaitMillis())));
            }
            if (card.lastError() != null && card.state() != DonatePulse.State.OK) {
                lore.add(messages.get("pass.donationsgui.status-error-line",
                        "error", shorten(card.lastError(), 60)));
            }
        }

        // --- баланс кошелька DonatePay (кэш ≤60 с) ---
        if (accessManager.config().donatePayReady() && anyDonatePayReady(cards)) {
            if (!accessManager.config().donatePayShowBalance()) {
                lore.add(messages.get("pass.donationsgui.status-balance-hidden"));
            } else {
                CachedWallet wallet = walletCache;
                if (wallet == null) {
                    lore.add(messages.get("pass.donationsgui.status-balance-loading"));
                    refreshWallet(); // обновит кэш и перерисует открытые меню
                } else if (wallet.error() != null) {
                    lore.add(messages.get("pass.donationsgui.status-balance-error",
                            "error", wallet.error()));
                } else {
                    lore.add(messages.get("pass.donationsgui.status-balance",
                            "wallet", wallet.name(), "balance", wallet.balance()));
                    lore.add(messages.get("pass.donationsgui.status-balance-age",
                            "ago", AccessManager.formatDuration(
                                    System.currentTimeMillis() - wallet.fetchedAtMillis())));
                }
            }
        }
        lore.add(messages.get("pass.donationsgui.status-click"));
        lore.add(messages.get("pass.donationsgui.status-click-recheck"));

        Material wool = !anyReady || down ? Material.RED_WOOL
                : flaky ? Material.YELLOW_WOOL : Material.LIME_WOOL;
        return control(wool, messages.get("pass.donationsgui.status-name"), lore);
    }

    private static boolean anyDonatePayReady(List<AccessManager.DonateHealthCard> cards) {
        return cards.stream().anyMatch(card -> card.ready()
                && RegistrationStore.DONATION_SOURCE_DONATEPAY.equals(card.serviceKey()));
    }

    /** Асинхронно обновляет кэш кошелька и перерисовывает у всех, у кого наш GUI открыт. */
    private void refreshWallet() {
        if (walletRefreshInFlight) {
            return; // один запрос уже летит (возможно, ждёт слот окна API) — дубли не нужны
        }
        walletRefreshInFlight = true;
        accessManager.fetchDonatePayWallet(
                user -> {
                    walletRefreshInFlight = false;
                    walletCache = new CachedWallet(user.name(), user.balanceRubles(), null,
                            System.currentTimeMillis());
                    reopenOpenMenus();
                },
                error -> {
                    walletRefreshInFlight = false;
                    walletCache = new CachedWallet("", 0, error, System.currentTimeMillis());
                    reopenOpenMenus();
                });
    }

    /** Обрезка длинной ошибки API для лора (без переносов, с «…»). */
    static String shorten(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String flat = text.replace('\n', ' ').replace('\r', ' ').trim();
        return flat.length() <= maxLength ? flat : flat.substring(0, maxLength - 1) + "…";
    }

    /** Кэш свежий? (просроченные кошельки перечитываем при следующем открытии) */
    private boolean walletFresh() {
        CachedWallet wallet = walletCache;
        return wallet != null && System.currentTimeMillis() - wallet.fetchedAtMillis() < WALLET_CACHE_MS;
    }

    /** Переоткрыть меню тем, кто прямо сейчас смотрит GUI журнала. */
    private void reopenOpenMenus() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getOpenInventory().getTopInventory().getHolder() instanceof DonationsGuiHolder holder) {
                openMenu(viewer, holder.filter(), holder.page());
            }
        }
    }

    private String filterLabel(Filter filter) {
        String key = switch (filter) {
            case ALL -> "donation.filter-all";
            case PENDING -> "donation.filter-pending";
            case GRANTED -> "donation.filter-granted";
            case REJECTED -> "donation.filter-rejected";
        };
        String label = Messages.stripFormatting(messages.raw(key)).trim();
        return label.isEmpty() ? "все" : label;
    }

    private ItemStack control(Material material, net.kyori.adventure.text.Component name,
                              List<net.kyori.adventure.text.Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack donationIcon(RegistrationStore.DonationRecord record) {
        RegistrationStore.DonationStatus status = RegistrationStore.DonationStatus.parse(record.status);
        Material material = switch (status) {
            case GRANTED -> Material.SUNFLOWER;
            case REJECTED -> Material.BARRIER;
            case PENDING_NAME -> Material.NAME_TAG;
            case PENDING_AMOUNT -> Material.COPPER_INGOT;
            case PENDING_REVIEW -> Material.CLOCK;
        };
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        lore.add(messages.get("pass.donationsgui.item-line-status",
                "status", statusLabel(status),
                "date", AccessManager.formatTimestamp(record.createdAt),
                "source", record.source == null || record.source.isBlank() ? "—" : record.source));
        lore.add(messages.get("pass.donationsgui.item-line-donor",
                "donor", record.donorName == null || record.donorName.isBlank() ? "—" : record.donorName,
                "player", record.grantedTo == null || record.grantedTo.isBlank() ? "—" : record.grantedTo,
                "days", record.days));
        if (status != RegistrationStore.DonationStatus.GRANTED
                && status != RegistrationStore.DonationStatus.REJECTED) {
            lore.add(messages.get("pass.donationsgui.item-lore-approve"));
            lore.add(messages.get("pass.donationsgui.item-lore-reject"));
        }
        ItemStack item = control(material,
                messages.get("pass.donationsgui.item-name",
                        "id", record.id,
                        "amount", DonateMenuService.formatRubles(record.amountKopecks)),
                lore);
        return item;
    }

    private String statusLabel(RegistrationStore.DonationStatus status) {
        String key = switch (status) {
            case GRANTED -> "donation.status-granted";
            case PENDING_NAME -> "donation.status-pending-name";
            case PENDING_AMOUNT -> "donation.status-pending-amount";
            case PENDING_REVIEW -> "donation.status-pending-review";
            case REJECTED -> "donation.status-rejected";
        };
        return Messages.stripFormatting(messages.raw(key));
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof DonationsGuiHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!holder.getInventory().equals(event.getClickedInventory())) return;
        int slot = event.getRawSlot();

        // управление
        if (slot == 0) { // назад
            openMenu(player, holder.filter(), holder.page() - 1);
            return;
        }
        if (slot == 1) { // фильтр
            openMenu(player, holder.filter().next(), 0);
            return;
        }
        if (slot == 2) { // обновить
            openMenu(player, holder.filter(), holder.page());
            return;
        }
        if (slot == 4) {
            if (event.getClick().isRightClick()) { // ПКМ — внеочередной опрос транзакций
                player.performCommand("pass recheck");
                openMenu(player, holder.filter(), holder.page());
                return;
            }
            // ЛКМ — принудительное обновление баланса
            long apiWait = accessManager.donatePayApiWaitMillis();
            if (walletRefreshInFlight || (apiWait > 0 && walletCache != null && walletCache.error() == null)) {
                // запрос уже летит ИЛИ окно API занято, а свежий баланс у нас есть —
                // честно говорим, когда ждать, вместо сброса кэша в «загружается…»
                messages.send(player, "pass.donationsgui.balance-wait",
                        "seconds", Math.max(1, AccessManager.waitSeconds(apiWait)));
            } else {
                walletCache = null;
                refreshWallet();
            }
            openMenu(player, holder.filter(), holder.page());
            return;
        }
        if (slot == 8) { // вперёд
            openMenu(player, holder.filter(), holder.page() + 1);
            return;
        }

        // запись (9..53) → индекс в странице
        if (slot < 9 || slot >= INVENTORY_SIZE) return;
        Page data = page(holder.filter(), holder.page());
        int recordIndex = slot - 9;
        if (recordIndex >= data.records().size()) return;
        RegistrationStore.DonationRecord record = data.records().get(recordIndex);
        RegistrationStore.DonationStatus status = RegistrationStore.DonationStatus.parse(record.status);
        if (status == RegistrationStore.DonationStatus.GRANTED
                || status == RegistrationStore.DonationStatus.REJECTED) {
            return; // исторические записи не кликабельны — просто справка
        }
        player.closeInventory();
        if (event.getClick().isRightClick()) {
            player.performCommand("pass reject " + record.id);
        } else {
            player.performCommand("pass approve " + record.id);
        }
        // после действия меню обновляется само (отчёт уже ушёл в чат командой)
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                openMenu(player, holder.filter(), holder.page());
            }
        }, 4L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof DonationsGuiHolder) {
            event.setCancelled(true);
        }
    }

    /** Маркержолдер: фильтр и страница текущего вида (для кликов и перерисовки). */
    static final class DonationsGuiHolder implements InventoryHolder {
        private final Filter filter;
        private final int page;
        private Inventory inventory;

        DonationsGuiHolder(Filter filter, int page) {
            this.filter = filter;
            this.page = page;
        }

        void bind(Inventory inventory) {
            this.inventory = inventory;
        }

        Filter filter() {
            return filter;
        }

        int page() {
            return page;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
