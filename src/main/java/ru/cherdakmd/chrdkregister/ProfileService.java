package ru.cherdakmd.chrdkregister;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
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
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /profile} — карточка игрока, собранная из разделов аддонов (1.4.156).
 *
 * <p>Ядро показывает своё: проходку, аккаунт, 2FA, банк и покупки каталога.
 * Остальное присылают аддоны через {@link ChrdkRegisterApi#registerProfile(String,
 * ChrdkRegisterApi.ProfileProvider)}: охота — монеты и звание, заказы —
 * репутацию, достижения — коллекцию и так далее. Нет аддона — нет раздела:
 * карточка остаётся рабочей.</p>
 *
 * <p>Меню на 54 слота: голова игрока, до двадцати одного раздела и кнопки
 * «В чат», «Обновить», «Закрыть». Клик по разделу закрывает меню и печатает
 * его строки в чат; {@code /profile chat [ник]} выводит всё текстом — это же
 * работает из консоли.</p>
 *
 * <p>Профиль другого игрока показывает те же разделы, но только публичные
 * данные: аддон сам решает, что отдавать при {@code self = false}. Доступ
 * ограничен правами {@code chrdkregister.profile} и
 * {@code chrdkregister.profile.others}.</p>
 */
public final class ProfileService implements Listener {

    /** Размер меню: шесть рядов. */
    public static final int SIZE = 54;
    /** Голова игрока по центру верхнего ряда. */
    public static final int HEAD_SLOT = 4;
    /** Сетка разделов: три ряда по семь. */
    static final int[] SECTION_SLOTS = {19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43};
    static final int TO_CHAT_SLOT = 45;
    static final int REFRESH_SLOT = 49;
    static final int CLOSE_SLOT = 53;

    private static final long DAY_MILLIS = 86_400_000L;

    private final RegisterPlugin plugin;
    private final AccessManager accessManager;
    private final BankStore bank;
    private final ProfileRegistry registry;

    public ProfileService(RegisterPlugin plugin, AccessManager accessManager, BankStore bank,
                          ProfileRegistry registry) {
        this.plugin = plugin;
        this.accessManager = accessManager;
        this.bank = bank;
        this.registry = registry;
    }

    /* --------------------------------- вход ---------------------------------- */

    /**
     * Показывает профиль: меню игроку или текст в чат (консоль и
     * {@code /profile chat}).
     *
     * @return {@code true} — команда обработана
     */
    public boolean show(CommandSender sender, boolean chat, String targetName) {
        if (sender == null) {
            return false;
        }
        if (!sender.hasPermission("chrdkregister.profile")) {
            plugin.messages().send(sender, "profile.no-permission");
            return true;
        }
        Target target = resolve(sender, targetName);
        if (target == null) {
            return true;
        }
        if (!target.self() && !sender.hasPermission("chrdkregister.profile.others")) {
            plugin.messages().send(sender, "profile.others-denied");
            return true;
        }
        if (chat || !(sender instanceof Player)) {
            print(sender, target);
            return true;
        }
        open((Player) sender, target);
        return true;
    }

    /** Подсказки {@code /profile [chat] [ник]}. */
    public List<String> complete(CommandSender sender, String[] args) {
        if (args == null || args.length == 0 || args.length > 2) {
            return List.of();
        }
        if (args.length == 2) {
            return args[0].equalsIgnoreCase("chat") ? names(args[1]) : List.of();
        }
        String prefix = args[0] == null ? "" : args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if ("chat".startsWith(prefix)) {
            out.add("chat");
        }
        out.addAll(names(args[0]));
        return out;
    }

