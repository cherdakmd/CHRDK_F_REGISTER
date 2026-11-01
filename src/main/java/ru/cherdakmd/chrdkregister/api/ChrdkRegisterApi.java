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

    /** Версия контракта API. API 33: мосты внешних интеграций (слушатели безопасности, уведомлений и глобального чата с фильтром получателей, бонус длительности покупок, заморозка входа и сброс сессий без смены пароля); API 32: общий владелец непрерывных пассивных зелий PassivePotions; API 30: общий helper одноразового согласия с котировкой; API 29: точный остаток выручки магазина; API 28: индекс членства кланов; API 27: снят источник оформления удалённого аддона; API 26: сняты контракты удалённых аддонов; API 25: durable checkpoint игровых наград; API 24 — Claims domain settlement. */
    int API_VERSION = 34;

    /** Версия основного плагина, например {@code "1.4.6"}. */
    String pluginVersion();

    /** API 25: durable current Core gameplay data; false never authorizes an addon reward ACK. */
    default boolean checkpointGameplayRewards() { return false; }


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
    /** Durable DiamondShop protocol; frozen source intent must precede this call. */
    /** API 24: Claims source WAL precedes bank-only atomic domain settlement. */
    default boolean claimSourceRegistered(UUID lineage) { return false; }
    default boolean registerClaimSource(UUID lineage) { return false; }
    default ClaimExchange.Status claimExchangeStatus(ClaimExchange t) { return ClaimExchange.Status.UNAVAILABLE; }
    default ClaimExchange.Status commitClaimExchange(ClaimExchange t) { return ClaimExchange.Status.UNAVAILABLE; }
    default boolean fundClaimPayment(Player payer,long cost) { return false; }
    default boolean shopTradingAvailable(Player player) { return false; }
    default boolean legacyShopImported(java.util.UUID owner) { return false; }
    default boolean shopRegistered(java.util.UUID market) { return false; }
    default boolean registerShop(java.util.UUID market) { return false; }
    default ShopExchange.Status shopExchangeStatus(ShopExchange trade) { return ShopExchange.Status.UNAVAILABLE; }
    default ShopExchange.Status commitShopExchange(Player player,ShopExchange trade) { return ShopExchange.Status.UNAVAILABLE; }

    default boolean exchangeDiamonds(Player player, long debit, long credit,
                                     org.bukkit.inventory.ItemStack[] expected, org.bukkit.inventory.ItemStack[] replacement) { return false; }

    /** Одноразовый перенос именной казны Claims; без известного UUID/включённого банка — отказ. */
    default boolean importLegacyClaims(String name, long amount) { return false; }

    /** Личный баланс по проверенному UUID из банка/кеша профилей, без сетевого поиска имени (API v14). */
    default long claimBankBalance(String name) { return 0L; }

    /** Доверенная сделка Claims. payer=null — offline-only, без доступа к инвентарю. */
    default boolean exchangeClaimAccounts(java.util.Map<String, Long> deltas, Player payer,
            org.bukkit.inventory.ItemStack[] expected, org.bukkit.inventory.ItemStack[] replacement) { return false; }

    /** API v17: статус долговечного расхода; false commit не является доказательством неоплаты. */
    /** API 18: authoritative high-water, -1 при неизвестном источнике/неисправном банке. */
    default long bankChargeSequence(String source, UUID lineage) { return -1L; }
    default BankCharge.Status bankChargeStatus(BankCharge payment) { return BankCharge.Status.UNAVAILABLE; }
    default boolean commitBankCharge(Player payer, BankCharge payment, org.bukkit.inventory.ItemStack[] expected) { return false; }

    /** API v16. Накопительные доходы: источник сохраняет total ДО вызова; повтор не начисляет delta ещё раз. */
    /** API 31: только bank-only WAL гильдий. Взыскание долга обходит его собственный резерв. */
    default boolean commitGuildBankCharge(BankCharge payment, boolean collectingDebt) { return false; }
    /** Доступный расходуемый остаток, без зарезервированного штрафного долга. */
    default long spendableBankBalance(UUID owner) { return -1; }

    default boolean personalBankAvailable() { return false; }
    default boolean registerIncomeSource(String source, UUID lineage) { return false; }
    default boolean incomeSourceAvailable(String source, UUID lineage) { return false; }
    /** API 29: неоплаченная точная доля выручки; null — банк недоступен. */
    default String shopSaleRemainder(UUID owner) { return null; }
    default UUID knownBankPlayer(String name) { return null; }
    /** API 20: UUID уже известен банку/серверу. Не создаёт игрока по одному имени/неизвестному UUID. */
    default boolean canReceiveBankIncome(UUID owner) { return false; }
    default long creditedEntitlement(String source, UUID lineage, String key, UUID owner) { return -1L; }
    /** true — total зачислен полностью. false: выплата могла быть частичной/неизвестной; нельзя откатывать source total. */
    default boolean creditEntitlement(String source, UUID lineage, String key, UUID owner, long total) { return false; }

    /** API v15: -1 при недоступном банке; UUID из достоверного ledger аукциона. */
    default boolean auctionBankAvailable() { return false; }
    default long auctionBankBalance(UUID player) { return -1L; }
    default boolean importLegacyAuction(UUID market, UUID player, long amount) { return false; }
    default AuctionPayment.Status auctionPaymentStatus(AuctionPayment payment) { return AuctionPayment.Status.UNAVAILABLE; }
    default boolean commitAuctionPayment(Player payer, AuctionPayment payment, org.bukkit.inventory.ItemStack[] expected) { return false; }

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
     * сделать ник жирным, добавить метку гильдии и т.п.</p>
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
     * @param id       уникальное имя аддона, например {@code guilds}
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

    /* ------------------- безопасность и внешние интеграции (API v32) ------------------- */

    /**
     * Слушатель событий безопасности аккаунта и предупреждений об истечении ранга (API v32).
     *
     * <p>Передаётся только маскированный IP; пароли и секреты 2FA наружу не выдаются.</p>
     */
    /** API34: доставка кода только в ЛС закреплённому профилю, ACK не означает проверку кода. */
    interface VkCodeDelivery {
        void sendVkCode(long vkUserId, String code, String purpose, java.util.function.Consumer<Boolean> ack);
    }
    default void registerVkCodeDelivery(VkCodeDelivery delivery) { }
    default void unregisterVkCodeDelivery(VkCodeDelivery delivery) { }
    default boolean vkTwoFactorEnabled(String playerName) { return false; }
    default boolean requestVkTwoFactor(org.bukkit.entity.Player player, long vkUserId, boolean enable) { return false; }
    default boolean confirmVkTwoFactor(org.bukkit.entity.Player player, long vkUserId, String code) { return false; }

    interface SecurityListener {
        /** Успешное завершение входа ({@code password}, {@code session}, {@code 2fa-enrolled} и т.п.). */
        default void onLoginSuccess(String playerName, String maskedIp, String method) {
        }

        /** Предупреждение о переборе пароля на аккаунте. */
        default void onBruteForceAlert(String playerName, String maskedIp, int failures, boolean blocked) {
        }

        /** Предупреждение о скором окончании срока ранга. */
        default void onPassExpiring(String playerName, long passUntilMillis, long remainingMinutes) {
        }
    }

    /** Подписывает аддон на события входа, перебора пароля и истечения ранга (API v32). */
    default void registerSecurityListener(String id, SecurityListener listener) {
    }

    /** Снимает подписку {@link #registerSecurityListener(String, SecurityListener)} (API v32). */
    default void unregisterSecurityListener(String id) {
    }

    /** Слушатель исходящих административных уведомлений {@code NotificationService} (API v32). */
    @FunctionalInterface
    interface NotificationListener {
        void onNotification(String event, String title, String description, String severity);
    }

    /** Подписывает аддон на административные уведомления ядра и других аддонов (API v32). */
    default void registerNotificationListener(String id, NotificationListener listener) {
    }

    /** Снимает подписку {@link #registerNotificationListener(String, NotificationListener)} (API v32). */
    default void unregisterNotificationListener(String id) {
    }

    /**
     * Внешний источник процентной прибавки к сроку покупок каталога (например, VK Donut) (API v32).
     *
     * <p>Процент ограничивается потолком {@code ShopDiscount.MAX_PERCENT} и не складывается с Boosty:
     * применяется наибольший активный процент подписчика.</p>
     */
    interface ShopBonusProvider {
        default boolean available() {
            return true;
        }

        int bonusPercent(String playerName);
    }

    /** Регистрирует провайдер бонусных дней каталога (API v32). */
    default void registerShopBonusProvider(String id, ShopBonusProvider provider) {
    }

    /** Удаляет провайдер бонусных дней каталога (API v32). */
    default void unregisterShopBonusProvider(String id) {
    }

    /** Заморожен ли вход в аккаунт владельцем через внешний привязанный канал (API v32). */
    default boolean isLoginFrozen(String playerName) {
        return false;
    }

    /**
     * Замораживает или размораживает вход в зарегистрированный аккаунт (API v32).
     *
     * <p>При заморозке также сбрасываются активные IP-сессии и завершается текущий сеанс
     * онлайн-игрока. Пароль и 2FA при этом не изменяются.</p>
     */
    default boolean setLoginFrozen(String playerName, boolean frozen, String source) {
        return false;
    }

    /** Сбрасывает все сохранённые сессии аккаунта и отключает активный сеанс на сервере (API v32). */
    default boolean terminateSessions(String playerName, String source) {
        return false;
    }

    /** Количество сохранённых IP-сессий аккаунта (API v32). */
    default int activeSessionCount(String playerName) {
        return 0;
    }

    /**
     * Слушатель принятых глобальных сообщений игрового чата для внешних мостов
     * (например, беседы сообщества ВКонтакте) (API v32).
     */
    interface GlobalChatListener {
        void onGlobalChat(String playerName, String plainMessage);
    }

    /** Регистрирует слушатель глобального чата (API v32). */
    default void registerGlobalChatListener(String id, GlobalChatListener listener) {
    }

    /** Удаляет слушатель глобального чата (API v32). */
    default void unregisterGlobalChatListener(String id) {
    }

    /**
     * Доставляет сообщение из внешнего моста (например, беседы сообщества ВКонтакте)
     * в глобальный чат сервера с применением проверки мута, заморозки аккаунта,
     * фильтра слов и ссылок, анти-капса, {@code /ignore} и журнала чата (API v32).
     *
     * @param sourceId       идентификатор аддона-источника (например, {@code "vk"})
     * @param badgeMini      MiniMessage-метка источника (например, {@code "<#0077FF>[ВК]</#0077FF>"})
     * @param linkedPlayer   привязанный ник игрока (или {@code null}/пусто для гостя беседы)
     * @param fallbackSender отображаемое имя отправителя, если {@code linkedPlayer} не задан
     * @param rawMessage     исходный текст сообщения
     * @return {@code true}, если сообщение прошло модерацию и отправлено в глобальный чат
     */
    default boolean deliverExternalGlobalChat(String sourceId, String badgeMini, String linkedPlayer,
                                              String fallbackSender, String rawMessage) {
        return false;
    }

    /**
     * Перегрузка {@link #deliverExternalGlobalChat(String, String, String, String, String)}
     * с дополнительным фильтром получателей по нику (например, личное скрытие моста чата {@code /vk chat}) (API v32).
     */
    default boolean deliverExternalGlobalChat(String sourceId, String badgeMini, String linkedPlayer,
                                              String fallbackSender, String rawMessage,
                                              java.util.function.Predicate<String> recipientFilter) {
        return deliverExternalGlobalChat(sourceId, badgeMini, linkedPlayer, fallbackSender, rawMessage);
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
