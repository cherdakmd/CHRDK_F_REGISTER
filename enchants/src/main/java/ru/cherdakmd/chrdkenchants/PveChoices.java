package ru.cherdakmd.chrdkenchants;

import java.util.Map;

/** Стабильный прежний выбор по уровню; личный приоритет действует только на реально доступную защиту. */
final class PveChoices {
    static PveEnchant preference(String id) {
        if (id == null || id.length() > 64) return null;
        PveEnchant enchant = PveEnchant.byId(id);
        return enchant != null && enchant.channel == PveEnchant.Channel.GUARD ? enchant : null;
    }
    static PveEnchant choose(PveEnchant.Channel channel, Map<String,Integer> available, String preferred) {
        PveEnchant preference = preference(preferred), best = null;
        for (PveEnchant enchant : PveEnchant.values()) {
            int level = available.getOrDefault(enchant.id, 0);
            if (enchant.channel != channel || level <= 0) continue;
            if (channel == PveEnchant.Channel.GUARD && enchant == preference) return enchant;
            if (best == null || enchant.level(level) > best.level(available.get(best.id))) best = enchant;
        }
        return best;
    }
    private PveChoices() { }
}
