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
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
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
 * ChrdkRegisterApi.ProfileProvider)}: достижения —
 * коллекцию, кузня — находки и так далее. Нет аддона — нет раздела:
 * карточка остаётся рабочей.</p>
 *
 * <p>Меню на 54 слота: голова игрока, до двадцати одного раздела и кнопки
 * «В чат», «Оформление ника» (только в своей карточке, 1.4.186), «Обновить»,
 * «Закрыть». Клик по разделу закрывает меню и печатает его строки в чат;
 * {@code /profile chat [ник]} выводит всё текстом — это же работает из
 * консоли.</p>
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
    /** 1.4.186: переход в меню «Оформление ника» — только в своей карточке. */
    static final int LABELS_SLOT = 47;
    /** В чужой онлайн-карточке — приглашение в группу через модуль гильдий. */
    static final int GROUP_INVITE_SLOT = 51;
    static final int FRIEND_INVITE_SLOT = 50;
    static final int REFRESH_SLOT = 49;
    static final int CLOSE_SLOT = 53;

    private static final long DAY_MILLIS = 86_400_000L;

    private final RegisterPlugin plugin;
    private final AccessManager accessManager;
    private final BankStore bank;
    private final ProfileRegistry registry;
    private final PublicSocialLinks publicSocialLinks;
    private final FriendStore friends;
    private final FriendRequests friendRequests;
    /** Личные переключатели надписей: раздел «Оформление ника» (1.4.186). */
    private LabelPreferences labelPreferences;
    private final java.util.Map<UUID, Integer> lastProfileClickTick = new java.util.HashMap<>();

    public ProfileService(RegisterPlugin plugin, AccessManager accessManager, BankStore bank,
                          ProfileRegistry registry) {
        this.plugin = plugin;
        this.accessManager = accessManager;
        this.bank = bank;
        this.registry = registry;
        this.publicSocialLinks = new PublicSocialLinks(plugin.getDataFolder().toPath(), plugin.getLogger());
        this.friends = new FriendStore(plugin.getDataFolder().toPath(), plugin.getLogger());
        this.friendRequests = new FriendRequests(friends);
    }

    /** Подключает переключатели надписей (вызывается при включении ядра). */
    public void setLabelPreferences(LabelPreferences preferences) {
        this.labelPreferences = preferences;
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
        if (!sender.hasPermission("chrdkregister.profile")
                || (sender instanceof Player player && accessManager.isBlocked(player))) {
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
        if (sender instanceof Player player && !target.self()) {
            Player online = Bukkit.getPlayer(target.id());
            if (online != null && !player.canSee(online)) {
                plugin.messages().send(sender, "profile.not-found", "player", target.name());
                return true;
            }
        }
        if (chat || !(sender instanceof Player)) {
            print(sender, target);
            return true;
        }
        open((Player) sender, target);
        return true;
    }

    /** ПКМ пустой основной рукой: не перехватываем предметы и отменённые действия. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof Player target)) return;
        Player viewer = event.getPlayer();
        if (viewer.getUniqueId().equals(target.getUniqueId()) || target.hasMetadata("NPC")
                || viewer.hasMetadata("NPC") || !viewer.canSee(target)
                || !viewer.getInventory().getItemInMainHand().getType().isAir()
                || !viewer.getInventory().getItemInOffHand().getType().isAir()
                || accessManager.isBlocked(viewer) || accessManager.isBlocked(target)
                || !viewer.hasPermission("chrdkregister.profile")
                || !viewer.hasPermission("chrdkregister.profile.others")) return;
        int tick = Bukkit.getCurrentTick();
        if (java.util.Objects.equals(lastProfileClickTick.put(viewer.getUniqueId(), tick), tick)) return;
        UUID targetId = target.getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player current = Bukkit.getPlayer(targetId);
            if (event.isCancelled() || !viewer.isOnline() || current == null || !current.isOnline()
                    || current.hasMetadata("NPC") || !viewer.canSee(current)
                    || accessManager.isBlocked(viewer) || accessManager.isBlocked(current)
                    || !viewer.getOpenInventory().getTopInventory().getType().equals(org.bukkit.event.inventory.InventoryType.CRAFTING)) return;
            show(viewer, false, current.getName());
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastProfileClickTick.remove(event.getPlayer().getUniqueId());
        friendRequests.quit(event.getPlayer().getUniqueId());
    }

    /** Приводит /friend к тому же маршруту, что и /profile friend. */
    static String[] friendArgs(String[] args) {
        String[] result = new String[args.length + 1];
        result[0] = "friend";
        System.arraycopy(args, 0, result, 1, args.length);
        return result;
    }

    /** Команды друзей: запросы временные, дружба сохраняется только после согласия. */
    public boolean friend(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "profile.friend-player-only");
            return true;
        }
        if (!player.hasPermission("chrdkregister.profile") || accessManager.isBlocked(player)) {
            plugin.messages().send(player, "profile.no-permission");
            return true;
        }
        if (!friends.available()) {
            plugin.messages().send(player, "profile.friend-unavailable");
            return true;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("list") && args.length <= 3) {
            int page = 1;
            if (args.length == 3) {
                try { page = Integer.parseInt(args[2]); } catch (NumberFormatException ignored) { page = 0; }
            }
            List<String> names = friends.friendsOf(player.getUniqueId()).stream()
                    .map(id -> { String name = Bukkit.getOfflinePlayer(id).getName(); return name == null ? id.toString() : name; })
                    .sorted(String.CASE_INSENSITIVE_ORDER).toList();
            int pages = Math.max(1, (names.size() + 9) / 10);
            if (page < 1 || page > pages) { plugin.messages().send(player, "profile.friend-usage"); return true; }
            plugin.messages().send(player, "profile.friend-list", "page", page, "pages", pages,
                    "names", names.isEmpty() ? "—" : String.join(", ", names.subList((page - 1) * 10, Math.min(page * 10, names.size()))));
            return true;
        }
        if (args.length != 3) { plugin.messages().send(player, "profile.friend-usage"); return true; }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("remove")) {
            Target target = resolve(player, args[2]);
            if (target == null) return true;
            FriendStore.Result result = friends.remove(player.getUniqueId(), target.id());
            if (result == FriendStore.Result.OK) plugin.messages().send(player, "profile.friend-removed", "player", target.name());
            else if (result == FriendStore.Result.NOT_FRIENDS) plugin.messages().send(player, "profile.friend-not-friends");
            else plugin.messages().send(player, "profile.friend-unavailable");
            return true;
        }
        if (!action.equals("invite") && !action.equals("accept") && !action.equals("decline")) {
            plugin.messages().send(player, "profile.friend-usage"); return true;
        }
        Player other = Bukkit.getPlayerExact(args[2]);
        if (other == null || !other.isOnline() || other.hasMetadata("NPC") || !player.canSee(other)
                || !other.canSee(player) || accessManager.isBlocked(other)
                || !other.hasPermission("chrdkregister.profile")) {
            plugin.messages().send(player, "profile.friend-offline"); return true;
        }
        if (action.equals("invite")) inviteFriend(player, other);
        else {
            FriendRequests.Result result = action.equals("accept")
                    ? friendRequests.accept(player.getUniqueId(), other.getUniqueId(), System.currentTimeMillis())
                    : friendRequests.decline(player.getUniqueId(), other.getUniqueId(), System.currentTimeMillis());
            if (result == FriendRequests.Result.OK) {
                if (action.equals("accept")) {
                    plugin.messages().send(player, "profile.friend-accepted", "player", other.getName());
                    plugin.messages().send(other, "profile.friend-accepted", "player", player.getName());
                } else {
                    plugin.messages().send(player, "profile.friend-declined");
                    plugin.messages().send(other, "profile.friend-declined-other", "player", player.getName());
                }
            } else friendError(player, result);
        }
        return true;
    }

    private void inviteFriend(Player player, Player target) {
        FriendRequests.Result result = friendRequests.invite(player.getUniqueId(), target.getUniqueId(), System.currentTimeMillis());
        if (result != FriendRequests.Result.OK) { friendError(player, result); return; }
        plugin.messages().send(player, "profile.friend-sent", "player", target.getName());
        plugin.messages().send(target, "profile.friend-received", "player", player.getName());
    }

    private void friendError(Player player, FriendRequests.Result result) {
        String key = switch (result) {
            case SELF -> "profile.friend-self";
            case FRIENDS -> "profile.friend-already";
            case PENDING -> "profile.friend-pending";
            case REVERSE_PENDING -> "profile.friend-reverse";
            case NO_REQUEST -> "profile.friend-no-request";
            case LIMIT -> "profile.friend-limit";
            case COOLDOWN -> "profile.friend-cooldown";
            default -> "profile.friend-unavailable";
        };
        plugin.messages().send(player, key);
    }

    /** Управление только своими явно опубликованными ссылками. */
    public boolean social(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "profile.social-player-only");
            return true;
        }
        if (!player.hasPermission("chrdkregister.profile") || accessManager.isBlocked(player)) {
            plugin.messages().send(player, "profile.no-permission");
            return true;
        }
        if (args.length == 1 || (args.length == 2 && args[1].equalsIgnoreCase("help"))) {
            plugin.messages().send(player, "profile.social-usage");
            return true;
        }
        boolean set = args.length == 4 && args[1].equalsIgnoreCase("set");
        boolean remove = args.length == 3 && args[1].equalsIgnoreCase("remove");
        PublicSocialLinks.Platform platform = args.length >= 3 ? PublicSocialLinks.Platform.parse(args[2]) : null;
        if ((!set && !remove) || platform == null) {
            plugin.messages().send(player, "profile.social-usage");
            return true;
        }
        if (set && PublicSocialLinks.validate(platform, args[3]).isEmpty()) {
            plugin.messages().send(player, "profile.social-invalid");
            return true;
        }
        if (!publicSocialLinks.update(player.getUniqueId(), platform, set ? args[3] : null)) {
            plugin.messages().send(player, "profile.social-save-error");
        } else {
            plugin.messages().send(player, set ? "profile.social-saved" : "profile.social-removed", "platform", platform.label);
        }
        return true;
    }

    private ChrdkRegisterApi.ProfileSection socialSection(Target target) {
        Optional<java.util.Map<PublicSocialLinks.Platform, String>> links = publicSocialLinks.read(target.id());
        if (links.isEmpty() || links.get().isEmpty()) return null;
        List<Component> lines = new ArrayList<>();
        for (PublicSocialLinks.Platform platform : PublicSocialLinks.Platform.values()) {
            String url = links.get().get(platform);
            if (url != null) {
                lines.add(plugin.messages().get("profile.social-line", "platform", platform.label, "url", url)
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(url)));
            }
        }
        return new ChrdkRegisterApi.ProfileSection(plugin.messages().raw("profile.social-title"), Material.WRITABLE_BOOK, lines);
    }

    /** Подсказки {@code /profile [chat] [ник]}. */
    public List<String> complete(CommandSender sender, String[] args) {
        if (args == null || args.length == 0 || !sender.hasPermission("chrdkregister.profile")
                || (sender instanceof Player player && accessManager.isBlocked(player))) return List.of();
        if (args.length >= 2 && args[0].equalsIgnoreCase("friend")) {
            if (args.length == 2) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                return List.of("invite", "accept", "decline", "remove", "list").stream()
                        .filter(option -> option.startsWith(prefix)).toList();
            }
            if (args.length != 3 || args[1].equalsIgnoreCase("list") || !(sender instanceof Player viewer)) return List.of();
            String prefix = args[2].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().filter(viewer::canSee)
                    .map(Player::getName).filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("social")) {
            List<String> options = args.length == 2 ? List.of("set", "remove", "help")
                    : args.length == 3 && (args[1].equalsIgnoreCase("set") || args[1].equalsIgnoreCase("remove"))
                    ? List.of("vk", "discord", "telegram", "youtube", "twitch") : List.of();
            String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
            return options.stream().filter(option -> option.startsWith(prefix)).toList();
        }
        if (args.length > 2) return List.of();
        if (args.length == 2) {
            return args[0].equalsIgnoreCase("chat") ? names(sender, args[1]) : List.of();
        }
        String prefix = args[0] == null ? "" : args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if ("chat".startsWith(prefix)) {
            out.add("chat");
        }
        if ("social".startsWith(prefix)) out.add("social");
        if ("friend".startsWith(prefix)) out.add("friend");
        out.addAll(names(sender, args[0]));
        return out;
    }

    private static List<String> names(CommandSender sender, String rawPrefix) {
        if (!sender.hasPermission("chrdkregister.profile") || !sender.hasPermission("chrdkregister.profile.others")) return List.of();
        String prefix = rawPrefix == null ? "" : rawPrefix.toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if ((!(sender instanceof Player viewer) || viewer.canSee(online))
                    && online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
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
        ChrdkRegisterApi.ProfileSection social = socialSection(target);
        if (social != null) out.add(social);
        ChrdkRegisterApi.ProfileSection labels = labelsSection(target);
        if (labels != null) {
            out.add(labels);
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

    /**
     * Раздел «Оформление ника» (1.4.186): что из надписей игрок оставил.
     *
     * <p>Показывается только в своей карточке: настройка личная, и чужой
     * профиль не должен рассказывать, что человек у себя спрятал. Внутри —
     * сколько надписей включено и какие именно скрыты.</p>
     */
    private ChrdkRegisterApi.ProfileSection labelsSection(Target target) {
        if (!target.self() || labelPreferences == null) {
            return null;
        }
        List<Component> lines = new ArrayList<>();
        int total = LabelPreferences.Label.values().length;
        int hidden = labelPreferences.hiddenCount(target.name());
        if (hidden == 0) {
            lines.add(plugin.messages().get("labels.profile-all-visible", "total", total));
        } else {
            lines.add(plugin.messages().get("labels.profile-line",
                    "visible", String.valueOf(total - hidden), "total", String.valueOf(total)));
            for (LabelPreferences.Label label : labelPreferences.hiddenLabels(target.name())) {
                lines.add(plugin.messages().get("labels.profile-hidden",
                        "name", LabelsMenuService.nameOf(plugin.messages(), label)));
            }
        }
        LabelStyleService.Selection selection = LabelsMenuService.selectionOf(plugin, target.name());
        if (selection != null) lines.add(LabelsMenuService.summary(plugin.messages(), selection));
        lines.add(plugin.messages().get("labels.profile-hint"));
        return new ChrdkRegisterApi.ProfileSection(
                plugin.messages().raw("labels.profile-section"), Material.NAME_TAG, lines);
    }

    static long daysLeft(long until, long now) {
        long left = Math.max(0L, until - now);
        return (left + DAY_MILLIS - 1L) / DAY_MILLIS;
    }

    /* --------------------------------- меню ----------------------------------- */

    private void open(Player viewer, Target target) {
        List<ChrdkRegisterApi.ProfileSection> sections = sections(target);
        Holder holder = new Holder(viewer.getUniqueId(), target.id(), target.name(), target.self(), sections);
        Inventory inventory = Bukkit.createInventory(holder, SIZE,
                plugin.messages().get("profile.title", "player", target.name()));
        holder.inventory = inventory;
        inventory.setItem(HEAD_SLOT, head(target, sections.size()));
        for (int index = 0; index < Math.min(SECTION_SLOTS.length, sections.size()); index++) {
            inventory.setItem(SECTION_SLOTS[index], sectionIcon(sections.get(index)));
        }
        inventory.setItem(TO_CHAT_SLOT, button(Material.WRITABLE_BOOK,
                "profile.to-chat", "profile.to-chat-lore"));
        if (target.self() && viewer.hasPermission("chrdkregister.labels")) {
            inventory.setItem(LABELS_SLOT, labelsButton());
        }
        if (!target.self() && target.online() && friends.available()
                && !friends.areFriends(viewer.getUniqueId(), target.id())) {
            inventory.setItem(FRIEND_INVITE_SLOT, button(Material.PLAYER_HEAD,
                    "profile.friend-invite-button", "profile.friend-invite-lore"));
        }
        if (!target.self() && target.online() && groupCommand() != null
                && viewer.hasPermission("chrdkguilds.use")) {
            inventory.setItem(GROUP_INVITE_SLOT, button(Material.LIME_BANNER,
                    "profile.group-invite", "profile.group-invite-lore"));
        }
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
                    "amount", BankService.formatMinor(bank.balanceMinor(target.id()))));
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

    /** Кнопка «Оформление ника»: имя и подсказка читаются литералами (контракт текстов). */
    private ItemStack labelsButton() {
        ItemStack item = new ItemStack(Material.NAME_TAG);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(plugin.messages().get("labels.profile-button"));
        meta.lore(List.of(plugin.messages().get("labels.profile-button-lore")));
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
        Player currentTarget = Bukkit.getPlayer(holder.targetId);
        if (!canOpenCard(!accessManager.isBlocked(player), player.hasPermission("chrdkregister.profile"),
                holder.self, player.hasPermission("chrdkregister.profile.others"),
                currentTarget == null || player.canSee(currentTarget))) {
            player.closeInventory();
            plugin.messages().send(player, "profile.access-changed");
            return;
        }
        if (slot == TO_CHAT_SLOT) {
            player.closeInventory();
            Target target = resolve(player, holder.targetName);
            if (target != null && holder.targetId.equals(target.id())) print(player, target);
            return;
        }
        if (slot == LABELS_SLOT) {
            // Кнопка есть только в своей карточке, но проверяем и здесь: клик
            // мог прийти из чужого меню, если холдер подменили.
            if (holder.self && holder.owner.equals(player.getUniqueId())
                    && player.hasPermission("chrdkregister.labels")) {
                LabelsMenuService menu = plugin.labelsMenu();
                if (menu != null) {
                    // Закрытие и открытие в одном тике клиент иногда теряет:
                    // показываем меню следующим тиком, когда экран уже свободен.
                    player.closeInventory();
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (player.isOnline() && !accessManager.isBlocked(player)
                                && player.hasPermission("chrdkregister.profile")
                                && player.hasPermission("chrdkregister.labels")) {
                            menu.open(player);
                        }
                    });
                }
            }
            return;
        }
        if (slot == FRIEND_INVITE_SLOT && !holder.self
                && event.getView().getTopInventory().getItem(FRIEND_INVITE_SLOT) != null) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player target = Bukkit.getPlayer(holder.targetId);
                if (!player.isOnline() || target == null || !target.isOnline()
                        || target.hasMetadata("NPC") || !player.canSee(target) || !target.canSee(player)
                        || accessManager.isBlocked(player) || accessManager.isBlocked(target)
                        || !target.hasPermission("chrdkregister.profile")
                        || !player.hasPermission("chrdkregister.profile")
                        || !player.hasPermission("chrdkregister.profile.others")
                        || player.getOpenInventory().getTopInventory().getHolder() != holder) return;
                player.closeInventory();
                inviteFriend(player, target);
            });
            return;
        }
        if (slot == GROUP_INVITE_SLOT && !holder.self
                && event.getView().getTopInventory().getItem(GROUP_INVITE_SLOT) != null) {
            Player target = Bukkit.getPlayer(holder.targetId);
            org.bukkit.command.PluginCommand command = groupCommand();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline() || target == null || !target.isOnline() || !player.canSee(target)
                        || accessManager.isBlocked(player) || accessManager.isBlocked(target)
                        || !player.hasPermission("chrdkregister.profile")
                        || !player.hasPermission("chrdkregister.profile.others")
                        || !player.hasPermission("chrdkguilds.use") || command == null
                        || groupCommand() != command) return;
                if (player.getOpenInventory().getTopInventory().getHolder() != holder) return;
                player.closeInventory();
                command.execute(player, "party", new String[]{"invite", target.getName()});
            });
            return;
        }
        if (slot == REFRESH_SLOT) {
            String name = holder.targetName;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline() || player.getOpenInventory().getTopInventory().getHolder() != holder) return;
                Target target = resolve(player, name);
                if (target != null && holder.targetId.equals(target.id())) show(player, false, name);
            });
            return;
        }
        int index = sectionIndex(slot);
        if (index >= 0 && index < holder.sections.size()) {
            Target target = resolve(player, holder.targetName);
            ChrdkRegisterApi.ProfileSection selected = holder.sections.get(index);
            ChrdkRegisterApi.ProfileSection fresh = target == null || !holder.targetId.equals(target.id())
                    ? null : currentSection(selected, holder.sections, sections(target));
            if (fresh == null) {
                player.closeInventory();
                plugin.messages().send(player, "profile.stale-section");
                return;
            }
            // Печатаем актуальные данные, а не снимок меню, где ссылка могла быть удалена.
            player.closeInventory();
            printSection(player, fresh);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    /** Доступ проверяется заново при каждом действии в уже открытом меню. */
    static boolean canOpenCard(boolean authenticated, boolean profilePermission, boolean self,
                               boolean othersPermission, boolean targetVisible) {
        return authenticated && profilePermission && (self || (othersPermission && targetVisible));
    }

    /** Поиск только однозначной пары заголовок/иконка в обоих снимках. */
    static ChrdkRegisterApi.ProfileSection currentSection(ChrdkRegisterApi.ProfileSection selected,
            List<ChrdkRegisterApi.ProfileSection> original, List<ChrdkRegisterApi.ProfileSection> current) {
        if (selected == null || original == null || current == null) return null;
        var oldMatches = original.stream().filter(section -> sameSectionKind(selected, section)).toList();
        var newMatches = current.stream().filter(section -> sameSectionKind(selected, section)).toList();
        return oldMatches.size() == 1 && newMatches.size() == 1 ? newMatches.get(0) : null;
    }

    private static boolean sameSectionKind(ChrdkRegisterApi.ProfileSection a, ChrdkRegisterApi.ProfileSection b) {
        return b != null && a.title().equals(b.title()) && a.icon() == b.icon();
    }

    static int sectionIndex(int slot) {
        for (int index = 0; index < SECTION_SLOTS.length; index++) {
            if (SECTION_SLOTS[index] == slot) {
                return index;
            }
        }
        return -1;
    }

    private org.bukkit.command.PluginCommand groupCommand() {
        org.bukkit.plugin.Plugin guilds = Bukkit.getPluginManager().getPlugin("CHRDK_Guilds");
        if (guilds == null || !guilds.isEnabled()) return null;
        org.bukkit.command.PluginCommand command = Bukkit.getPluginCommand("chrdk_guilds:party");
        return command != null && command.getPlugin() == guilds ? command : null;
    }

    /** Кто открыл меню и чей профиль в нём показан. */
    static final class Holder implements InventoryHolder {
        private final UUID owner;
        private final UUID targetId;
        private final String targetName;
        private final boolean self;
        private final List<ChrdkRegisterApi.ProfileSection> sections;
        private Inventory inventory;

        Holder(UUID owner, UUID targetId, String targetName, boolean self, List<ChrdkRegisterApi.ProfileSection> sections) {
            this.owner = owner;
            this.targetId = targetId;
            this.self = self;
            this.targetName = targetName;
            this.sections = List.copyOf(sections);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
