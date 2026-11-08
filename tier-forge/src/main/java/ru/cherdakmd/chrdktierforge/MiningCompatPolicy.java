package ru.cherdakmd.chrdktierforge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Чистое правило несовместимости «Карьера» и кастомных чар массовой добычи.
 *
 * <p>Легендарное свойство {@code quarry} («Карьер») даёт колонну
 * {@link ForgeUpgrade.Effect#ZONE_BREAK}. Чары {@code veinminer}
 * («Жилодробитель») и {@code excavator} («Дробитель») из CHRDK_Enchants дают
 * свой режим массовой добычи за тот же удар. Две ветки на одном инструменте
 * складывались, поэтому с 1.5.2 тир-аддон не выдаёт «Карьер» предмету, где
 * уже стоит такая чара, и не награждает такой чарой предмет с «Карьером».</p>
 *
 * <p>Класс не знает ни Bukkit, ни аддона чар: он работает с id. Мост
 * {@code ChrdkEnchantsApi} опрашивается в {@link TierItems}, а обратную
 * границу (наковальня, книга, ролл, copy API) держит CHRDK_Enchants через
 * PDC {@code tier_properties}. Публичный Core API не меняется.</p>
 */
final class MiningCompatPolicy {

    /** Id легендарного свойства тира. */
    static final String QUARRY = "quarry";
    /** Чары группы {@code megamine} в CHRDK_Enchants. */
    static final Set<String> MEGA_MINING = Set.of("veinminer", "excavator");

    private MiningCompatPolicy() {
    }

    /** Чара режима массовой добычи (регистр не важен). */
    static boolean isMegaMining(String id) {
        return id != null && MEGA_MINING.contains(id.trim().toLowerCase(Locale.ROOT));
    }

    /** Есть ли среди кастомных чар режим массовой добычи. */
    static boolean hasMegaMining(Map<String, Integer> customEnchants) {
        if (customEnchants == null || customEnchants.isEmpty()) {
            return false;
        }
        for (String id : customEnchants.keySet()) {
            if (isMegaMining(id)) {
                return true;
            }
        }
        return false;
    }

    /** Какое свойство тира нельзя выдать при таком наборе чар. */
    static List<TierProperty> forbidden(boolean megaMining) {
        if (!megaMining) {
            return List.of();
        }
        TierProperty quarry = TierProperties.byId(QUARRY);
        return quarry == null ? List.of() : List.of(quarry);
    }

    /** Убрать запрещённые свойства из сохранённого набора PDC. */
    static List<TierProperty> without(List<TierProperty> properties, List<TierProperty> forbidden) {
        if (properties == null || properties.isEmpty()) {
            return List.of();
        }
        if (forbidden == null || forbidden.isEmpty()) {
            return properties;
        }
        List<TierProperty> result = new ArrayList<>(properties.size());
        for (TierProperty property : properties) {
            if (!forbidden.contains(property)) {
                result.add(property);
            }
        }
        return result;
    }
}
