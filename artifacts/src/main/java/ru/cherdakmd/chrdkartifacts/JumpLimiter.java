package ru.cherdakmd.chrdkartifacts;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Прыжок «Глиняной души» без хака с амплификатором. Исторически прыжок
 * выключали эффектом `jump_boost` с амплификатором 250 — это переполнение
 * знакового байта: на 1.20+ даёт десинк, на 1.21.5 обходится высоким прыжком,
 * а в 1.21.5+ Jump Boost вообще переехал на атрибут `jump_strength`.
 *
 * <p>Здесь то же самое честно: модификатор на атрибут
 * {@code Attribute.JUMP_STRENGTH} ставится пульсом и снимается, когда артефакт
 * убран. Ключ модификатора постоянный, поэтому повторное применение не плодит
 * дубли; если артефакт выключили — снимаем. Модификатор transient: в
 * playerdata он не попадает и после перезахода просто ставится пульсом заново,
 * поэтому «залипнуть» навсегда не может. Модификатор вычитает базовое
 * значение, так что атрибут даёт ровно ноль и не зависит от того, какой прыжок
 * настроен на сервере.</p>
 */
public final class JumpLimiter {

    /** Допуск сравнения сумм модификатора и базы (значение приводится к нулю). */
    static final double EPSILON = 1e-6;

    private final NamespacedKey key;

    public JumpLimiter(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "glinyanaya-dusha");
    }

    /** Включён ли лимит прыжка у игрока прямо сейчас. */
    public boolean isLimited(Player player) {
        AttributeInstance instance = player.getAttribute(Attribute.JUMP_STRENGTH);
        return instance != null && instance.getModifier(key) != null;
    }

    /**
     * Ставит ограничение прыжка. Модификатор вычитает текущее базовое значение,
     * поэтому итог — ровно ноль; при повторном вызове сумма пересчитывается,
     * если базу успел поменять другой плагин. Базовое значение не трогаем.
     *
     * @return удалось ли (атрибут есть в текущей версии сервера)
     */
    public boolean apply(Player player) {
        AttributeInstance instance = player.getAttribute(Attribute.JUMP_STRENGTH);
        if (instance == null) {
            return false;
        }
        double base = instance.getBaseValue();
        AttributeModifier existing = instance.getModifier(key);
        if (existing != null) {
            if (Math.abs(existing.getAmount() + base) < EPSILON) {
                return true;
            }
            instance.removeModifier(key);
        }
        AttributeModifier modifier = new AttributeModifier(key, -base,
                AttributeModifier.Operation.ADD_NUMBER);
        try {
            instance.addTransientModifier(modifier);
            return true;
        } catch (IllegalArgumentException ignored) {
            // модификатор успели добавить между проверкой и вызовом — не страшно
            return instance.getModifier(key) != null;
        }
    }

    /** Снимает ограничение; безопасно вызывать на игроке без модификатора. */
    public void clear(Player player) {
        AttributeInstance instance = player.getAttribute(Attribute.JUMP_STRENGTH);
        if (instance != null) {
            instance.removeModifier(key);
        }
    }
}
