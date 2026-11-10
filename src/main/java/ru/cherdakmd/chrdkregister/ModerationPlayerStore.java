package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import ru.cherdakmd.chrdkregister.storage.PlayerDataFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Редактируемые {@code players/warns.yml} и {@code players/bans.yml}.
 *
 * <p>Роли модераторов и журнал остаются в {@code moderation.json} с SHA-256.
 * Варны и баны вынесены в отдельные YAML без контрольной суммы: владелец
 * может исправить запись вручную при остановленном сервере. Битый файл уходит
 * в {@code .broken} и не загружается; запись обоих файлов атомарная.</p>
 */
public final class ModerationPlayerStore {

    public static final int SCHEMA = 1;
    private static final String TAG = "[CHRDK_Core]";
    private static final String WARNS_HEADER = """
            # CHRDK_Core — варны игроков.
            # Редактируйте при ОСТАНОВЛЕННОМ сервере: плагин перезапишет файл
            # при следующем действии. Игрок/автор — ник в нижнем регистре.
            # Снятие варна НЕ удаляет строку: заполните removed-by и removed-at.
            # at / removed-at / id / next-warn-id — целые числа; время в мс.
            """;
    private static final String BANS_HEADER = """
            # CHRDK_Core — зеркало ванильного BanList для админ-панели.
            # Редактируйте при ОСТАНОВЛЕННОМ сервере. Ключ — ник в нижнем регистре.
            # reason — причина, by — автор, at — время выдачи в мс.
            # Этот файл не заменяет ванильный BanList и не содержит паролей.
            """;

    public static final String README = """
            # Данные игроков CHRDK_Core

            `warns.yml` — активные и снятые варны, `bans.yml` — зеркало банов для
            админ-панели. Роли модераторов и журнал остаются в
            `../moderation.json` и защищены SHA-256.

            Правила ручной правки:

            1. Остановите сервер, иначе следующее сохранение перезапишет YAML.
            2. Ники пишите в нижнем регистре; время и id — целые числа в мс.
            3. Снятый варн не удаляйте: укажите `removed-by` и `removed-at`.
            4. Сохраните UTF-8 без BOM. Нечитаемый YAML уйдёт в `*.yml.broken`,
               а панель стартует без варнов/банов до исправления файла.

            Резервная копия исходного `moderation.json` создаётся автоматически
            при первом запуске этой версии:
            `moderation.json.before-players-split-*.bak`.
            """;

    /** Одна строка истории варнов в YAML. */
    public static final class Warn {
        public long id;
        public String player = "";
        public String issuer = "";
        public String reason = "";
        public long at;
        public String removedBy = "";
        public long removedAt;
    }

    /** Одна запись бана в YAML; ключ карты — канонический ник. */
    public static final class Ban {
        public String player = "";
        public String reason = "";
        public String by = "";
        public long at;
    }

    public static final class Document {
        public int schema = SCHEMA;
        public long nextWarnId = 1L;
        public List<Warn> warns = new ArrayList<>();
        public Map<String, Ban> bans = new LinkedHashMap<>();
    }

    public record Result(Document document, boolean failed, boolean missing) { }

    private final Path warnsFile;
    private final Path bansFile;
    private final Logger logger;

    public ModerationPlayerStore(Path dataFolder, Logger logger) {
        Path folder = PlayerDataFiles.folder(dataFolder);
        this.warnsFile = folder.resolve("warns.yml");
        this.bansFile = folder.resolve("bans.yml");
        this.logger = logger;
    }

    public Path warnsFile() {
        return warnsFile;
    }

    public Path bansFile() {
        return bansFile;
    }

    public static String canonical(String name) {
        return ModerationLedger.key(name);
    }

    public static Document fromState(ModerationLedger.State state) {
        Document document = new Document();
        if (state == null) return document;
        document.nextWarnId = Math.max(1L, state.nextWarnId);
        if (state.warns != null) {
            for (ModerationLedger.Warn source : state.warns) {
                if (source == null || canonical(source.target).isEmpty()) continue;
                Warn warn = new Warn();
                warn.id = source.id;
                warn.player = canonical(source.target);
                warn.issuer = canonical(source.issuer);
                warn.reason = source.reason == null ? "" : source.reason;
                warn.at = source.at;
                warn.removedBy = canonical(source.removedBy);
                warn.removedAt = source.removedAt;
                document.warns.add(warn);
            }
        }
        if (state.bans != null) {
            for (ModerationLedger.Ban source : state.bans) {
                if (source == null || canonical(source.target).isEmpty()) continue;
                Ban ban = new Ban();
                ban.player = canonical(source.target);
                ban.reason = source.reason == null ? "" : source.reason;
                ban.by = canonical(source.by);
                ban.at = source.at;
                document.bans.put(ban.player, ban);
            }
        }
        return document;
    }

    public static void applyTo(ModerationLedger.State state, Document document) {
        if (state == null) return;
        state.playerDataSchema = ModerationLedger.PLAYER_DATA_SCHEMA;
        state.warns = new ArrayList<>();
        state.bans = new ArrayList<>();
        if (document == null) {
            state.nextWarnId = 1L;
            return;
        }
        long maxId = 0L;
        for (Warn source : document.warns) {
            ModerationLedger.Warn warn = new ModerationLedger.Warn();
            warn.id = source.id;
            warn.target = canonical(source.player);
            warn.issuer = canonical(source.issuer);
            warn.reason = source.reason;
            warn.at = source.at;
            warn.removedBy = canonical(source.removedBy);
            warn.removedAt = source.removedAt;
            state.warns.add(warn);
            maxId = Math.max(maxId, warn.id);
        }
        for (Ban source : document.bans.values()) {
            ModerationLedger.Ban ban = new ModerationLedger.Ban();
            ban.target = canonical(source.player);
            ban.reason = source.reason;
            ban.by = canonical(source.by);
            ban.at = source.at;
            state.bans.add(ban);
        }
        state.nextWarnId = Math.max(maxId + 1L, Math.max(1L, document.nextWarnId));
    }

