package ru.cherdakmd.chrdksocial;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import ru.cherdakmd.chrdkregister.storage.PlayerDataFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Редактируемый {@code players/social.yml}: связи ВК/MAX, первый вход, донатный
 * доступ и факт выдачи награды за привязку. Глобальные настройки, чаты и новости
 * остаются в {@code social-links.json} с SHA-256.
 *
 * <p>Файл намеренно человекочитаемый: контрольной суммы нет, чтобы владелец
 * мог исправить связь вручную. Битый YAML не загружается и уходит в
 * {@code social.yml.broken}; включается аварийный режим социального guard'а.
 * Запись атомарная через {@link PlayerDataFiles}.</p>
 */
public final class SocialPlayerStore {

    public static final int SCHEMA = 1;
    private static final String TAG = "[CHRDK_SOCIAL]";
    private static final String HEADER = """
            # CHRDK_VK — данные игроков (привязка ВК/MAX и доступ).
            # Редактируйте при ОСТАНОВЛЕННОМ сервере: при следующем действии плагин
            # перезапишет файл в этом же формате. Ник игрока — в нижнем регистре.
            # link: provider = vk | max, external-id > 0, linked-at = время в мс.
            # profiles: ключ вида vk-123 или max-123, значение — ник игрока.
            # first-seen-at / reward-claimed-at / donor.granted-at — время в мс;
            # 0 или отсутствие поля означает «не задано». Пароли, токены и 2FA
            # в этот файл не пишутся.
            """;

    public static final String README = """
            # Данные игроков CHRDK_VK

            `social.yml` — привязки ВКонтакте/MAX, первый вход, донатный доступ и
            отметка о награде. Глобальные настройки, чаты и новости лежат в
            `../social-links.json` и защищены SHA-256.

            Правила ручной правки:

            1. Остановите сервер. Иначе следующее сохранение перезапишет файл.
            2. Ник игрока пишите в нижнем регистре, `provider` — только `vk` или
               `max`, `external-id` — положительное число.
            3. Ключ в `profiles` имеет вид `vk-123` или `max-123`; значение — ник.
               Не используйте один профиль для двух игроков.
            4. Сохраните UTF-8 без BOM и запустите сервер. Если YAML не читается,
               копия уйдёт в `social.yml.broken`, а привязки не загрузятся.

            Резервная копия исходного `social-links.json` создаётся автоматически
            при первом запуске этой версии: `social-links.json.before-players-move-*.bak`.
            """;

    /** Одна связь с мессенджером. */
    public static final class Link {
        public String provider = "";
        public long externalId;
        public String externalName = "";
        public long linkedAt;
    }

    /** Подтверждённый донат с бессрочным доступом. */
    public static final class Donor {
        public long grantedAt;
        public String source = "";
    }

    /** Все персональные поля одного игрока. */
    public static final class Player {
        public Link link;
        public long firstSeenAt;
        public Donor donor;
        public long rewardClaimedAt;
    }

    /** Снимок файла: игроки и обратный индекс «профиль → игрок». */
    public static final class Document {
        public int schema = SCHEMA;
        public Map<String, Player> players = new LinkedHashMap<>();
        public Map<String, String> profiles = new LinkedHashMap<>();

        public Player player(String name, boolean create) {
            String key = canonical(name);
            if (key.isEmpty()) return null;
            Player found = players.get(key);
            if (found == null && create) {
                found = new Player();
                players.put(key, found);
            }
            return found;
        }
    }

    /** failed=true — файл повреждён; missing=true — его ещё нет на диске. */
    public record Result(Document document, boolean failed, boolean missing) { }

    private final Path file;
    private final Logger logger;

    public SocialPlayerStore(Path dataFolder, Logger logger) {
        this.file = PlayerDataFiles.folder(dataFolder).resolve("social.yml");
        this.logger = logger;
    }

    public Path file() {
        return file;
    }

    public static String canonical(String name) {
        return SocialLinkCodeManager.canonical(name);
    }

    public static String normalizeProvider(String provider) {
        String value = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        return value.equals("vk") || value.equals("max") ? value : "";
    }

    public static String profileKey(String provider, long externalId) {
        return normalizeProvider(provider) + "-" + Math.max(0L, externalId);
    }

