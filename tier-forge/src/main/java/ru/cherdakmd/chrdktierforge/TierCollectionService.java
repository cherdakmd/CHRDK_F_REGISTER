package ru.cherdakmd.chrdktierforge;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.cherdakmd.chrdkregister.Messages;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Коллекция кузни: отмечает, что игрок впервые увидел, объявляет новинки и
 * платит за вехи и закрытые разделы (1.4.150–1.4.151).
 *
 * <p>Вызывается там, где предмет окончательно получил тир: крафт (старый
 * флоу), опознание, дроп с моба и результат наковальни. В режиме неопознанных
 * предметов крафт ничего не записывает — редкость ещё скрыта, и «коллекция по
 * превью» была бы бесплатной разведкой.</p>
 *
 * <p>Награда выдаётся ровно один раз: отметка {@code claimed} пишется в книгу
 * вместе с прогрессом, поэтому сбой между выплатой и сохранением не удвоит
 * дни проходки. За полностью закрытый раздел платится крупнее и выдаётся
 * титул — он надевается рядом с ником через {@code NameTagProvider} ядра.</p>
 */
public final class TierCollectionService {

    private final TierForgePlugin plugin;
    private final TierCollectionKeeper keeper;

    public TierCollectionService(TierForgePlugin plugin, TierCollectionKeeper keeper) {
        this.plugin = plugin;
        this.keeper = keeper;
    }

    /** Отмечает собранное по предмету; вызывать после наката/опознания. */
    public void record(Player player, ItemStack item) {
        if (player == null || item == null) {
            return;
        }
        TierCollectionConfig config = config();
        if (config == null || !config.enabled()) {
            return;
        }
        String tier = plugin.items().tierOf(item);
        if (tier == null) {
            return;
        }
        List<String> properties = new ArrayList<>();
        for (TierProperty property : plugin.items().propertiesOf(item)) {
            properties.add(property.id());
        }
        List<String> affixes = new ArrayList<>();
        for (TierAffix affix : plugin.items().affixesOf(item)) {
            affixes.add(affix.id());
        }
        TierCollectionBook.Discovery discovery = keeper.record(
                player.getUniqueId(), tier, properties, affixes);
        if (discovery.isEmpty()) {
            return;
        }
        if (config.announceFinds()) {
            announce(player, discovery);
        }
        grantMilestones(player, config);
        grantSections(player, config);
        // 1.4.152: новая находка могла дать титул — имя в табе обновляем сразу.
        plugin.refreshTab(player);
    }

    public TierCollectionBook.Snapshot snapshot(Player player) {
        return player == null
                ? TierCollectionBook.Snapshot.EMPTY
                : keeper.snapshot(player.getUniqueId());
    }

    /** Снимок по UUID — админская статистика офлайн-игрока. */
    public TierCollectionBook.Snapshot snapshot(UUID player) {
        return player == null ? TierCollectionBook.Snapshot.EMPTY : keeper.snapshot(player);
    }

    public TierCollectionBook.Progress progress(UUID player) {
        return keeper.progress(player);
    }

    public TierCollectionKeeper keeper() {
        return keeper;
    }

    public TierCollectionConfig config() {
        TierForgeConfig config = plugin.config();
        return config == null ? null : config.collection();
    }

    public boolean enabled() {
        TierCollectionConfig config = config();
        return config != null && config.enabled();
    }

    /* -------------------------------- титулы -------------------------------- */

    /** Титул игрока в виде готовой MiniMessage-строки или {@code null}. */
    public String titleOf(Player player) {
        return player == null ? null : titleFor(player.getUniqueId());
    }

    /** Титул по UUID — нужен витрине, статистике и рейтингу. */
    public String titleFor(UUID player) {
        TierCollectionConfig config = config();
        if (config == null || !config.titles().enabled()) {
            return null;
        }
        return titleText(shownSection(player));
    }

