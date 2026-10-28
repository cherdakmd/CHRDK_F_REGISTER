package ru.cherdakmd.chrdkregister.api;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Публичный API основного плагина CHRDK_Core для аддонов (v1.4.6).
 *
 * <p>Аддон — обычный Bukkit-плагин с {@code depend: [CHRDK_Core]} (или softdepend) в
 * {@code plugin.yml}. Экземпляр API получают через {@code ServicesManager}:</p>
 *
 * <pre>{@code
 * ChrdkRegisterApi api = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
 * if (api != null) { ... }
 * }</pre>
 *
 * <p>Наряду с этим интерфейсом аддоны слушают Bukkit-события пакета
 * {@code ru.cherdakmd.chrdkregister.api.events} (выдача/отзыв/истечение проходки,
 * завершение авторизации). Контракт: интерфейс расширяется только добавлением методов,
 * {@link #API_VERSION} растёт на каждое такое расширение.</p>
 *
 * <p>Методы {@link #economy()}, {@link #permissions()} и {@link #luckPerms()} возвращают
 * {@link Optional#empty()}, когда соответствующий плагин не установлен; типы Vault и
 * LuckPerms присутствуют только в дженериках (стирание типов), поэтому сам API загружается
 * и без этих плагинов.</p>
 */
public interface ChrdkRegisterApi {

    /** Версия контракта API. Растёт при расширении (добавлении методов/событий). */
    int API_VERSION = 14;

    /** Версия основного плагина, например {@code "1.4.6"}. */
    String pluginVersion();

    /* ------------------------------ проходка ------------------------------ */

    /** Есть ли у игрока активная (не истёкшая) проходка. Работает и для оффлайн-ников. */
    boolean hasActivePass(String playerName);

    /** Unix-время (мс) окончания проходки; {@code 0}, если проходки нет. Работает оффлайн. */
    long passUntil(String playerName);

    /* ------------------------------ ранги (v9) ------------------------------ */

    /**
     * Ступень действующего донатного ранга: 0 — ранга нет, 1..5 — лестница.
     * Работает и для оффлайн-ников.
     *
     * <p>Метод появился в API v9. Реализация по умолчанию выводит первую ступень
     * из {@link #hasActivePass(String)}, поэтому сторонние реализации интерфейса
     * остаются совместимыми.</p>
     */
    default int rankLevel(String playerName) {
        return hasActivePass(playerName) ? 1 : 0;
    }

    /** Ид ступени (wanderer, pathfinder, keeper, patron, founder) или пустая строка. */
    default String rankId(String playerName) {
        return "";
    }

    /** Момент окончания ранга; {@code 0} — ранга нет или он выдан навсегда. */
    default long rankUntil(String playerName) {
        return passUntil(playerName);
    }

    /** Есть ли ранг не ниже указанной ступени. */
    default boolean hasRank(String playerName, int minLevel) {
        return rankLevel(playerName) >= Math.max(1, minLevel);
    }

    /** @deprecated Платные ранги игровым API не выдаются. */
    @Deprecated
    default void grantRank(String playerName, int level, int days, String source) {
    }

    /** @deprecated Платные ранги игровым API не выдаются. */
    @Deprecated
    default void grantRankPermanent(String playerName, int level, String source) {
    }

    /** Снимает ранг; {@code true}, если ранг был. */
    default boolean revokeRank(String playerName) {
        return false;
    }

    /**
     * Бонусы действующего ранга: опыт, добыча и дополнительные уровни навыка (v10).
     *
     * <p>Аддон не должен считать проценты сам: числа живут в конфиге ядра, а
     * потолки — в {@link RankBonus}. Реализация по умолчанию возвращает
     * {@link RankBonus#NONE}, поэтому сторонние реализации интерфейса
     * остаются совместимыми и просто не дают бонусов.</p>
     */
    default RankBonus rankBonus(String playerName) {
        return RankBonus.NONE;
    }

    /** True when the account has only received its registration trial (no later non-trial grant).
     * Includes an expired/revoked trial. Default false preserves older API implementations.
     */
    default boolean isTrialOnly(String playerName) { return false; }

    /** Зарегистрирован ли у игрока пароль. Работает оффлайн. */
    boolean isRegistered(String playerName);

    /** Включена ли у игрока TOTP 2FA. Работает оффлайн. */
    boolean isTwoFactorEnabled(String playerName);

    /* ------------------------------ авторизация ------------------------------ */

    /** Текущая стадия онлайн-игрока. */
    ApiAuthStage stageOf(Player player);

    /** {@code true}, если онлайн-игрок полностью авторизован (stage == AUTHENTICATED). */
    boolean isAuthenticated(Player player);

    /* ------------------------------ управление проходкой ------------------------------ */

    /** @deprecated Платные права не выдаются игровым API. Используйте grantRewardBoostHours. */
    @Deprecated
    void grantPass(String playerName, int days, String source);

    /** @deprecated Совместимый адаптер к отдельному бусту; НИКОГДА не продлевает ранг/проходку. */
    @Deprecated
    default void grantPassHours(String playerName, int hours, String source) {
    }

    /** Игровая награда: +10 % XP, только авторизованное онлайн-время, без платных прав. */
    default void grantRewardBoostHours(String playerName, int hours, String source) { }

    /** Остаток отдельного буста; это не Unix timestamp и не срок ранга. */
    default long rewardBoostRemainingMillis(String playerName) { return 0L; }

    /** База до других бонусов и уже начисляемый XP. +10 % аддитивно, общий предел +50 %. */
    default long boostExperience(String playerName, String channel, long base, long current) { return current; }

    /** Отзывает проходку; {@code true}, если проходка была активна. */
    boolean revokePass(String playerName);

    /** Queue an addon notification using the core Discord webhook and notifications.events allowlist.
     * Default no-op keeps third-party API implementations source compatible. No webhook credentials leave the core.
     */
    default void notifyEvent(String event, String title, String description, String severity) { }

    /* --------------------------- покупки каталога --------------------------- */

    /**
     * Срок действия покупки каталога ({@code shop.products}) в миллисекундах Unix.
     * Работает и для оффлайн-ников. {@code 0} — товар не куплен или уже истёк.
     *
     * <p>Метод появился в API v4; значение по умолчанию {@code 0} сохраняет
     * совместимость со сторонними реализациями интерфейса.</p>
     */
    default long entitlementUntil(String playerName, String productId) {
        return 0L;
    }

    /**
     * Число одновременно активных оплаченных пакетов (API v13, только чтение).
     * Старое одиночное право учитывается как один пакет; не умножайте его по дням.
     */
    default int entitlementCount(String playerName, String productId) {
        return hasEntitlement(playerName, productId) ? 1 : 0;
    }

    /** @deprecated Платные товары не выдаются игровым API. Всегда возвращает 0. */
    @Deprecated
    default long grantEntitlement(String playerName, String productId, int days, String source) {
        return 0L;
    }

    /**
     * Активна ли покупка каталога: товар куплен и срок ещё не вышел.
     * Аддоны используют это для своих возможностей — например, CHRDK_Cosmetics
     * открывает по покупке отдельный шлейф.
     */
    default boolean hasEntitlement(String playerName, String productId) {
        return entitlementUntil(playerName, productId) > System.currentTimeMillis();
    }

    /* -------------------------------- банк алмазов ------------------------------- */

    /**
     * Баланс алмазного банка игрока ({@code /bal}); {@code 0} для неизвестного ника.
     *
     * <p>Появился в API v7: аддоны (достижения, квесты) могут строить награды и
     * условия на реальном счёте игрока. Реализация по умолчанию возвращает
     * {@code 0}, поэтому сторонние реализации интерфейса не ломаются.</p>
     */
    default long bankBalance(String playerName) {
        return 0L;
    }

    /** Общий личный счёт по UUID Player, с обновлением индекса ника. Не отдельный кошелёк магазина. */
    default long personalDiamonds(Player player) { return 0L; }

    /** Доверенная серверная миграция старого DiamondShop; receipt сохраняется вместе с балансом. */
    default boolean importLegacyShop(java.util.UUID player, long roundedAmount) { return false; }

    /** Main-thread обмен счёта и полного storage-снимка. Старое ядро отказывает, без fallback-кошелька. */
    default boolean exchangeDiamonds(Player player, long debit, long credit,
                                     org.bukkit.inventory.ItemStack[] expected, org.bukkit.inventory.ItemStack[] replacement) { return false; }

    /** Одноразовый перенос именной казны Claims; без известного UUID/включённого банка — отказ. */
    default boolean importLegacyClaims(String name, long amount) { return false; }

    /** Личный баланс по проверенному UUID из банка/кеша профилей, без сетевого поиска имени (API v14). */
    default long claimBankBalance(String name) { return 0L; }

    /** Доверенная сделка Claims. payer=null — offline-only, без доступа к инвентарю. */
    default boolean exchangeClaimAccounts(java.util.Map<String, Long> deltas, Player payer,
            org.bukkit.inventory.ItemStack[] expected, org.bukkit.inventory.ItemStack[] replacement) { return false; }

    /** Сумма всех переводов игрока (API v7). */
    default long bankSent(String playerName) {
        return 0L;
    }

    /** Сумма всех полученных переводов игрока (API v7). */
    default long bankReceived(String playerName) {
        return 0L;
    }

    /* ------------------------------ оформление ника ------------------------------ */

    /**
     * Оформление ника в чате и личных сообщениях.
     *
     * <p>Провайдер получает ник без разметки и уже оформленное имя (с тегом
     * каталога) и возвращает новое — MiniMessage-строку. Так аддон может
     * сделать ник жирным, добавить метку «СТРИМ» и т.п.</p>
     */
    @FunctionalInterface
    interface NameTagProvider {

        /**
         * @param playerName    ник игрока без разметки
         * @param decoratedName имя, уже оформленное ядром (MiniMessage)
         * @return новое имя; {@code null} — оставить как было
         */
        String decorate(String playerName, String decoratedName);
    }

    /**
     * Добавляет оформление ника (API v6). Ядро применяет провайдеров в порядке
     * регистрации; повторная регистрация с тем же id заменяет прежнюю.
     *
     * <p>По умолчанию ничего не делает: старые реализации API остаются
     * совместимыми. Сбой провайдера логируется и не ломает чат.</p>
     *
     * @param id       уникальное имя аддона, например {@code streams}
     * @param provider сборка имени; {@code null} игнорируется
     */
    default void registerNameTag(String id, NameTagProvider provider) {
    }

    /** Убирает оформление, зарегистрированное аддоном под этим id (API v6). */
    default void unregisterNameTag(String id) {
    }

    /* -------------------------------- профиль -------------------------------- */

    /**
     * Контекст, для которого аддон собирает раздел профиля (API v8).
     *
     * @param playerId   UUID игрока (для оффлайн-профиля — сохранённый или
     *                   вычисленный по нику)
     * @param playerName ник в том виде, в каком его знает сервер
     * @param online     игрок сейчас на сервере
     * @param self       профиль открывает сам игрок
     */
    record ProfileContext(UUID playerId, String playerName, boolean online, boolean self) {
    }

    /**
     * Готовый раздел профиля (API v8): заголовок, иконка и строки описания.
     *
     * <p>Строки — уже собранные компоненты из {@code messages.yml} аддона.
     * Пустой раздел ядро не показывает, поэтому аддон может спокойно вернуть
     * {@code lines = List.of()} для игрока без прогресса.</p>
     *
     * @param title заголовок раздела (MiniMessage)
     * @param icon  материал иконки в меню
     * @param lines строки для меню и чата
     */
    record ProfileSection(String title, Material icon, List<Component> lines) {

        public ProfileSection {
            title = title == null || title.isBlank() ? "?" : title;
            icon = icon == null ? Material.PAPER : icon;
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /**
     * Поставщик раздела профиля (API v8). Аддон регистрирует его в
     * {@code onEnable} и снимает в {@code onDisable}.
     *
     * <p>{@link #order()} задаёт место в меню: меньше — выше. Ядро сортирует
     * разделы по порядку, затем по id; исключение внутри провайдера
     * логируется и не ломает остальные разделы.</p>
     */
    @FunctionalInterface
    interface ProfileProvider {

        /** Место раздела в меню: меньше — выше. */
        default int order() {
            return 100;
        }

        /** Раздел для игрока; {@code null} или пустые строки — раздел скрыт. */
        ProfileSection section(ProfileContext context);
    }

    /**
     * Добавляет раздел в {@code /profile} (API v8). Повторная регистрация с тем
     * же id заменяет прежний раздел, поэтому перезагрузка аддона не плодит
     * дубликаты.
     *
     * <p>По умолчанию ничего не делает: сторонние реализации API остаются
     * совместимыми.</p>
     *
     * @param id       уникальный id аддона, например {@code hunt}
     * @param provider сборка раздела; {@code null} игнорируется
     */
    default void registerProfile(String id, ProfileProvider provider) {
    }

    /** Убирает раздел, зарегистрированный под этим id (API v8). */
    default void unregisterProfile(String id) {
    }

    /* ------------------------------ интеграции ------------------------------ */

    /**
     * Экономика Vault, если плагин Vault установлен и зарегистрировал сервис.
     * Сигнатура: {@code Optional<net.milkbowl.vault.economy.Economy>}.
     */
    <T> Optional<T> economy();

    /**
     * Права Vault, если плагин Vault установлен и зарегистрировал сервис.
     * Сигнатура: {@code Optional<net.milkbowl.vault.permission.Permission>}.
     */
    <T> Optional<T> permissions();

    /**
     * LuckPerms API, если LuckPerms установлен.
     * Сигнатура: {@code Optional<net.luckperms.api.LuckPerms>}.
     */
    <T> Optional<T> luckPerms();
}
