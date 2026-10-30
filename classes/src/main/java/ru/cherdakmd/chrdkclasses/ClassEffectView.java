package ru.cherdakmd.chrdkclasses;

/** Пассив и таланты отдельно; эффективная сумма совпадает с игровым расчётом. */
record ClassEffectView(EffectKind kind, double passive, double talents, double ceiling) {
    ClassEffectView(EffectKind kind, double passive, double talents) { this(kind,passive,talents,kind.cap()); }
    ClassEffectView {
        if (kind == null || !Double.isFinite(passive) || !Double.isFinite(talents)
                || !Double.isFinite(ceiling) || ceiling < 0 || ceiling > kind.cap() || passive < 0 || talents < 0 || !Double.isFinite(passive + talents)) {
            throw new IllegalArgumentException("Некорректная разбивка эффекта класса");
        }
    }
    double raw() { return passive + talents; }
    double effective() { return Math.min(ceiling, ClassMath.total(kind, raw())); }
    double clipped() {
        double difference = raw() - effective();
        return difference < 0.000000001 ? 0 : difference;
    }
    double gain(double added) {
        if (!Double.isFinite(added) || added < 0) {
            throw new IllegalArgumentException("Некорректная прибавка таланта");
        }
        return Math.max(0, Math.min(ceiling, ClassMath.total(kind, raw() + added)) - effective());
    }
}
