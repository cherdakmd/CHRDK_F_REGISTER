package ru.cherdakmd.chrdkprofessions;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.ArrayList;
import java.util.List;

/**
 * Раздел «Ремёсла» в {@code /profile} (1.4.282).
 *
 * <p>Выбор профессии отменён: показываем все восемь ремёсел сразу, их уровни
 * и заработанные алмазы. Никаких «выберите ремесло», кулдаунов смены и пустой
 * карточки — profession доступна каждому с первого входа.</p>
 */
final class ProfessionsProfile implements ChrdkRegisterApi.ProfileProvider {

    private final ProfessionsPlugin plugin;

    ProfessionsProfile(ProfessionsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public int order() {
        return 38;
    }

    @Override
    public ChrdkRegisterApi.ProfileSection section(ChrdkRegisterApi.ProfileContext context) {
        if (context == null || plugin.service() == null) {
            return null;
        }
        Messages messages = plugin.messages();
        ProfessionService service = plugin.service();
        long now = System.currentTimeMillis();
        List<Component> lines = new ArrayList<>();
        lines.add(messages.get("profile-all-active",
                "count", String.valueOf(Profession.values().length),
                "xp", String.valueOf(plugin.config().xpPerDiamond()),
                "rank", String.valueOf(Math.round(service.bonus(context.playerId()).xpFactor() * 100))));
        for (Profession profession : Profession.values()) {
            int level = service.level(context.playerId(), profession);
            int percent = service.progressPercent(context.playerId(), profession);
            lines.add(messages.get("profile-profession-line",
                    "profession", Messages.trusted(messages.raw("profession." + profession.id() + ".name")),
                    "level", String.valueOf(level),
                    "percent", String.valueOf(percent)));
        }
        lines.add(messages.get("profile-income-today",
                "amount", String.valueOf(service.incomeToday(context.playerId(), now)),
                "cap", String.valueOf(plugin.config().dailyIncomeCap())));
        lines.add(messages.get("profile-income-total",
                "amount", String.valueOf(service.incomeTotal(context.playerId()))));
        return new ChrdkRegisterApi.ProfileSection(messages.raw("profile-title"),
                Material.DIAMOND, lines);
    }
}
