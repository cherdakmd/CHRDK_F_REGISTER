package ru.cherdakmd.chrdkskills;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Раздел навыков в {@code /profile} (1.4.156).
 *
 * <p>Суммарный уровень и лучший навык: по ним видно стиль игры — копатель,
 * боец или ремесленник. Опыт читается из хранилища по UUID, поэтому раздел
 * работает и для оффлайн-игрока.</p>
 */
final class SkillsProfile implements ChrdkRegisterApi.ProfileProvider {

    private final SkillsPlugin plugin;

    SkillsProfile(SkillsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public int order() {
        return 40;
    }

    @Override
    public ChrdkRegisterApi.ProfileSection section(ChrdkRegisterApi.ProfileContext context) {
        if (context == null || plugin.store() == null || plugin.skills() == null) {
            return null;
        }
        Map<SkillType, Long> xp = plugin.store().allXp(context.playerId().toString());
        if (xp.isEmpty()) {
            return null;
        }
        SkillCurve.Params curve = plugin.skills().curve();
        long totalXp = 0L;
        int totalLevel = 0;
        SkillType best = null;
        long bestXp = 0L;
        int bestLevel = 0;
        for (Map.Entry<SkillType, Long> entry : xp.entrySet()) {
            long value = Math.max(0L, entry.getValue());
            int level = SkillCurve.levelFor(value, curve);
            totalXp += value;
            totalLevel += level;
            if (value > bestXp) {
                best = entry.getKey();
                bestXp = value;
                bestLevel = level;
            }
        }
        if (totalXp <= 0L || best == null) {
            return null;
        }
        Messages messages = plugin.messages();
        List<Component> lines = new ArrayList<>();
        lines.add(messages.get("profile-level",
                "level", String.valueOf(totalLevel),
                "xp", String.valueOf(totalXp)));
        lines.add(messages.get("profile-best",
                "skill", Messages.trusted(messages.raw("skill." + best.id() + ".name")),
                "level", String.valueOf(bestLevel)));
        lines.add(messages.get("ability-energy", "energy", plugin.abilities().energy(context.playerId()), "max", SkillAbility.MAX_ENERGY));
        return new ChrdkRegisterApi.ProfileSection(messages.raw("profile-title"),
                Material.EXPERIENCE_BOTTLE, lines);
    }
}