    public Result load() {
        Result warns = loadWarns();
        Result bans = loadBans();
        if (warns.failed() || bans.failed()) {
            return new Result(new Document(), true, false);
        }
        Document document = new Document();
        document.nextWarnId = Math.max(1L, warns.document().nextWarnId);
        document.warns = warns.document().warns;
        document.bans = bans.document().bans;
        return new Result(document, false, warns.missing() || bans.missing());
    }

    private Result loadWarns() {
        Document document = new Document();
        if (!Files.isRegularFile(warnsFile)) return new Result(document, false, true);
        try {
            YamlConfiguration yaml = readYaml(warnsFile);
            if (yaml.getInt("schema", 0) != SCHEMA || !yaml.isSet("warns")) {
                throw new IllegalStateException("нет схемы или секции warns");
            }
            document.nextWarnId = Math.max(1L, yaml.getLong("next-warn-id", 1L));
            for (Map<?, ?> row : yaml.getMapList("warns")) {
                Warn warn = new Warn();
                warn.id = number(row.get("id"));
                warn.player = canonical(text(row.get("player")));
                warn.issuer = canonical(text(row.get("issuer")));
                warn.reason = text(row.get("reason"));
                warn.at = number(row.get("at"));
                warn.removedBy = canonical(text(row.get("removed-by")));
                warn.removedAt = number(row.get("removed-at"));
                if (!warn.player.isEmpty()) document.warns.add(warn);
            }
            long maxId = 0L;
            for (Warn warn : document.warns) maxId = Math.max(maxId, warn.id);
            document.nextWarnId = Math.max(maxId + 1L, document.nextWarnId);
            return new Result(document, false, false);
        } catch (IOException | RuntimeException failure) {
            return broken(warnsFile, failure);
        }
    }

    private Result loadBans() {
        Document document = new Document();
        if (!Files.isRegularFile(bansFile)) return new Result(document, false, true);
        try {
            YamlConfiguration yaml = readYaml(bansFile);
            if (yaml.getInt("schema", 0) != SCHEMA || !yaml.isSet("bans")) {
                throw new IllegalStateException("нет схемы или секции bans");
            }
            ConfigurationSection section = yaml.getConfigurationSection("bans");
            if (section != null) {
                for (String rawKey : section.getKeys(false)) {
                    String player = canonical(rawKey);
                    if (player.isEmpty()) continue;
                    ConfigurationSection row = section.getConfigurationSection(rawKey);
                    if (row == null) continue;
                    Ban ban = new Ban();
                    ban.player = player;
                    ban.reason = row.getString("reason", "");
                    ban.by = canonical(row.getString("by", ""));
                    ban.at = row.getLong("at", 0L);
                    document.bans.put(player, ban);
                }
            }
            return new Result(document, false, false);
        } catch (IOException | RuntimeException failure) {
            return broken(bansFile, failure);
        }
    }

    private static YamlConfiguration readYaml(Path file) throws IOException {
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        if (raw == null || raw.isBlank()) throw new IllegalStateException("пустой YAML");
        return YamlConfiguration.loadConfiguration(file.toFile());
    }

    private Result broken(Path file, Exception failure) {
        PlayerDataFiles.preserveBroken(file);
        if (logger != null) {
            logger.log(Level.WARNING, TAG + " Не удалось прочитать " + file.getFileName() + ": "
                    + failure.getMessage() + ". Копия сохранена в .broken; состояние стартует пустым.", failure);
        }
        return new Result(new Document(), true, false);
    }

    public boolean save(Document document) {
        if (document == null) return false;
        return saveWarns(document) & saveBans(document);
    }

    private boolean saveWarns(Document document) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema", SCHEMA);
        long maxId = 0L;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Warn warn : document.warns) {
            String player = canonical(warn.player);
            if (player.isEmpty()) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", warn.id);
            row.put("player", player);
            row.put("issuer", canonical(warn.issuer));
            row.put("reason", warn.reason == null ? "" : warn.reason);
            row.put("at", warn.at);
            row.put("removed-by", canonical(warn.removedBy));
            row.put("removed-at", warn.removedAt);
            rows.add(row);
            maxId = Math.max(maxId, warn.id);
        }
        yaml.set("next-warn-id", Math.max(maxId + 1L, Math.max(1L, document.nextWarnId)));
        yaml.set("warns", rows);
        return PlayerDataFiles.write(warnsFile, WARNS_HEADER + yaml.saveToString(), logger, TAG);
    }

    private boolean saveBans(Document document) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema", SCHEMA);
        Map<String, Object> bans = new LinkedHashMap<>();
        for (Map.Entry<String, Ban> entry : document.bans.entrySet()) {
            String player = canonical(entry.getKey());
            Ban ban = entry.getValue();
            if (player.isEmpty() || ban == null) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("reason", ban.reason == null ? "" : ban.reason);
            row.put("by", canonical(ban.by));
            row.put("at", ban.at);
            bans.put(player, row);
        }
        yaml.set("bans", bans);
        return PlayerDataFiles.write(bansFile, BANS_HEADER + yaml.saveToString(), logger, TAG);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return 0L;
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
