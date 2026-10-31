package ru.cherdakmd.chrdkartifacts;

import java.math.BigDecimal;

/** Числа карточки берём из того же каталога, что фактические характеристики. */
final class ArtifactTraitText {
    private ArtifactTraitText() { }

    static String signedValue(ArtifactTraits.Def def, int level) {
        double value = def.value(level);
        return (value < 0 ? "−" : "+")
                + BigDecimal.valueOf(Math.abs(value)).stripTrailingZeros().toPlainString();
    }

    static String effectLevel(ArtifactTraits.Def def, int level) {
        return ArtifactTraitCodec.roman(def.amplifier(level) + 1);
    }
}
