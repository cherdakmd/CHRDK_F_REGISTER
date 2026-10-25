package ru.cherdakmd.chrdkregister;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.node.Node;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Каталог товаров: выдача, продление, отзыв и показ покупок.
 *
 * <p>Товары покупаются на витрине (TradeMC), а плагин получает по RCON команду
 * {@code pass shopbuy <ник> <товар>} — так же, как проходка получает {@code pass shoppay}.
 * Поэтому сервис вызывается из консоли: никаких {@code Player} на входе, ник
 * может быть оффлайн-игроком.</p>
 *
 * <p>Срок хранится в аккаунте ({@link RegistrationStore}), а действие товара — временная
 * группа LuckPerms на тот же срок. LuckPerms снимет группу сам; плагин дополнительно
 * подчищает истёкшие записи, чтобы файл данных не рос.</p>
 */
public final class ShopService {

    /** Со ступени, чья покупка попадает в «летопись» (Discord): Меценат и Покровитель. */
    private static final int SPONSOR_LEVEL = 4;

    /** Итог выдачи товара — для команды и текстов. */
    public enum Status {
        /** Товар выдан или продлён. */
        GRANTED,
        /** Каталог выключен или пуст. */
        DISABLED,
        /** Такого товара нет в конфиге. */
        UNKNOWN_PRODUCT,
        /** У игрока уже есть ранг не ниже — покупка не нужна. */
        RANK_HIGHER
    }

    public record Result(Status status, String playerName, ShopProduct product,
                         long untilMillis, int days, List<String> included, int bonusDays) {

        public Result {
            included = included == null ? List.of() : List.copyOf(included);
        }

        /** Совместимый конструктор без скидки. */
        public Result(Status status, String playerName, ShopProduct product,
                      long untilMillis, int days, List<String> included) {
            this(status, playerName, product, untilMillis, days, included, 0);
        }

        /** Скидка подписчика Boosty сработала. */
        public boolean hasBonus() {
            return bonusDays > 0;
        }

        public boolean granted() {
            return status == Status.GRANTED;
        }

        /** Набор выдал не только себя: перечислить состав в тексте. */
        public boolean isBundle() {
            return !included.isEmpty();
        }

        public static Result failure(Status status, String playerName) {
            return new Result(status, playerName, null, 0L, 0, List.of(), 0);
        }
    }

    /** Активная покупка игрока: товар и момент окончания. */
    public record Active(ShopProduct product, long untilMillis) {
    }

    private final RegisterPlugin plugin;
    private final AccessManager access;
    private final ExternalHooks hooks;
    private final Logger logger;

    public ShopService(RegisterPlugin plugin, AccessManager access, ExternalHooks hooks, Logger logger) {
        this.plugin = plugin;
        this.access = access;
        this.hooks = hooks;
        this.logger = logger;
    }

    /** Текущий каталог (перечитывается из конфига при каждом обращении — /pass reload бесплатен). */
    public ShopCatalog catalog() {
        return access.config().shop();
    }

    /** Товар по id с приведением регистра. */
    public Optional<ShopProduct> product(String id) {
        return catalog().find(id);
    }