    /**
     * Раздел, титул которого игрок реально носит: выбор игрока, если раздел
     * закрыт, иначе самый весомый заработанный. {@code null} — титула нет
     * или он скрыт командой {@code /tiers collection title off}.
     */
    public String shownSection(UUID player) {
        TierCollectionBook.Progress progress = keeper.progress(player);
        return TierCollectionTitles.selected(progress, keeper.titleChoice(player));
    }

    /** Закрыт ли раздел — по нему команда решает, можно ли надеть титул. */
    public boolean sectionUnlocked(UUID player, String section) {
        return TierCollectionTitles.isSection(section)
                && keeper.progress(player).complete(section);
    }

    /** Текущий выбор игрока: {@code null} — авто, {@code "off"} — скрыт, иначе ид. */
    public String choice(UUID player) {
        return keeper.titleChoice(player);
    }

    /**
     * Меняет выбор титула.
     *
     * @param raw {@code auto}, {@code off} или ид закрытого раздела
     * @return ключ сообщения об ошибке или {@code null}, если выбор применён
     */
    public String chooseTitle(Player player, String raw) {
        if (player == null) {
            return "player-only";
        }
        TierCollectionConfig config = config();
        if (config == null || !config.titles().enabled()) {
            return "collection-titles-disabled";
        }
        String value = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        boolean auto = value.isEmpty() || TierCollectionTitles.AUTO.equals(value);
        boolean off = TierCollectionTitles.OFF.equals(value);
        if (!auto && !off && !TierCollectionTitles.isSection(value)) {
            return "collection-title-unknown";
        }
        if (!auto && !off && !sectionUnlocked(player.getUniqueId(), value)) {
            return "collection-title-locked";
        }
        if (!keeper.chooseTitle(player.getUniqueId(), value)) {
            return "collection-title-empty-book";
        }
        plugin.refreshTab(player);
        return null;
    }

    /** Текст титула по ид раздела; ключи перечислены явно, чтобы их видел контракт. */
    private String titleText(String section) {
        if (section == null) {
            return null;
        }
        return switch (section) {
            case "tiers" -> plugin.messages().raw("title.tiers");
            case "affixes" -> plugin.messages().raw("title.affixes");
            case "properties" -> plugin.messages().raw("title.properties");
            case "all" -> plugin.messages().raw("title.all");
            default -> null;
        };
    }

    /** Человеческое имя раздела для сообщений и витрины. */
    public String sectionName(String section) {
        if (section == null) {
            return "";
        }
        return switch (section) {
            case "tiers" -> plugin.messages().raw("collection-section-tiers");
            case "affixes" -> plugin.messages().raw("collection-section-affixes");
            case "properties" -> plugin.messages().raw("collection-section-properties");
            case "all" -> plugin.messages().raw("collection-section-all");
            default -> section;
        };
    }

