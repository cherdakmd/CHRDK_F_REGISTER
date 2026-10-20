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

    /** Итог выдачи товара — для команды и текстов. */
    public enum Status {
        /** Товар выдан или продлён. */
        GRANTED,
        /** Каталог выключен или пуст. */
        DISABLED,
        /** Такого товара нет в конфиге. */
        UNKNOWN_PRODUCT
    }

    public record Result(Status status, String playerName, ShopProduct product,
                         long untilMillis, int days) {
        public boolean granted() {
            return status == Status.GRANTED;
        }

        public static Result failure(Status status, String playerName) {
            return new Result(status, playerName, null, 0L, 0);
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
     * Выдаёт товар игроку.
     *
     * @param playerName ник покупателя (может быть оффлайн)
     * @param productId  id товара из каталога
     * @param days       переопределение срока; {@code <= 0} — срок из товара
     * @param actor      кто выдал (консоль, RCON, админ) — для аудита
     * @return результат выдачи
     */
    public Result grant(String playerName, String productId, int days, String actor) {
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
        long until = access.store().grantEntitlement(
                playerName, product.id(), now, length, catalog.maxTotalDays());

        String canonical = access.store().getAccount(playerName)
                .map(account -> account.playerName)
                .filter(name -> name != null && !name.isBlank())
                .orElse(playerName);

        applyGroup(canonical, product, now, until);
        runCommands(canonical, product);

        access.store().recordShopSale(canonical, product.id(), product.priceRubles(), now);
        access.audit().log(AuditLog.SHOP_BUY,
                actor == null || actor.isBlank() ? "console" : actor,
                canonical, "",
                AuditLog.details("product", product.id(), "days", length,
                        "until", until, "price", product.priceRubles()));

        Player online = Bukkit.getPlayerExact(canonical);
        if (online != null) {
            plugin.messages().send(online, "shop.received",
                    "name", product.name(),
                    "until", AccessManager.formatTimestamp(until));
        }
        logger.info("Товар '" + product.id() + "' выдан " + canonical + " на " + length
                + " дн. (до " + AccessManager.formatTimestamp(until)
                + ", цена " + product.priceRubles() + " ₽, источник: "
                + (actor == null ? "console" : actor) + ").");
        return new Result(Status.GRANTED, canonical, product, until, length);
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
        boolean removed = access.store().revokeEntitlement(canonical, productId);
        if (!removed) {
            return false;
        }
        catalog().find(productId).filter(ShopProduct::hasGroup)
                .ifPresent(product -> removeGroup(canonical, product));
        access.audit().log(AuditLog.SHOP_REVOKE,
                actor == null || actor.isBlank() ? "console" : actor,
                canonical, "", AuditLog.details("product", productId));
        Player online = Bukkit.getPlayerExact(canonical);
        if (online != null) {
            String name = catalog().find(productId).map(ShopProduct::name).orElse(productId);
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