    public Result load() {
        if (!Files.isRegularFile(file)) return new Result(new Document(), false, true);
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text == null || text.isBlank()) throw new IllegalStateException("пустой social.yml");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
            if (!yaml.isSet("schema") || yaml.getInt("schema", 0) != SCHEMA) {
                throw new IllegalStateException("неизвестная схема social.yml");
            }
            if (!yaml.isSet("players")) throw new IllegalStateException("нет секции players");
            if (!yaml.isSet("profiles")) throw new IllegalStateException("нет секции profiles");
            Document document = new Document();
            parsePlayers(yaml, document);
            parseProfiles(yaml, document);
            return new Result(document, false, false);
        } catch (IOException | RuntimeException failure) {
            PlayerDataFiles.preserveBroken(file);
            if (logger != null) {
                logger.log(Level.SEVERE, TAG + " Не удалось прочитать players/social.yml: "
                        + failure.getMessage() + ". Копия сохранена в social.yml.broken; "
                        + "включён аварийный режим.", failure);
            }
            return new Result(new Document(), true, false);
        }
    }

    private void parsePlayers(YamlConfiguration yaml, Document document) {
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) return; // players: {} — допустимый пустой реестр.
        for (String rawKey : players.getKeys(false)) {
            String key = canonical(rawKey);
            if (key.isEmpty()) continue;
            ConfigurationSection section = players.getConfigurationSection(rawKey);
            if (section == null) continue;
            Player player = new Player();
            player.firstSeenAt = Math.max(0L, section.getLong("first-seen-at", 0L));
            player.rewardClaimedAt = Math.max(0L, section.getLong("reward-claimed-at", 0L));
            ConfigurationSection link = section.getConfigurationSection("link");
            if (link != null) {
                String provider = normalizeProvider(link.getString("provider", ""));
                long externalId = link.getLong("external-id", 0L);
                if (!provider.isEmpty() && externalId > 0L) {
                    player.link = new Link();
                    player.link.provider = provider;
                    player.link.externalId = externalId;
                    player.link.externalName = link.getString("external-name", "");
                    player.link.linkedAt = Math.max(0L, link.getLong("linked-at", 0L));
                    document.profiles.put(profileKey(provider, externalId), key);
                } else {
                    warn("у игрока " + key + " пропущена неполная связь link");
                }
            }
            ConfigurationSection donor = section.getConfigurationSection("donor");
            if (donor != null) {
                player.donor = new Donor();
                player.donor.grantedAt = Math.max(0L, donor.getLong("granted-at", 0L));
                player.donor.source = donor.getString("source", "");
            }
            if (!isEmpty(player)) document.players.put(key, player);
        }
    }

    private void parseProfiles(YamlConfiguration yaml, Document document) {
        ConfigurationSection profiles = yaml.getConfigurationSection("profiles");
        if (profiles == null) return; // profiles: {} — допустимый пустой индекс.
        for (String rawKey : profiles.getKeys(false)) {
            String key = normalizeProfileKey(rawKey);
            String player = canonical(profiles.getString(rawKey, ""));
            if (!key.matches("(vk|max)-[1-9]\\d*") || player.isEmpty()) {
                warn("пропущен неверный профиль " + rawKey);
                continue;
            }
            document.profiles.putIfAbsent(key, player);
        }
    }

    private static String normalizeProfileKey(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace(':', '-');
    }

    private static boolean isEmpty(Player player) {
        return player == null || (player.link == null && player.donor == null
                && player.firstSeenAt <= 0L && player.rewardClaimedAt <= 0L);
    }

    private void warn(String message) {
        if (logger != null) logger.warning(TAG + " players/social.yml: " + message);
    }

    /** Атомарно записывает документ в читаемом YAML с поясняющей шапкой. */
    public boolean save(Document document) {
        if (document == null) return false;
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema", SCHEMA);
        Map<String, Object> players = new LinkedHashMap<>();
        for (Map.Entry<String, Player> entry : document.players.entrySet()) {
            String key = canonical(entry.getKey());
            Player player = entry.getValue();
            if (key.isEmpty() || isEmpty(player)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            if (player.firstSeenAt > 0L) row.put("first-seen-at", player.firstSeenAt);
            if (player.link != null) {
                Map<String, Object> link = new LinkedHashMap<>();
                link.put("provider", normalizeProvider(player.link.provider));
                link.put("external-id", player.link.externalId);
                link.put("external-name", player.link.externalName == null ? "" : player.link.externalName);
                link.put("linked-at", player.link.linkedAt);
                row.put("link", link);
            }
            if (player.donor != null) {
                Map<String, Object> donor = new LinkedHashMap<>();
                donor.put("granted-at", player.donor.grantedAt);
                donor.put("source", player.donor.source == null ? "" : player.donor.source);
                row.put("donor", donor);
            }
            if (player.rewardClaimedAt > 0L) row.put("reward-claimed-at", player.rewardClaimedAt);
            players.put(key, row);
        }
        yaml.set("players", players);
        Map<String, Object> profiles = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : document.profiles.entrySet()) {
            String key = normalizeProfileKey(entry.getKey());
            String player = canonical(entry.getValue());
            if (!key.matches("(vk|max)-[1-9]\\d*") || player.isEmpty()) continue;
            profiles.put(key, player);
        }
        yaml.set("profiles", profiles);
        String body = yaml.saveToString();
        return PlayerDataFiles.write(file, HEADER + body, logger, TAG);
    }
}
