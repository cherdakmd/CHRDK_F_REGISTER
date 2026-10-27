package ru.cherdakmd.chrdkregister;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import ru.cherdakmd.chrdkregister.api.ApiAuthStage;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.Optional;

/**
 * Реализация публичного API, регистрируемая в {@code ServicesManager} при включении плагина.
 * Тонкая обёртка над {@link AccessManager}: аддоны видят только стабильный контракт.
 */
final class ChrdkRegisterApiImpl implements ChrdkRegisterApi {

    private final RegisterPlugin plugin;
    private final java.util.Set<String> blocked = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private void deny(String operation, String player) {
        if (!blocked.add(operation)) return;
        java.util.logging.Logger logger = plugin == null ? java.util.logging.Logger.getLogger("CHRDK_Core") : plugin.getLogger();
        logger.warning("Blocked unpaid API " + operation + " (first player: " + player + "). Use grantRewardBoostHours for gameplay rewards.");
    }
    private final AccessManager accessManager;
    private final ExternalHooks hooks;
    private final ShopService shop;
    private final NameTags nameTags;
    private final BankStore bank;
    private final ProfileRegistry profiles;

    ChrdkRegisterApiImpl(RegisterPlugin plugin, AccessManager accessManager, ExternalHooks hooks,
                         ShopService shop, NameTags nameTags, BankStore bank,
                         ProfileRegistry profiles) {
        this.plugin = plugin;
        this.accessManager = accessManager;
        this.hooks = hooks;
        this.shop = shop;
        this.nameTags = nameTags;
        this.bank = bank;
        this.profiles = profiles;
    }

    @Override
    public long bankBalance(String playerName) {
        return bank == null ? 0L : bank.balanceOf(playerName);
    }

    @Override
    public long bankSent(String playerName) {
        java.util.UUID id = bank == null ? null : bank.idByName(playerName);
        return id == null ? 0L : bank.sentOf(id);
    }

    @Override
    public long bankReceived(String playerName) {
        java.util.UUID id = bank == null ? null : bank.idByName(playerName);
        return id == null ? 0L : bank.receivedOf(id);
    }

    @Override
    public String pluginVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean hasActivePass(String playerName) {
        return accessManager.store().hasActivePass(playerName, System.currentTimeMillis());
    }

    @Override
    public int rankLevel(String playerName) {
        return accessManager.store().rankLevel(playerName, System.currentTimeMillis());
    }

    @Override
    public String rankId(String playerName) {
        int level = rankLevel(playerName);
        if (level <= 0) {
            return "";
        }
        RankTier tier = accessManager.config().ranks().tier(level);
        return tier == null ? "" : tier.id();
    }

    @Override
    public long rankUntil(String playerName) {
        return accessManager.store().rankUntil(playerName);
    }

    @Override
    public boolean hasRank(String playerName, int minLevel) {
        return rankLevel(playerName) >= Math.max(1, minLevel);
    }

    @Override
    public ru.cherdakmd.chrdkregister.api.RankBonus rankBonus(String playerName) {
        return accessManager.config().ranks().bonus(rankLevel(playerName));
    }

    @Override
    public void grantRank(String playerName, int level, int days, String source) {
        deny("grantRank", playerName);
    }

    @Override
    public void grantRankPermanent(String playerName, int level, String source) {
        deny("grantRankPermanent", playerName);
    }

    @Override
    public boolean revokeRank(String playerName) {
        return accessManager.store().revokeRank(playerName);
    }

    @Override
    public long passUntil(String playerName) {
        return accessManager.store().getAccount(playerName)
                .map(account -> account.passUntil)
                .orElse(0L);
    }

    @Override
    public boolean isTrialOnly(String playerName) {
        return accessManager.store().getAccount(playerName).map(RegistrationStore.PlayerAccount::isTrialOnly).orElse(false);
    }

    @Override
    public boolean isRegistered(String playerName) {
        return accessManager.store().isRegistered(playerName);
    }

    @Override
    public boolean isTwoFactorEnabled(String playerName) {
        return accessManager.store().isTotpEnabled(playerName);
    }

    @Override
    public ApiAuthStage stageOf(Player player) {
        AccessManager.PlayerStage stage = accessManager.getStage(player);
        try {
            return ApiAuthStage.valueOf(stage.name());
        } catch (IllegalArgumentException e) {
            return ApiAuthStage.NO_PASS; // неизвестная новая стадия = безопасный минимум
        }
    }

    @Override
    public boolean isAuthenticated(Player player) {
        return accessManager.getStage(player) == AccessManager.PlayerStage.AUTHENTICATED;
    }

    @Override
    public void grantPass(String playerName, int days, String source) {
        deny("grantPass", playerName);
    }

    @Override
    public void grantPassHours(String playerName, int hours, String source) {
        accessManager.grantPassHours(playerName, hours,
                source == null || source.isBlank() ? "addon" : source);
    }

    @Override
    public void grantRewardBoostHours(String playerName, int hours, String source) {
        plugin.rewardBoosts().grant(playerName, hours, source);
    }

    @Override
    public long rewardBoostRemainingMillis(String playerName) {
        return accessManager.store().rewardBoostRemaining(playerName);
    }

    @Override
    public long boostExperience(String playerName, String channel, long base, long current) {
        return plugin.rewardBoosts().experience(playerName, channel, base, current);
    }

    @Override
    public boolean revokePass(String playerName) {
        return accessManager.revokePass(playerName);
    }

    @Override
    public long entitlementUntil(String playerName, String productId) {
        return accessManager.store().entitlementUntil(playerName, productId);
    }

    @Override
    public int entitlementCount(String playerName, String productId) {
        return accessManager.store().entitlementCount(playerName, productId, System.currentTimeMillis());
    }

    @Override
    public long grantEntitlement(String playerName, String productId, int days, String source) {
        deny("grantEntitlement", playerName);
        return 0L;
    }

    @Override
    public boolean hasEntitlement(String playerName, String productId) {
        return entitlementUntil(playerName, productId) > System.currentTimeMillis();
    }

    @Override
    public void registerNameTag(String id, ChrdkRegisterApi.NameTagProvider provider) {
        nameTags.register(id, provider);
    }

    @Override
    public void unregisterNameTag(String id) {
        nameTags.unregister(id);
    }

    @Override
    public void registerProfile(String id, ChrdkRegisterApi.ProfileProvider provider) {
        if (profiles != null) {
            profiles.register(id, provider);
        }
    }

    @Override
    public void unregisterProfile(String id) {
        if (profiles != null) {
            profiles.unregister(id);
        }
    }

    @Override
    public void notifyEvent(String event, String title, String description, String severity) {
        accessManager.notifications().notify(event, title, description, severity);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> Optional<T> economy() {
        return (Optional<T>) hooks.economy();
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> Optional<T> permissions() {
        return (Optional<T>) hooks.permissions();
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> Optional<T> luckPerms() {
        return (Optional<T>) hooks.luckPerms();
    }
}
