package ru.cherdakmd.chrdkregister;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.node.Node;

import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Синхронизация группы LuckPerms с активной проходкой (1.4.6).
 *
 * <p>Если задано {@code integrations.pass-group}, игроку при выдаче проходки добавляется
 * узел {@code group.<имя>}, а при отзыве и (при {@code integrations.pass-group-remove-expired})
 * истечении — снимается. Операции выполняются асинхронно через {@code modifyUser} и работают
 * и для оффлайн-игроков.</p>
 *
 * <p>С {@code integrations.pass-group-paid-only: true} группа — признак реальной покупки:
 * тестовый период при регистрации и {@code /pass testpay} её не выдают.</p>
 */
public final class LuckPermsPassSync {

    private final ExternalHooks hooks;
    private final RegistrationStore store;
    private final Supplier<PluginConfig> config;
    private final Logger logger;

    public LuckPermsPassSync(ExternalHooks hooks, RegistrationStore store,
                             Supplier<PluginConfig> config, Logger logger) {
        this.hooks = hooks;
        this.store = store;
        this.config = config;
        this.logger = logger;
    }

    /** Имя узла LuckPerms для группы (чистая функция — покрыта юнит-тестами). */
    public static String groupNodeName(String group) {
        return "group." + group.trim();
    }

    /** Настроенная группа или {@code null}, если синхронизация выключена. */
    private String configuredGroup() {
        String group = config.get().passGroup();
        return group == null || group.isBlank() ? null : group;
    }

    /**
     * Чистая политика выдачи группы: тестовый режим не даёт группу, если включён
     * {@code integrations.pass-group-paid-only}. Покрыто юнит-тестами.
     */
    public static boolean grantsGroup(boolean paidGrant, boolean paidOnly) {
        return paidGrant || !paidOnly;
    }

    /** Выдать синхронизируемую группу (реальная покупка, ручная выдача, Boosty). */
    public void onPassGranted(String playerName) {
        onPassGranted(playerName, true);
    }

    /**
     * Выдать синхронизируемую группу с учётом источника проходки.
     *
     * @param paidGrant {@code false} для тестового режима (trial при регистрации,
     *                  {@code /pass testpay}) — группа тогда не выдаётся
     */
    public void onPassGranted(String playerName, boolean paidGrant) {
        String group = configuredGroup();
        if (group != null && !grantsGroup(paidGrant, config.get().passGroupPaidOnly())) {
            logger.info("LuckPerms: проходка игрока " + playerName + " получена не за покупку "
                    + "(тестовый режим) — группа " + group + " не выдаётся.");
            return;
        }
        apply(playerName, true);
    }

    /** Снять синхронизируемую группу (отзыв проходки). */
    public void onPassRevoked(String playerName) {
        apply(playerName, false);
    }

    /** Снять группу при истечении проходки, если это разрешено настройкой. */
    public void onPassExpired(String playerName) {
        if (config.get().passGroupRemoveExpired()) {
            apply(playerName, false);
        }
    }

    private void apply(String playerName, boolean add) {
        String group = configuredGroup();
        if (group == null) return;
        LuckPerms luckPerms = hooks.luckPerms().orElse(null);
        if (luckPerms == null) {
            logger.warning("integrations.pass-group задан (" + group + "), но LuckPerms не найден — "
                    + "синхронизация пропущена.");
            return;
        }
        store.getAccount(playerName)
                .flatMap(account -> parseUuid(account.uuid))
                .ifPresentOrElse(uuid -> luckPerms.getUserManager().modifyUser(uuid, user -> {
                    if (add) {
                        user.data().add(Node.builder(groupNodeName(group)).build());
                    } else {
                        user.data().remove(Node.builder(groupNodeName(group)).build());
                    }
                }).exceptionally(error -> {
                    logger.warning("LuckPerms: не удалось " + (add ? "выдать" : "снять")
                            + " группу " + group + " игроку " + playerName + ": " + error.getMessage());
                    return null;
                }), () -> logger.fine("LuckPerms: у " + playerName + " нет сохранённого UUID — пропуск."));
    }

    private static java.util.Optional<UUID> parseUuid(String value) {
        try {
            return value == null || value.isBlank()
                    ? java.util.Optional.empty()
                    : java.util.Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return java.util.Optional.empty();
        }
    }
}