    private static List<String> names(String rawPrefix) {
        String prefix = rawPrefix == null ? "" : rawPrefix.toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                names.add(online.getName());
            }
        }
        return names;
    }

    /* ------------------------------- разрешение ------------------------------- */

    /** Профиль, к которому обращается команда. */
    record Target(UUID id, String name, boolean online, boolean self,
                  RegistrationStore.PlayerAccount account) {
    }

    private Target resolve(CommandSender sender, String rawName) {
        boolean selfRequest = rawName == null || rawName.isBlank();
        if (selfRequest && !(sender instanceof Player)) {
            plugin.messages().send(sender, "profile.player-only");
            return null;
        }
        String name = selfRequest ? ((Player) sender).getName() : rawName.trim();
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return new Target(online.getUniqueId(), online.getName(), true,
                    sender instanceof Player player && player.getUniqueId().equals(
                            online.getUniqueId()),
                    accessManager.store().getAccount(online.getName()).orElse(null));
        }
        Optional<RegistrationStore.PlayerAccount> account = accessManager.store().getAccount(name);
        OfflinePlayer offline = offline(name);
        if (account.isEmpty() && (offline == null || !offline.hasPlayedBefore())) {
            plugin.messages().send(sender, "profile.not-found", "player", name);
            return null;
        }
        UUID id = account.map(value -> value.uuid)
                .map(ProfileService::parseUuid)
                .orElse(null);
        if (id == null && offline != null) {
            id = offline.getUniqueId();
        }
        if (id == null) {
            plugin.messages().send(sender, "profile.not-found", "player", name);
            return null;
        }
        String resolvedName = account.map(value -> value.playerName == null
                        || value.playerName.isBlank() ? name : value.playerName)
                .orElseGet(() -> offline != null && offline.getName() != null
                        ? offline.getName() : name);
        boolean self = sender instanceof Player player && player.getUniqueId().equals(id);
        return new Target(id, resolvedName, false, self, account.orElse(null));
    }

    private static OfflinePlayer offline(String name) {
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached != null ? cached : Bukkit.getOfflinePlayer(name);
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /* -------------------------------- разделы --------------------------------- */

    /** Разделы профиля: сначала ядро, затем аддоны в их порядке. */
    List<ChrdkRegisterApi.ProfileSection> sections(Target target) {
        List<ChrdkRegisterApi.ProfileSection> out = new ArrayList<>();
        ChrdkRegisterApi.ProfileSection core = coreSection(target);
        if (core != null && !core.lines().isEmpty()) {
            out.add(core);
        }
        out.addAll(registry.sections(new ChrdkRegisterApi.ProfileContext(target.id(),
                target.name(), target.online(), target.self())));
        return List.copyOf(out);
    }

    /** Раздел ядра: проходка, аккаунт, 2FA, банк и покупки каталога. */
    private ChrdkRegisterApi.ProfileSection coreSection(Target target) {
        List<Component> lines = new ArrayList<>();
        long now = System.currentTimeMillis();
        RegistrationStore.PlayerAccount account = target.account();
        long until = account == null ? 0L : account.passUntil;
        if (until > now) {
            String days = String.valueOf(daysLeft(until, now));
            lines.add(plugin.messages().get(account.isTrialOnly()
                    ? "profile.core-trial" : "profile.core-pass", "days", days));
        } else {
            lines.add(plugin.messages().get("profile.core-no-pass"));
        }
        if (target.self()) {
            // Пароль, 2FA, банк и покупки — личные данные: в чужой карточке их нет.
            boolean registered = account != null && account.passwordHash != null
                    && !account.passwordHash.isBlank();
            lines.add(plugin.messages().get(registered
                    ? "profile.core-registered" : "profile.core-not-registered"));
            boolean twoFactor = account != null && account.totpEnabledAt > 0;
            lines.add(plugin.messages().get(twoFactor
                    ? "profile.core-two-factor" : "profile.core-two-factor-off"));
            lines.add(plugin.messages().get("profile.core-bank",
                    "balance", BankService.format(bank.balance(target.id())),
                    "sent", BankService.format(bank.sentOf(target.id())),
                    "received", BankService.format(bank.receivedOf(target.id()))));
            int entitlements = 0;
            if (account != null && account.entitlements != null) {
                for (Long end : account.entitlements.values()) {
                    if (end != null && end > now) {
                        entitlements++;
                    }
                }
            }
            if (entitlements > 0) {
                lines.add(plugin.messages().get("profile.core-entitlements",
                        "count", String.valueOf(entitlements)));
            }
            lines.add(plugin.messages().get("profile.core-own"));
        }
        return new ChrdkRegisterApi.ProfileSection(
                plugin.messages().raw("profile.section-core"), Material.NETHER_STAR, lines);
    }

    static long daysLeft(long until, long now) {
        long left = Math.max(0L, until - now);
        return (left + DAY_MILLIS - 1L) / DAY_MILLIS;
    }

    /* --------------------------------- меню ----------------------------------- */

    private void open(Player viewer, Target target) {
        List<ChrdkRegisterApi.ProfileSection> sections = sections(target);
        Holder holder = new Holder(viewer.getUniqueId(), target.name(), sections);
        Inventory inventory = Bukkit.createInventory(holder, SIZE,
                plugin.messages().get("profile.title", "player", target.name()));
        holder.inventory = inventory;
        inventory.setItem(HEAD_SLOT, head(target, sections.size()));
        for (int index = 0; index < Math.min(SECTION_SLOTS.length, sections.size()); index++) {
            inventory.setItem(SECTION_SLOTS[index], sectionIcon(sections.get(index)));
        }
        inventory.setItem(TO_CHAT_SLOT, button(Material.WRITABLE_BOOK,
                "profile.to-chat", "profile.to-chat-lore"));
        inventory.setItem(REFRESH_SLOT, button(Material.SUNFLOWER,
                "profile.refresh", "profile.refresh-lore"));
        inventory.setItem(CLOSE_SLOT, button(Material.BARRIER, "profile.close", null));
        viewer.openInventory(inventory);
    }

    private ItemStack head(Target target, int sectionCount) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        if (!(item.getItemMeta() instanceof SkullMeta meta)) {
            return item;
        }
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(target.id()));
        meta.displayName(plugin.messages().get("profile.head-name", "player", target.name()));
        List<Component> lore = new ArrayList<>();
        long now = System.currentTimeMillis();
        RegistrationStore.PlayerAccount account = target.account();
        long until = account == null ? 0L : account.passUntil;
        if (until > now) {
            lore.add(plugin.messages().get(account.isTrialOnly()
                            ? "profile.head-lore-trial" : "profile.head-lore-pass",
                    "days", String.valueOf(daysLeft(until, now))));
        } else {
            lore.add(plugin.messages().get("profile.head-lore-no-pass"));
        }
        if (target.self()) {
            lore.add(plugin.messages().get("profile.head-lore-bank",
                    "amount", BankService.format(bank.balance(target.id()))));
        }
        if (sectionCount > SECTION_SLOTS.length) {
            lore.add(plugin.messages().get("profile.head-lore-more",
                    "count", String.valueOf(sectionCount - SECTION_SLOTS.length)));
        }
        lore.add(plugin.messages().get("profile.head-lore-hint"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack sectionIcon(ChrdkRegisterApi.ProfileSection section) {
        ItemStack item = new ItemStack(section.icon());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(plugin.messages().renderTemplate(section.title()));
        List<Component> lore = new ArrayList<>(section.lines());
        lore.add(plugin.messages().get("profile.section-hint"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack button(Material material, String nameKey, String loreKey) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(plugin.messages().get(nameKey));
        if (loreKey != null) {
            meta.lore(List.of(plugin.messages().get(loreKey)));
        }
        item.setItemMeta(meta);
        return item;
    }

    /* --------------------------------- чат ------------------------------------ */

    /** Печатает профиль текстом: заголовок, разделы и подвал. */
    private void print(CommandSender sender, Target target) {
        List<ChrdkRegisterApi.ProfileSection> sections = sections(target);
        plugin.messages().send(sender, "profile.chat-header", "player", target.name());
        Component prefix = plugin.messages().get("profile.chat-line-prefix");
        for (ChrdkRegisterApi.ProfileSection section : sections) {
            plugin.messages().send(sender, "profile.chat-section",
                    "title", Messages.trusted(section.title()));
            for (Component line : section.lines()) {
                sender.sendMessage(prefix.append(line));
            }
        }
        if (sections.isEmpty()) {
            plugin.messages().send(sender, "profile.chat-empty");
        }
        plugin.messages().send(sender, "profile.chat-footer",
                "sections", String.valueOf(sections.size()));
    }

    private void printSection(CommandSender sender, ChrdkRegisterApi.ProfileSection section) {
        plugin.messages().send(sender, "profile.chat-section",
                "title", Messages.trusted(section.title()));
        Component prefix = plugin.messages().get("profile.chat-line-prefix");
        for (Component line : section.lines()) {
            sender.sendMessage(prefix.append(line));
        }
    }

    /* -------------------------------- клики ----------------------------------- */

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !holder.owner.equals(player.getUniqueId())) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (slot == TO_CHAT_SLOT) {
            player.closeInventory();
            Target target = resolve(player, holder.targetName);
            if (target != null) {
                print(player, target);
            }
            return;
        }
        if (slot == REFRESH_SLOT) {
            String name = holder.targetName;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Target target = resolve(player, name);
                if (target != null && player.isOnline()) {
                    open(player, target);
                }
            });
            return;
        }
        int index = sectionIndex(slot);
        if (index >= 0 && index < holder.sections.size()) {
            // Чат за открытым меню не видно: сначала закрываем, потом печатаем.
            player.closeInventory();
            printSection(player, holder.sections.get(index));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    static int sectionIndex(int slot) {
        for (int index = 0; index < SECTION_SLOTS.length; index++) {
            if (SECTION_SLOTS[index] == slot) {
                return index;
            }
        }
        return -1;
    }

    /** Кто открыл меню и чей профиль в нём показан. */
    static final class Holder implements InventoryHolder {
        private final UUID owner;
        private final String targetName;
        private final List<ChrdkRegisterApi.ProfileSection> sections;
        private Inventory inventory;

        Holder(UUID owner, String targetName, List<ChrdkRegisterApi.ProfileSection> sections) {
            this.owner = owner;
            this.targetName = targetName;
            this.sections = List.copyOf(sections);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
