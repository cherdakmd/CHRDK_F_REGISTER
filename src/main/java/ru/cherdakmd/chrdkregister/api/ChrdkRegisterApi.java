package ru.cherdakmd.chrdkregister.api;

import org.bukkit.entity.Player;

import java.util.Optional;

/**
 * Публичный API основного плагина CHRDK_F_REGISTER для аддонов (v1.4.6).
 *
 * <p>Аддон — обычный Bukkit-плагин с {@code depend: [CHRDK_F_REGISTER]} (или softdepend) в
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
    int API_VERSION = 1;

    /** Версия основного плагина, например {@code "1.4.6"}. */
    String pluginVersion();

    /* ------------------------------ проходка ------------------------------ */

    /** Есть ли у игрока активная (не истёкшая) проходка. Работает и для оффлайн-ников. */
    boolean hasActivePass(String playerName);

    /** Unix-время (мс) окончания проходки; {@code 0}, если проходки нет. Работает оффлайн. */
    long passUntil(String playerName);

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
