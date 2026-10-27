package ru.cherdakmd.chrdkprofessions;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.ArrayList;
import java.util.List;

/**
 * Раздел «Профессия» в {@code /profile} (1.4.165).
 *
 * <p>Раздел виден и тем, кто ещё не выбрал ремесло: пустая карточка объясняет,
 * что выбор бесплатный, а смена — раз в сутки. Иначе о механике узнают только
 * по слухам, а профессия выглядит как скрытая привилегия.</p>
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
        Profession profession = plugin.service().active(context.playerId());
        List<Component> lines = new ArrayList<>();
        if (profession == null) {
            lines.add(messages.get("profile-none"));
            lines.add(messages.get("profile-none-hint",
                    "hours", String.valueOf(plugin.config().chooseCooldownHours())));
        } else {
            ProfessionMastery mastery = plugin.store().mastery(context.playerId(), profession);
            lines.add(messages.get("mastery-status", "specialization", mastery == null
                    ? messages.get("mastery-none") : messages.get(mastery.nameKey())));
            int level = plugin.service().level(context.playerId(), profession);
            int maxLevel = plugin.service().maxLevel(context.playerId());
            int needed = ProfessionMath.needed(level, maxLevel);
            lines.add(messages.get("profile-profession", "profession",
                    Messages.trusted(messages.raw("profession." + profession.id() + ".name"))));
            lines.add(messages.get("profile-level",
                    "level", String.valueOf(level),
                    "rank", Messages.trusted(messages.raw(ProfessionMath.rankKey(level)))));
            int percent = plugin.service().progressPercent(context.playerId(), profession);
            lines.add(messages.get("profile-progress",
                    "xp", String.valueOf(plugin.service().progress(context.playerId(), profession)),
                    "needed", needed <= 0 ? "∞" : String.valueOf(needed),
                    "percent", String.valueOf(percent)));
            if (needed > 0) {
                lines.add(messages.get("profile-bar", "bar", ProfessionText.bar(percent)));
            }
            String next = ProfessionText.nextStepRaw(messages, profession, level, maxLevel);
            if (!next.isBlank()) {
                lines.add(messages.get("profile-next", "next", Messages.trusted(next)));
            }
            lines.add(messages.get("profile-perk", "perk",
                    Messages.trusted(ProfessionText.perkRaw(messages, profession, level, maxLevel))));
            lines.add(messages.get("profile-hint",
                    "hint", ProfessionText.hintComponent(messages, profession)));
            int left = plugin.service().dailyLeft(context.playerId(), profession,
                    System.currentTimeMillis());
            if (left >= 0) {
                lines.add(messages.get("profile-daily", "left", String.valueOf(left)));
            }
            long cooldown = plugin.service().chooseCooldownLeft(context.playerId(),
                    System.currentTimeMillis());
            if (cooldown > 0) {
                lines.add(messages.get("profile-cooldown", "time", ProfessionMath.clock(cooldown)));
            }
        }
        return new ChrdkRegisterApi.ProfileSection(messages.raw("profile-title"),
                Material.SMITHING_TABLE, lines);
    }
}
