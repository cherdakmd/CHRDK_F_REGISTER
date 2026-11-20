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
        // Перерождения читаем из хранилища по UUID: раздел работает и для
        // оффлайн-игрока. Шкала ступеней (перерождение = 10) кормит дневной
        // лимит алмазов — это прежний контракт DiamondCapMath.
        int rebirths = plugin.store().rebirths(context.playerId().toString());
        int total = rebirths * 10;
        if (rebirths > 0) {
            lines.add(messages.get("profile-rebirth",
                    "count", String.valueOf(rebirths),
                    "title", Messages.trusted(RebirthMath.title(rebirths))));
        }
        // Персональный дневной лимит дропа: ранг, престиж, уровни и донатные
        // бусты читаются по имени, поэтому строка работает и для оффлайн-игрока.
        if (context.self()) {
            int personalCap = plugin.diamondDailyCap(context.playerName(), totalLevel, total);
            int usedToday = plugin.store().diamondDropsToday(
                    context.playerId().toString(), DiamondDrops.today());
            lines.add(messages.get("profile-diamond-cap",
                    "used", String.valueOf(usedToday), "cap", String.valueOf(personalCap)));
            if (context.online()) {
                lines.add(messages.get("ability-energy", "energy", plugin.abilities().energy(context.playerId()), "max", SkillAbility.MAX_ENERGY));
            }
        }
        return new ChrdkRegisterApi.ProfileSection(messages.raw("profile-title"),
                Material.EXPERIENCE_BOTTLE, lines);
    }
}