    /**
     * Товар витрины по сумме платежа — для карточек TradeMC (1.4.167).
     *
     * <p>Панель магазина присылает только сумму ({@code pass shoppay <ник> <руб>}),
     * поэтому товар ищется по цене. Цены в каталоге обязаны быть уникальными:
     * если две карточки стоят одинаково, выбор неоднозначен — берём первую
     * и пишем предупреждение, чтобы владелец поправил витрину.</p>
     *
     * @param rubles сумма платежа в рублях
     * @return товар или пусто, если такой цены в каталоге нет
     */
    public Optional<ShopProduct> productByPrice(int rubles) {
        List<ShopProduct> matches = ShopCatalog.matchingPrice(catalog().products(), rubles);
        if (matches.size() > 1) {
            logger.warning("Витрина: цена " + rubles + " ₽ у нескольких товаров ("
                    + String.join(", ", matches.stream().map(ShopProduct::id).toList())
                    + ") — выдан первый, поправьте shop.products");
        }
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));
    }

    /**
     * Выдаёт товар игроку.
     *
     * @param playerName ник покупателя (может быть оффлайн)
     * @param productId  id товара из каталога
     * @param days       переопределение срока; {@code <= 0} — срок из товара
     * @param actor      кто выдал (консоль, RCON, админ) — для аудита
     * @return результат выдачи
     */
    public Result grant(String playerName, String productId, int days, String actor) {
        return grant(playerName, productId, days, actor, "");
    }

    /**
     * Выдаёт товар и, если он набор, всё его содержимое.
     *
     * <p>Набор — рецепт покупки: отдельных записей в аккаунте он не создаёт, игрок
     * получает ровно перечисленные товары, каждый со своим сроком. Поэтому витрина
     * {@code /pass my} показывает состав, а не «набор», и продление одной вещи
     * из набора не задевает остальные.</p>
     *
     * @param giftFrom ник дарителя; пусто — обычная покупка. Подарок отличается
     *                 только журналом, аудитом и поздравлением получателя
     */
    public Result grant(String playerName, String productId, int days, String actor, String giftFrom) {
        return grant(playerName, productId, days, actor, giftFrom, true);
    }

    /**
     * Выдаёт товар без записи в журнал продаж: награда аддона или компенсация.
     *
     * <p>Срок, группа, команды и аудит — те же, что у покупки. Отличие одно: в журнале
     * донатов не появляется строка с ценой, иначе бесплатные выдачи раздували бы выручку
     * и ломали сверку с витриной. Событие аудита — {@link AuditLog#SHOP_GRANT}.</p>
     *
     * @param source откуда пришла выдача, например {@code battlepass:mine_diamonds}
     * @return момент окончания срока; {@code 0} — товар не выдан
     */
    public long grantInternal(String playerName, String productId, int days, String source) {
        Result result = grant(playerName, productId, days,
                source == null || source.isBlank() ? "internal" : source, "", false);
        return result.granted() ? result.untilMillis() : 0L;
    }

    private Result grant(String playerName, String productId, int days, String actor,
                         String giftFrom, boolean recordSale) {
        ShopCatalog catalog = catalog();
        if (!catalog.usable()) {
            return Result.failure(Status.DISABLED, playerName);
        }
        ShopProduct product = catalog.find(productId).orElse(null);
        if (product == null) {
            return Result.failure(Status.UNKNOWN_PRODUCT, playerName);
        }
        int length = days > 0
                ? Math.min(days, ShopCatalog.MAX_PRODUCT_DAYS)
                : product.days();
        long now = System.currentTimeMillis();

        String canonical = access.store().getAccount(playerName)
                .map(account -> account.playerName)
                .filter(name -> name != null && !name.isBlank())
                .orElse(playerName);

        // Скидка — про покупку: за награды аддонов (recordSale = false) бонус не даём,
        // иначе бесплатные выдачи раздували бы срок за счёт подписки.
        ShopDiscount discount = recordSale ? discountFor(canonical) : ShopDiscount.DISABLED;
        int bonusDays = discount.bonusFor(length);
        long until;
        if (product.hasRank()) {
            until = grantRankProduct(canonical, product, length, bonusDays, now);
            if (until <= 0L) {
                return Result.failure(Status.RANK_HIGHER, canonical);
            }
        } else {
            until = product.isBundle() ? 0L : grantOne(canonical, product, now, length, discount);
        }
        List<String> included = new ArrayList<>();
        if (product.isBundle()) {
            until = now;
            bonusDays = 0;
            for (String partId : product.resolvedIncludes()) {
                ShopProduct part = catalog.find(partId).orElse(null);
                if (part == null) {
                    continue; // набор пережил правку конфига — выдаём то, что ещё существует
                }
                until = Math.max(until, grantOne(canonical, part, now, part.days(), discount));
                bonusDays += discount.bonusFor(part.days());
                included.add(part.id());
            }
            if (product.hasPassDays()) {
                RegistrationStore.PlayerAccount account = access.grantPassManually(
                        canonical, product.passDays(), "shop:" + product.id());
                until = Math.max(until, account.passUntil);
            }
        }

        boolean gift = giftFrom != null && !giftFrom.isBlank();
        if (recordSale) {
            access.store().recordShopSale(canonical, product.id(), product.priceRubles(), now,
                    gift ? "gift:" + giftFrom.trim() : "");
        }
        String event = !recordSale ? AuditLog.SHOP_GRANT
                : gift ? AuditLog.SHOP_GIFT : AuditLog.SHOP_BUY;
        access.audit().log(event,
                gift ? giftFrom.trim() : actor == null || actor.isBlank() ? "console" : actor,
                canonical, "",
                AuditLog.details("product", product.id(), "days", length,
                        "until", until, "price", recordSale ? product.priceRubles() : 0,
                        "included", String.join(",", included),
                        "bonus", bonusDays));

        Player online = Bukkit.getPlayerExact(canonical);
        if (online != null) {
            if (gift) {
                plugin.messages().send(online, "shop.gift-received",
                        "name", Messages.trusted(product.name()), "from", giftFrom.trim(),
                        "until", AccessManager.formatTimestamp(until));
            } else {
                plugin.messages().send(online, "shop.received",
                        "name", Messages.trusted(product.name()),
                        "until", AccessManager.formatTimestamp(until));
            }
            if (bonusDays > 0) {
                plugin.messages().send(online, "shop.received-bonus",
                        "days", bonusDays, "percent", discount.percent());
            }
        }
        if (product.hasRank() && product.rank() >= SPONSOR_LEVEL) {
            // 1.4.167: Меценат и Покровитель попадают в «летопись» — тот же вебхук,
            // что у pass.granted. Гасится штатным троттлингом уведомлений.
            String title = plugin.messages().raw("rank.legend-title");
            access.notifications().notify("pass.granted", title,
                    plugin.messages().plain("rank.legend-desc",
                            "player", canonical,
                            "rank", Messages.stripTags(product.name()),
                            "until", AccessManager.formatTimestamp(until),
                            "gift", gift ? giftFrom.trim() : "—"),
                    "good");
        }
        logger.info("Товар '" + product.id() + (gift ? "' подарен " + canonical + " от " + giftFrom.trim()
                : "' выдан " + canonical) + " на " + length + " дн. (до "
                + AccessManager.formatTimestamp(until) + ", цена " + product.priceRubles()
                + " ₽, источник: " + (actor == null ? "console" : actor) + ")."
                + (included.isEmpty() ? "" : " Набор: " + String.join(", ", included) + "."));
        return new Result(Status.GRANTED, canonical, product, until, length, included, bonusDays);
    }

    /**
     * Выдаёт ранг из карточки каталога.
     *
     * <p>Повышение пересчитывает остаток по цене месячных карточек
     * ({@link RankLadder#upgradeDays}), продление того же ранга складывает дни,
     * понижение и покупка при уже имеющемся постоянном ранге не выдаются.</p>
     *
     * @return момент окончания ранга; {@code 0} — ранг не выдан
     */
    private long grantRankProduct(String playerName, ShopProduct product, int days, int bonusDays,
                                  long now) {
        int level = product.rank();
        RegistrationStore.PlayerAccount account = access.store().getAccount(playerName).orElse(null);
        boolean permanent = account != null && account.rankPermanent;
        int permanentLevel = permanent ? account.rankLevel : 0;
        int currentLevel = access.store().rankLevel(playerName, now);
        if (permanentLevel >= level || (!permanent && currentLevel > level)) {
            return 0L;
        }
        int totalDays = Math.max(1, days + bonusDays);
        long cap = now + catalog().maxTotalDays() * ShopEntitlements.DAY_MILLIS;
        long until;
        if (!permanent && currentLevel == level) {
            long base = Math.max(now, access.store().rankUntil(playerName));
            until = Math.min(cap, base + totalDays * ShopEntitlements.DAY_MILLIS);
        } else {
            int remaining = 0;
            if (!permanent && currentLevel > 0) {
                long currentUntil = access.store().rankUntil(playerName);
                if (currentUntil > now) {
                    remaining = (int) Math.ceil((currentUntil - now) / (double) ShopEntitlements.DAY_MILLIS);
                }
            }
            int planned = RankLadder.upgradeDays(totalDays, remaining,
                    monthlyPrice(currentLevel), monthlyPrice(level), catalog().maxTotalDays());
            until = now + planned * ShopEntitlements.DAY_MILLIS;
        }
        access.store().grantRankUntil(playerName, now, level, until, "shop:" + product.id());
        return until;
    }

    /**
     * Платёж за ступень ниже текущей: деньги не теряются (1.4.167).
     *
     * <p>Витрина TradeMC присылает только сумму, поэтому игрок с Покровителем
     * вполне может купить Мецената — по ошибке или из любопытства. Ранг не
     * понижается, и без записи в журнал такой платёж просто исчез бы: человек
     * заплатил и не получил ничего. Кладём покупку в очередь ручного разбора
     * ({@code /pass pending}), чтобы владелец вернул деньги или зачёл их сам.</p>
     *
     * @return запись журнала со статусом ожидания
     */
    public RegistrationStore.DonationRecord rejectLowerRank(String playerName, ShopProduct product,
                                                            int currentLevel) {
        long now = System.currentTimeMillis();
        RegistrationStore.DonationRecord record = new RegistrationStore.DonationRecord();
        record.id = access.store().nextShopSaleId();
        record.source = RegistrationStore.DONATION_SOURCE_TRADEMC;
        record.donorName = playerName == null ? "" : playerName;
        record.amountKopecks = Math.max(0, product.priceRubles()) * 100L;
        record.amountRaw = Integer.toString(Math.max(0, product.priceRubles()));
        record.createdAt = now;
        record.detectedAt = now;
        record.status = RegistrationStore.DonationStatus.PENDING_REVIEW.name();
        record.note = "shop:" + product.id() + ":ниже текущей ступени " + currentLevel;
        record.grantedTo = "";
        record.decidedBy = "";
        record.decidedAt = 0L;
        record.days = 0;
        access.store().recordDonation(record);
        access.audit().log(AuditLog.SHOP_BUY_PENDING, "trademc", record.donorName, "",
                AuditLog.details("product", product.id(), "price", product.priceRubles(),
                        "currentLevel", currentLevel));
        logger.warning("Витрина: " + record.donorName + " оплатил «" + product.id() + "» ("
                + product.priceRubles() + " ₽), но у него уже ступень " + currentLevel
                + ". Платёж в очереди /pass pending — верните деньги или зачтите вручную.");
        return record;
    }

    /** Цена месячной карточки ранга — база для пересчёта остатка при повышении. */
    private int monthlyPrice(int level) {
        int price = 0;
        int bestDays = Integer.MAX_VALUE;
        for (ShopProduct product : catalog().products()) {
            if (product.rank() == level && product.days() <= 31 && product.days() < bestDays) {
                price = product.priceRubles();
                bestDays = product.days();
            }
        }
        return price;
    }

    /**
     * Выдаёт один товар: запись срока, группа, разметка и команды.
     *
     * @return момент окончания срока
     */
    private long grantOne(String playerName, ShopProduct product, long now, int days,
                          ShopDiscount discount) {
        long until = access.store().grantEntitlement(
                playerName, product.id(), now, days + discount.bonusFor(days), catalog().maxTotalDays());
        applyGroup(playerName, product, now, until);
        runCommands(playerName, product);
        return until;
    }

    /**
     * Отзывает товар: запись в аккаунте и временную группу LuckPerms.
     *
     * @return {@code true}, если товар был активен
     */
    public boolean revoke(String playerName, String productId, String actor) {
        String canonical = access.store().getAccount(playerName)
                .map(account -> account.playerName)
                .filter(name -> name != null && !name.isBlank())
                .orElse(playerName);
        ShopProduct product = catalog().find(productId).orElse(null);
        boolean removed;
        if (product != null && product.isBundle()) {
            // У набора нет собственной записи: снимаем всё, что он выдал. Время,
            // купленное отдельно, тоже уйдёт — это инструмент возврата, а не пауза.
            removed = false;
            // Набор мог быть куплен до 1.4.112, когда он записывался одной записью.
            removed |= access.store().revokeEntitlement(canonical, product.id());
            for (String partId : product.resolvedIncludes()) {
                removed |= access.store().revokeEntitlement(canonical, partId);
                catalog().find(partId).filter(ShopProduct::hasGroup)
                        .ifPresent(part -> removeGroup(canonical, part));
            }
        } else {
            removed = access.store().revokeEntitlement(canonical, productId);
            if (removed && product != null && product.hasGroup()) {
                removeGroup(canonical, product);
            }
        }
        if (!removed) {
            return false;
        }
        access.audit().log(AuditLog.SHOP_REVOKE,
                actor == null || actor.isBlank() ? "console" : actor,
                canonical, "", AuditLog.details("product", productId));
        Player online = Bukkit.getPlayerExact(canonical);
        if (online != null) {
            String name = product == null ? productId : product.name();
            plugin.messages().send(online, "shop.revoked-player", "name", name);
        }
        logger.info("Товар '" + productId + "' отозван у " + canonical
                + " (" + (actor == null ? "console" : actor) + ").");
        return true;
    }

    /** Активные покупки игрока в порядке каталога. */
    public List<Active> active(String playerName) {
        Map<String, Long> owned = access.store().entitlementsOf(playerName);
        if (owned.isEmpty()) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        List<Active> result = new ArrayList<>();
        for (ShopProduct product : catalog().products()) {
            Long until = owned.get(product.id());
            if (until != null && ShopEntitlements.active(until, now)) {
                result.add(new Active(product, until));
            }
        }
        return result;
    }

    /** Разметка ника из самой дорогой активной покупки; пусто — ник без оформления. */
    public String tagOf(String playerName) {
        ShopProduct best = null;
        for (Active active : active(playerName)) {
            if (!active.product().hasTag()) {
                continue;
            }
            if (best == null || active.product().priceRubles() > best.priceRubles()) {
                best = active.product();
            }
        }
        return best == null ? "" : best.tag();
    }

    /**
     * Досылает временные группы игроку при входе: покупка могла случиться до его первого
     * входа, когда UUID ещё не был известен LuckPerms.
     */
    public void applyPending(Player player) {
        if (player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Active active : active(player.getName())) {
            if (active.product().hasGroup()) {
                applyGroup(player.getName(), active.product(), now, active.untilMillis());
            }
        }
    }

    /** Чистка истёкших записей при старте; возвращает их число. */
    public int cleanupExpired() {
        int removed = access.store().cleanupEntitlements(System.currentTimeMillis());
        if (removed > 0) {
            logger.info("Каталог: удалено истёкших покупок — " + removed + ".");
        }
        return removed;
    }

    /** Печатает проблемы разбора каталога в лог (вызывается при включении и /pass reload). */
    public void logProblems() {
        for (String problem : catalog().problems()) {
            logger.warning("Каталог товаров: " + problem);
        }
    }

    /* --------------------------- применение эффектов --------------------------- */

    private void applyGroup(String playerName, ShopProduct product, long now, long until) {
        if (!product.hasGroup()) {
            return;
        }
        LuckPerms luckPerms = hooks == null ? null : hooks.luckPerms().orElse(null);
        if (luckPerms == null) {
            logger.warning("Товар '" + product.id() + "' требует LuckPerms (группа '"
                    + product.group() + "'), но LuckPerms не найден — покупка сохранена без группы.");
            return;
        }
        UUID uuid = parseUuid(access.store().getAccount(playerName)
                .map(account -> account.uuid)
                .orElse(""));
        if (uuid == null) {
            logger.info("Товар '" + product.id() + "': у " + playerName
                    + " ещё нет UUID — группа будет выдана при первом входе.");
            return;
        }
        String nodeKey = "group." + product.group();
        long millis = Math.max(1_000L, until - now);
        luckPerms.getUserManager().modifyUser(uuid, user -> {
            user.data().clear(node -> node.getKey().equalsIgnoreCase(nodeKey));
            user.data().add(Node.builder(nodeKey).expiry(Duration.ofMillis(millis)).build());
        }).exceptionally(error -> {
            logger.warning("LuckPerms: не удалось выдать группу " + product.group()
                    + " игроку " + playerName + ": " + error.getMessage());
            return null;
        });
    }

    private void removeGroup(String playerName, ShopProduct product) {
        LuckPerms luckPerms = hooks == null ? null : hooks.luckPerms().orElse(null);
        if (luckPerms == null) {
            return;
        }
        UUID uuid = parseUuid(access.store().getAccount(playerName)
                .map(account -> account.uuid)
                .orElse(""));
        if (uuid == null) {
            return;
        }
        String nodeKey = "group." + product.group();
        luckPerms.getUserManager().modifyUser(uuid, user ->
                user.data().clear(node -> node.getKey().equalsIgnoreCase(nodeKey)))
                .exceptionally(error -> {
                    logger.warning("LuckPerms: не удалось снять группу " + product.group()
                            + " у " + playerName + ": " + error.getMessage());
                    return null;
                });
    }

    private void runCommands(String playerName, ShopProduct product) {
        for (String template : product.commands()) {
            String command = template.replace("%player%", playerName);
            if (command.isBlank()) {
                continue;
            }
            if (Bukkit.isPrimaryThread()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            } else {
                Bukkit.getScheduler().runTask(plugin,
                        () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
            }
        }
    }

    /**
     * Настроена ли скидка так, чтобы когда-нибудь сработать: включена в каталоге и
     * Boosty не выключен и не в холостом прогоне. Нужна витрине, чтобы не обещать
     * бонус, которого не будет.
     */
    public boolean bonusAvailable() {
        PluginConfig config = access.config();
        return config.shopDiscount().usable() && config.boosty().enabled() && !config.boosty().dryRun();
    }

    /**
     * Скидка, которая действует для игрока прямо сейчас.
     *
     * <p>Ноль, если скидка выключена в конфиге, Boosty недоступен, игрок не привязан
     * или подписка не подтверждалась дольше окна {@code boosty.perk-grace-hours}.</p>
     */
    public ShopDiscount discountFor(String playerName) {
        ShopDiscount discount = access.config().shopDiscount();
        if (!discount.usable() || playerName == null || playerName.isBlank()) {
            return ShopDiscount.DISABLED;
        }
        BoostyManager boosty = access.boostyManager();
        if (boosty == null || !boosty.perkActive(playerName, access.config().boosty())) {
            return ShopDiscount.DISABLED;
        }
        return discount;
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
