package ru.cherdakmd.chrdkclasses;

import com.google.gson.Gson;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.*;
import java.io.IOException;
import java.util.logging.Logger;
import ru.cherdakmd.chrdkregister.storage.SafeJsonWriter;

/** Общие правила фиксируются до первого переноса, в том числе для будущих входов офлайн-игроков. */
final class LegacyRulesFile {
    private LegacyRulesFile() { }
    static LegacyClassRules loadOrCreate(Path folder, ClassConfig config, Logger logger) {
        Path path = folder.resolve("classes-legacy-rules.json");
        try {
            if (Files.notExists(path)) {
                LegacyClassRules rules = LegacyClassRules.capture(config);
                if (!SafeJsonWriter.write(path, new Gson().toJson(rules), logger, "[CHRDK_Classes]"))
                    throw new IOException("Нельзя зафиксировать дообновительные правила");
                return rules;
            }
            if (Files.size(path) > 4096) throw new IOException("Слишком большой снимок правил");
            Map<?,?> row = ClassStorageJson.read(Files.readString(path, StandardCharsets.UTF_8));
            if (!row.keySet().equals(Set.of("version","levelsPerLevel","startingPoints","pointsPerLevel",
                    "achievementEvery","achievementMax","ranksEnabled","enabled"))) throw new IOException("Неизвестная схема правил");
            if (!(row.get("ranksEnabled") instanceof Boolean ranks) || !(row.get("enabled") instanceof Boolean enabled))
                throw new IOException("Некорректные переключатели правил");
            return new LegacyClassRules(number(row,"version"), number(row,"levelsPerLevel"), number(row,"startingPoints"),
                    number(row,"pointsPerLevel"), number(row,"achievementEvery"), number(row,"achievementMax"), ranks, enabled);
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Не удалось сохранить/прочитать исходные правила Classes; перенос остановлен", error);
        }
    }
    private static int number(Map<?,?> row, String key) {
        if (!(row.get(key) instanceof BigDecimal value)) throw new IllegalArgumentException("Не число: "+key);
        return value.intValueExact();
    }
}
