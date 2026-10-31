package ru.cherdakmd.chrdktierforge;

import java.util.Map;

/** Снимок двух бонусов снаряда; предмет/инвентарь стрелка после выстрела не нужен. */
record TierShotBonus(double tierScalar, double snipeScalar) {
    static final TierShotBonus NONE = new TierShotBonus(0, 0);

    static TierShotBonus capture(int tierPercent, Map<ForgeUpgrade.Def, Integer> effects) {
        double snipe = 0;
        for (var entry : effects.entrySet()) {
            if (entry.getKey().effect() == ForgeUpgrade.Effect.SNIPE) {
                snipe += entry.getKey().perLevel() * entry.getValue() / 100.0;
            }
        }
        return new TierShotBonus(TierMath.projectileScalar(tierPercent), snipe);
    }

    double apply(double damage) {
        return damage * (1 + tierScalar) * (1 + snipeScalar);
    }

    String encode() { return "1;" + tierScalar + ";" + snipeScalar; }

    /** Неизвестные/повреждённые данные не превращаются в бонус от текущего оружия. */
    static TierShotBonus decode(String value) {
        if (value == null || value.length() > 100) return NONE;
        String[] parts = value.split(";", -1);
        if (parts.length != 3 || !parts[0].equals("1")) return NONE;
        try {
            double tier = Double.parseDouble(parts[1]);
            double snipe = Double.parseDouble(parts[2]);
            // Все допустимые сочетания текущего каталога укладываются в эти границы.
            if (!Double.isFinite(tier) || !Double.isFinite(snipe)
                    || tier < -0.4 || tier > 1 || snipe < -1 || snipe > 1) return NONE;
            return new TierShotBonus(tier, snipe);
        } catch (NumberFormatException invalid) {
            return NONE;
        }
    }
}
