package ru.cherdakmd.chrdkreferrals;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.Optional;

/**
 * Бизнес-логика рефералки: привязки, выплата бонусов по событию выдачи ранга.
 * Все вызовы core API — объекты {@link ChrdkRegisterApi}, полученные из ServicesManager.
 */
public final class ReferralService {

    private final ReferralsPlugin plugin;
    private final ReferralStore store;

    public ReferralService(ReferralsPlugin plugin, ReferralStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    public ReferralStore store() {
        return store;
    }

    /** Попытка привязки приглашённого к пригласившему (сообщения — снаружи). */
    public ReferralRules.LinkError tryLink(String inviter, String invitee) {
        ChrdkRegisterApi api = plugin.coreApi();
        if (api == null) return ReferralRules.LinkError.OK; // обработается сообщением no-api
        boolean inviterRegistered = !plugin.config().requireInviterRegistered()
                || api.isRegistered(inviter);
        ReferralRules.LinkError error = ReferralRules.canLink(
                inviter, invitee,
                api.isTrialOnly(invitee) ? 0 : api.passUntil(invitee),
                store.hasAnyLink(invitee),
                inviterRegistered,
                plugin.config().requireInviterRegistered());
        if (error == ReferralRules.LinkError.OK) {
            store.createLink(invitee, inviter, System.currentTimeMillis());
        }
        return error;
    }

    /**
     * Событие выдачи/продления ранга из ядра. Если это ПЕРВАЯ оплата приглашённого —
     * платим бонусные часы обоим (через api.grantPassHours — событие привязки потребителя повторно
     * не стреляет, потому что link.consumed станет true ДО grant).
     */
    public void onPassGranted(String playerName) {
        ChrdkRegisterApi api = plugin.coreApi();
        if (api == null) return;
        Optional<ReferralStore.RefLink> pending = store.consumeLink(playerName, System.currentTimeMillis());
        if (pending.isEmpty()) return;
        ReferralStore.RefLink link = pending.get();

        int inviterHours = plugin.config().bonusHoursInviter();
        int inviteeHours = plugin.config().bonusHoursInvitee();
        if (inviterHours > 0) {
            api.grantRewardBoostHours(link.inviter, inviterHours, "referral:" + playerName);
        }
        if (inviteeHours > 0) {
            api.grantRewardBoostHours(playerName, inviteeHours, "referral-bonus:" + link.inviter);
        }
        store.recordBonusPaid(link.inviter, inviterHours);

        Player inviteePlayer = Bukkit.getPlayerExact(playerName);
        if (inviteePlayer != null && inviteeHours > 0) {
            plugin.messages().send(inviteePlayer, "ref.bonus-you",
                    "hours", inviteeHours, "inviter", link.inviter);
        }
        Player inviterPlayer = Bukkit.getPlayerExact(link.inviter);
        if (inviterPlayer != null && inviterHours > 0) {
            plugin.messages().send(inviterPlayer, "ref.bonus-inviter",
                    "invitee", playerName, "hours", inviterHours);
        }
        plugin.getLogger().info("Referral bonus: " + playerName + " (inviter: " + link.inviter
                + ", +" + inviterHours + "h / +" + inviteeHours + "h).");
    }

    /** Одноразовая подсказка новичку: никогда не имел ранга и нет привязки. */
    public boolean shouldHint(String playerName) {
        ChrdkRegisterApi api = plugin.coreApi();
        return api != null
                && plugin.config().joinHintEnabled()
                && (api.passUntil(playerName) == 0 || api.isTrialOnly(playerName))
                && !store.hasAnyLink(playerName);
    }
}
