package ru.cherdakmd.chrdkclasses;

/** Пассив и таланты отдельно; эффективная сумма совпадает с игровым расчётом. */
record ClassEffectView(EffectKind kind, double passive, double talents) {
    ClassEffectView {
        if (kind == null || !Double.isFinite(passive) || !Double.isFinite(talents)
                || passive < 0 || talents < 0 || !Double.isFinite(passive + talents)) {
            throw new IllegalArgumentException("Некорректная разбивка эффекта класса");
        }
    }
    double raw() { return passive + talents; }
    double effective() { return ClassMath.total(kind, raw()); }
    double clipped() { return Math.max(0, raw() - effective()); }
    double gain(double added) {
        if (!Double.isFinite(added) || added < 0) {
            throw new IllegalArgumentException("Некорректная прибавка таланта");
        }
        return Math.max(0, ClassMath.total(kind, raw() + added) - effective());
    }
}
