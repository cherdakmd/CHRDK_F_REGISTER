package ru.cherdakmd.chrdkregister.api;

import org.bukkit.entity.Player;

import java.util.Optional;

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
    int API_VERSION = 7;

    /** Версия основного плагина, например {@code "1.4.6"}. */
    String pluginVersion();

    /* ------------------------------ проходка ------------------------------ */

    /** Есть ли у игрока активная (не истёкшая) проходка. Работает и для оффлайн-ников. */
    boolean hasActivePass(String playerName);

    /** Unix-время (мс) окончания проходки; {@code 0}, если проходки нет. Работает оффлайн. */
    long passUntil(String playerName);

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

    /**
     * Выдаёт (или продлевает) проходку.
     *
     * @param days   длительность в днях; {@code <= 0} — значение {@code pass.grant-days}
     * @param source метка источника, попадёт в аккаунт и аудит (например, имя аддона)
     */
    void grantPass(String playerName, int days, String source);

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
     * Выдаёт покупку каталога как награду: срок, группа и команды товара, без записи
     * продажи в журнале донатов. Нужно аддонам, которые награждают товаром каталога, —
     * например, премиум-трек батлпасса выдаёт косметику.
     *
     * <p>Появился в API v5; реализация по умолчанию ничего не выдаёт и возвращает
     * {@code 0}, поэтому сторонние реализации интерфейса остаются совместимыми.</p>
     *
     * @param days срок в днях; {@code <= 0} — срок из карточки товара
     * @param source метка источника для аудита ({@code battlepass:mission_id})
     * @return момент окончания срока; {@code 0} — товар не выдан
     */
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