    /**
     * Титул по нику — точка входа для {@code NameTagProvider} ядра. Ник в чате
     * всегда принадлежит онлайну, поэтому дополнительных запросов к профилям нет.
     */
    public String titleOfName(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return null;
        }
        Player online = Bukkit.getPlayerExact(playerName);
        return online == null ? null : titleOf(online);
    }

    /** Строка «Ник [титул]» для провайдера оформления ников. */
    public String decorateName(String playerName, String decoratedName) {
        String title = titleOfName(playerName);
        if (title == null) {
            return null;
        }
        String format = plugin.messages().raw("title-format");
        if (format == null || format.isBlank()) {
            return decoratedName + " " + title;
        }
        return plugin.messages().raw("title-format",
                "name", Messages.trusted(decoratedName),
                "title", Messages.trusted(title));
    }

    /* ------------------------------ сообщения ------------------------------ */

    private void announce(Player player, TierCollectionBook.Discovery discovery) {
        List<String> parts = new ArrayList<>(3);
        if (discovery.tier() != null) {
            parts.add(plugin.messages().raw("collection-part-tier",
                    "tier", Messages.trusted(plugin.messages()
                            .raw("tier." + discovery.tier() + ".name"))));
        }
        if (!discovery.properties().isEmpty()) {
            parts.add(plugin.messages().raw("collection-part-properties",
                    "count", discovery.properties().size()));
        }
        if (!discovery.affixes().isEmpty()) {
            parts.add(plugin.messages().raw("collection-part-affixes",
                    "count", discovery.affixes().size()));
        }
        String joined = String.join(plugin.messages().raw("collection-part-separator"), parts);
        plugin.messages().send(player, "collection-found", "parts", Messages.trusted(joined));
    }

    /* ------------------------------- награды ------------------------------- */

    private void grantMilestones(Player player, TierCollectionConfig config) {
        TierCollectionBook.Snapshot snapshot = snapshot(player);
        for (TierCollectionConfig.Milestone milestone : config.properties()) {
            if (snapshot.propertyCount() >= milestone.count()) {
                claim(player, "properties:" + milestone.count(), milestone,
                        plugin.messages().raw("collection-milestone-properties",
                                "count", milestone.count()));
            }
        }
        for (TierCollectionConfig.Milestone milestone : config.affixes()) {
            if (snapshot.affixCount() >= milestone.count()) {
                claim(player, "affixes:" + milestone.count(), milestone,
                        plugin.messages().raw("collection-milestone-affixes",
                                "count", milestone.count()));
            }
        }
    }

    /** Закрытые разделы: крупная награда и титул, по одному разу на раздел. */
    private void grantSections(Player player, TierCollectionConfig config) {
        TierCollectionBook.Progress progress = keeper.progress(player.getUniqueId());
        for (String section : TierCollectionConfig.SECTIONS) {
            if (!progress.complete(section)) {
                continue;
            }
            if (!keeper.claim(player.getUniqueId(), "section:" + section)) {
                continue;
            }
            TierCollectionConfig.SectionReward reward = config.section(section);
            pay(player, "section:" + section, reward.passDays(), reward.diamonds());
            plugin.messages().send(player, "collection-section",
                    "name", Messages.trusted(sectionName(section)),
                    "reward", Messages.trusted(rewardText(reward.passDays(),
                            reward.diamonds())));
            if (config.titles().enabled()) {
                String title = titleText(section);
                if (title != null) {
                    plugin.messages().send(player, "collection-section-title",
                            "title", Messages.trusted(title));
                }
            }
        }
    }

    private void claim(Player player, String key, TierCollectionConfig.Milestone milestone,
                       String name) {
        if (!keeper.claim(player.getUniqueId(), key)) {
            return; // веха уже выплачена
        }
        pay(player, key, milestone.passDays(), milestone.diamonds());
        plugin.messages().send(player, "collection-milestone",
                "name", Messages.trusted(name),
                "reward", Messages.trusted(rewardText(milestone.passDays(),
                        milestone.diamonds())));
    }

    private void pay(Player player, String key, int passDays, int diamonds) {
        if (passDays > 0) {
            if (plugin.coreApi() != null) {
                plugin.coreApi().grantPass(player.getName(), passDays,
                        "tierforge:collection:" + key);
            } else {
                plugin.getLogger().warning("Ядро недоступно: награда коллекции " + key
                        + " не выдана игроку " + player.getName());
            }
        }
        int remaining = diamonds;
        while (remaining > 0) {
            int amount = Math.min(64, remaining);
            remaining -= amount;
            Map<Integer, ItemStack> leftover = player.getInventory()
                    .addItem(new ItemStack(Material.DIAMOND, amount));
            for (ItemStack rest : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
        }
    }

    /** Человеческий текст награды для чата. */
    String rewardText(int passDays, int diamonds) {
        List<String> parts = new ArrayList<>(2);
        if (passDays > 0) {
            parts.add(plugin.messages().raw("collection-reward-days",
                    "days", passDays));
        }
        if (diamonds > 0) {
            parts.add(plugin.messages().raw("collection-reward-diamonds",
                    "count", diamonds));
        }
        if (parts.isEmpty()) {
            return plugin.messages().raw("collection-reward-none");
        }
        return String.join(plugin.messages().raw("collection-reward-join"), parts);
    }
}
