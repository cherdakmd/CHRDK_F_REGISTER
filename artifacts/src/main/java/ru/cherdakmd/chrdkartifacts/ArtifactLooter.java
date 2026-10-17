package ru.cherdakmd.chrdkartifacts;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Выбор артефакта для «природных» источников (сундуки/мобы/рыбалка): сначала бросок
 * стороны (светлая vs проклятая по весу конфига), затем равномерный тираж внутри
 * выбранной стороны. Чистый класс — тестируется без Bukkit.
 */
public final class ArtifactLooter {

    private ArtifactLooter() {
    }

    /**
     * @param goodSideWeightPercent вероятность светлой стороны в процентах (0..100)
     * @param random                источник случайности
     * @return артефакт или null, если нужной стороны в реестре нет (или в свете 0)
     */
    public static ArtifactDef roll(int goodSideWeightPercent, Random random) {
        boolean good = random.nextDouble() * 100.0 < Math.max(0, Math.min(100, goodSideWeightPercent));
        List<ArtifactDef> side = ArtifactRegistry.bySide(
                good ? ArtifactDef.Side.GOOD : ArtifactDef.Side.CURSED);
        if (side.isEmpty()) {
            side = ArtifactRegistry.bySide(
                    good ? ArtifactDef.Side.CURSED : ArtifactDef.Side.GOOD);
        }
        if (side.isEmpty()) {
            return null;
        }
        List<ArtifactDef> pool = new ArrayList<>(side);
        return pool.get(random.nextInt(pool.size()));
    }
}
