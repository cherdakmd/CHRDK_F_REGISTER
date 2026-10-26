package ru.cherdakmd.chrdkparty;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import ru.cherdakmd.chrdkregister.storage.SafeJsonWriter;

/**
 * Группы в {@code parties.json} (1.4.158).
 *
 * <p>Группы переживают перезапуск сервера: игрок, который ушёл спать, не должен
 * на утро узнать, что его компания распалась. Приглашения сюда не попадают —
 * у них есть срок, и после рестарта они всё равно были бы мусором.</p>
 *
 * <p>Паттерн записи общий для репозитория: мутации на главном потоке,
 * {@link #snapshotJson()} и асинхронная запись {@link #saveNow()}. Класс чистый
 * (Gson + NIO + UUID) и проверяется юнит-тестами без Bukkit.</p>
 */
public final class PartyStore {

    /** Версия формата файла. */
    public static final int VERSION = 1;
    /** Потолок числа групп в файле: защита от мусора и от бесконечного цикла. */
    public static final int MAX_PARTIES = 20_000;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type DATA_TYPE = new TypeToken<LinkedHashMap<String, Object>>() {
    }.getType();

    private final Path file;
    private final Logger logger;
    private final Map<UUID, Party> parties = new LinkedHashMap<>();
    private boolean dirty;

    public PartyStore(Path dataFolder, Logger logger) {
        this.file = dataFolder.resolve("parties.json");
        this.logger = logger;
    }

    public Path file() {
        return file;
    }

    /* --------------------------------- чтение --------------------------------- */

    /** Читает файл; битые записи пропускаются с предупреждением. */
    public synchronized void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, Object> data = GSON.fromJson(reader, DATA_TYPE);
            if (data == null) {
                return;
            }
            int skipped = 0;
            if (data.get("parties") instanceof Map<?, ?> rows) {
                for (Map.Entry<?, ?> entry : rows.entrySet()) {
                    UUID id = parseUuid(entry.getKey());
                    if (id == null || !(entry.getValue() instanceof Map<?, ?> row) || parties.size() >= MAX_PARTIES) {
                        skipped++;
                        continue;
                    }
                    Party party = readParty(id, row);
                    if (party == null || party.size() == 0) {
                        skipped++;
                        continue;
                    }
                    // Один игрок не может быть в двух группах: вторая запись
                    // считается мусором, иначе список и бонус разъедутся.
                    boolean clash = false;
                    for (UUID member : party.memberIds()) {
                        if (of(member) != null) {
                            clash = true;
                            break;
                        }
                    }
                    if (clash) {
                        skipped++;
                        continue;
                    }
                    party.leaderFirst();
                    parties.put(id, party);
                }
            }
            if (skipped > 0 && logger != null) {
                logger.warning("[CHRDK_Party] Пропущено записей parties.json: " + skipped + ".");
            }
            if (logger != null) {
                logger.info("[CHRDK_Party] Загружено групп: " + parties.size() + ".");
            }
        } catch (Exception exception) {
            if (logger != null) {
                logger.severe("[CHRDK_Party] Не удалось прочитать " + file + ": "
                        + exception.getMessage());
            }
        }
    }

    private static Party readParty(UUID id, Map<?, ?> row) {
        Map<?, ?> members = row.get("members") instanceof Map<?, ?> map ? map : Map.of();
        UUID leader = parseUuid(row.get("leader"));
        if (leader == null) {
            leader = firstUuid(members);
        }
        if (leader == null) {
            return null;
        }
        String name = "";
        if (members.get(leader.toString()) != null) {
            name = string(members.get(leader.toString()));
        }
        Party party = new Party(id, leader, name);
        for (Map.Entry<?, ?> entry : members.entrySet()) {
            UUID member = parseUuid(entry.getKey());
            if (member == null || member.equals(leader)) {
                continue;
            }
            party.add(member, string(entry.getValue()));
        }
        return party;
    }

    private static UUID firstUuid(Map<?, ?> members) {
        for (Object key : members.keySet()) {
            UUID id = parseUuid(key);
            if (id != null) {
                return id;
            }
        }
        return null;
    }

    /* --------------------------------- доступ --------------------------------- */

    /** Группа по её иду. */
    public synchronized Party byId(UUID id) {
        return id == null ? null : parties.get(id);
    }

    /** Группа игрока; {@code null} — он ни с кем. */
    public synchronized Party of(UUID player) {
        if (player == null) {
            return null;
        }
        for (Party party : parties.values()) {
            if (party.isMember(player)) {
                return party;
            }
        }
        return null;
    }

    /** Все группы — для админской сводки. */
    public synchronized Collection<Party> all() {
        return List.copyOf(parties.values());
    }

    public synchronized int size() {
        return parties.size();
    }

    /** Сколько всего игроков состоит в группах. */
    public synchronized int memberCount() {
        int total = 0;
        for (Party party : parties.values()) {
            total += party.size();
        }
        return total;
    }

    /** Создаёт группу; {@code null}, если игрок уже в какой-то. */
    public synchronized Party create(UUID leader, String name) {
        if (leader == null || of(leader) != null || parties.size() >= MAX_PARTIES) {
            return null;
        }
        Party party = new Party(UUID.randomUUID(), leader, name);
        parties.put(party.id(), party);
        dirty = true;
        return party;
    }

    /** Распускает группу; {@code true} — она была. */
    public synchronized boolean remove(UUID partyId) {
        if (partyId == null || parties.remove(partyId) == null) {
            return false;
        }
        dirty = true;
        return true;
    }

    /** Помечает данные изменёнными: состав правят методы {@link Party}. */
    public synchronized void markDirty() {
        dirty = true;
    }

    public synchronized boolean isDirty() {
        return dirty;
    }

    /* -------------------------------- запись ---------------------------------- */

    /** Снимок для асинхронной записи; сбрасывает флаг «грязно». */
    public synchronized String snapshotJson() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", VERSION);
        Map<String, Object> rows = new LinkedHashMap<>();
        List<Party> ordered = new ArrayList<>(parties.values());
        for (Party party : ordered) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("leader", party.leader().toString());
            Map<String, String> members = new LinkedHashMap<>();
            for (Map.Entry<UUID, String> member : party.members().entrySet()) {
                members.put(member.getKey().toString(), member.getValue());
            }
            row.put("members", members);
            rows.put(party.id().toString(), row);
        }
        data.put("parties", rows);
        dirty = false;
        return GSON.toJson(data);
    }

    /** Пишет файл, только если были изменения. */
    public void saveNow() {
        String json;
        synchronized (this) {
            if (!dirty) {
                return;
            }
            json = snapshotJson();
        }
        write(json);
    }

    /** Немедленная запись без проверки флага (выключение сервера, тесты). */
    public void flush() {
        write(snapshotJson());
    }

    private void write(String json) {
        if (!SafeJsonWriter.write(file, json, logger, "[CHRDK_Party]")) {
            // Диск не принял данные — помечаем состояние изменённым, иначе
            // следующее автосохранение решит, что писать нечего, и прогресс
            // потеряется молча.
            synchronized (this) {
                dirty = true;
            }
        }
    }
    private static UUID parseUuid(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(value).trim());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
