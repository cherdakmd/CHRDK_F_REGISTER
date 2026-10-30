package ru.cherdakmd.chrdkclasses;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Замороженные цены дерева 1.4.255: новый каталог не должен менять сумму возврата. */
final class LegacyClassTalents {
    private static final Map<String, Integer> COSTS = costs();

    private LegacyClassTalents() {
    }

    private static Map<String, Integer> costs() {
        Map<String, Integer> result = new LinkedHashMap<>();
        int[] prices = {1, 2, 3, 5, 1};
        for (String prefix : new String[]{
                "warrior-stoyka", "warrior-natisk", "warrior-zemlya",
                "archer-metkost", "archer-skorost", "archer-vyzhivanie",
                "mage-chary", "mage-shchit", "mage-potok",
                "cleric-milost", "cleric-svet", "cleric-doroga"}) {
            for (int tier = 1; tier <= prices.length; tier++) {
                result.put(prefix + "-" + tier, prices[tier - 1]);
            }
        }
        return Map.copyOf(result);
    }

    static int spent(String classId, Set<String> talents) {
        int total = 0;
        for (String id : talents) {
            Integer cost = COSTS.get(id);
            if (cost == null || !id.startsWith(classId + "-")) {
                throw new IllegalArgumentException("Неизвестный или чужой старый талант: " + id);
            }
            total += cost;
        }
        return total;
    }
}
