package ru.cherdakmd.chrdkregister;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Состояние особых званий: справочник определений и публичный реестр выдач.
 *
 * <p>Чистая модель без Bukkit и диска: её можно покрыть юнит-тестами. Диск,
 * контрольная сумма и защита {@code .broken} — в {@link HonorStore}.</p>
 *
 * <p>Одно активное звание на игрока: повторная выдача заменяет предыдущую,
 * чтобы в чате и TAB не склеивались два градиента. Все операции пишутся в
 * аудит вызывающим {@link HonorService}.</p>
 */
public final class HonorLedger {

    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_DEFINITIONS = 64;
    public static final int MAX_GRANTS = 2000;

    /** Снимок для Gson: обычные классы с публичными полями, без records. */
    public static final class State {
        public int version = SCHEMA_VERSION;
        public List<SavedDefinition> definitions = new ArrayList<>();
        public List<SavedGrant> grants = new ArrayList<>();
        /** SHA-256 канонического JSON без этого поля; пустая строка — legacy. */
        public String checksum = "";
    }

    public static final class SavedDefinition {
        public String id = "";
        public String name = "";
        public String format = HonorDefinition.DEFAULT_FORMAT;
        public String badge = "";
        public int grantLevel;
        public String description = "";
        public String joinLine = "";
        public boolean listed = true;
    }

    public static final class SavedGrant {
        public String player = "";
        public String uuid = "";
        public String honorId = "";
        public String grantedBy = "";
        public long grantedAt;
        public String reason = "";
    }

    private final Map<String, HonorDefinition> definitions = new LinkedHashMap<>();
    private final Map<String, HonorGrant> grants = new LinkedHashMap<>();

    /** Первый запуск без конфига: только шаблон, без выданных игроков. */
    public static HonorLedger withDefaults() {
        return withDefaults(HonorConfig.DEFAULT);
    }

    /**
     * Первый запуск: шаблон «Старый цитрус» плюс благодарности, явно
     * прописанные владельцем в {@code honors.initial-grants}.
     *
     * <p>Существующий {@code honors.json} этим методом не затрагивается:
     * решения администрации на диске всегда главнее конфига.</p>
     */
    public static HonorLedger withDefaults(HonorConfig config) {
        HonorLedger ledger = new HonorLedger();
        ledger.define(HonorDefinition.oldCitrus());
        if (config == null) return ledger;
        long now = System.currentTimeMillis();
        for (HonorConfig.InitialGrant entry : config.initialGrants()) {
            if (ledger.definition(entry.honorId()) == null) continue;
            String reason = entry.reason();
            if (reason.length() > config.maxReasonLength()) {
                reason = reason.substring(0, config.maxReasonLength()).trim();
            }
            ledger.grant(new HonorGrant(entry.player(), "", entry.honorId(),
                    "настройки сервера", now, reason));
        }
        return ledger;
    }

    public synchronized void restore(State state) {
        definitions.clear();
        grants.clear();
        if (state == null) return;
        if (state.definitions != null) {
            for (SavedDefinition raw : state.definitions) {
                if (raw == null || !HonorDefinition.validId(raw.id)) continue;
                if (definitions.size() >= MAX_DEFINITIONS) break;
                HonorDefinition def = new HonorDefinition(raw.id, raw.name, raw.format, raw.badge,
                        raw.grantLevel, raw.description, raw.joinLine, raw.listed);
                definitions.put(def.id(), def);
            }
        }
        if (state.grants != null) {
            for (SavedGrant raw : state.grants) {
                if (raw == null || HonorGrant.key(raw.player).isEmpty()) continue;
                String id = HonorDefinition.normalizeId(raw.honorId);
                if (!definitions.containsKey(id)) continue;
                if (grants.size() >= MAX_GRANTS) break;
                HonorGrant grant = new HonorGrant(raw.player, raw.uuid, id, raw.grantedBy,
                        raw.grantedAt, raw.reason);
                grants.put(grant.key(), grant);
            }
        }
    }

    public synchronized State snapshot() {
        State state = new State();
        state.version = SCHEMA_VERSION;
        for (HonorDefinition def : definitions.values()) {
            SavedDefinition raw = new SavedDefinition();
            raw.id = def.id();
            raw.name = def.name();
            raw.format = def.format();
            raw.badge = def.badge();
            raw.grantLevel = def.grantLevel();
            raw.description = def.description();
            raw.joinLine = def.joinLine();
            raw.listed = def.listed();
            state.definitions.add(raw);
        }
        for (HonorGrant grant : sortedGrants()) {
            SavedGrant raw = new SavedGrant();
            raw.player = grant.player();
            raw.uuid = grant.uuid();
            raw.honorId = grant.honorId();
            raw.grantedBy = grant.grantedBy();
            raw.grantedAt = grant.grantedAt();
            raw.reason = grant.reason();
            state.grants.add(raw);
        }
        return state;
    }

    public synchronized List<HonorDefinition> definitions() {
        return List.copyOf(definitions.values());
    }

    /** Реестр для игроков: только публичные звания, свежие сверху. */
    public synchronized List<HonorGrant> publicGrants() {
        return sortedGrants().stream()
                .filter(grant -> {
                    HonorDefinition def = definitions.get(grant.honorId());
                    return def != null && def.listed();
                })
                .toList();
    }

    public synchronized List<HonorGrant> allGrants() {
        return sortedGrants();
    }

    public synchronized List<HonorGrant> grantsOfHonor(String honorId) {
        String id = HonorDefinition.normalizeId(honorId);
        return sortedGrants().stream().filter(grant -> grant.honorId().equals(id)).toList();
    }

    public synchronized HonorDefinition definition(String id) {
        return definitions.get(HonorDefinition.normalizeId(id));
    }

    /** Активное звание игрока (для оформления ника), без учёта покупок. */
    public synchronized HonorDefinition active(String playerName) {
        HonorGrant grant = grants.get(HonorGrant.key(playerName));
        return grant == null ? null : definitions.get(grant.honorId());
    }

    public synchronized HonorGrant grantOf(String playerName) {
        return grants.get(HonorGrant.key(playerName));
    }

    /** Ступень, которую звание добавляет к оплаченному рангу. */
    public synchronized int grantLevel(String playerName) {
        HonorDefinition def = active(playerName);
        return def == null ? 0 : def.grantLevel();
    }

    /** Создать определение; false — невалидный id, дубль или переполнение. */
    public synchronized boolean define(HonorDefinition def) {
        if (def == null || !HonorDefinition.validId(def.id()) || definitions.containsKey(def.id())) {
            return false;
        }
        if (definitions.size() >= MAX_DEFINITIONS) return false;
        definitions.put(def.id(), def);
        return true;
    }

    /** Заменить определение по id, сохранив выданные звания. */
    public synchronized boolean replace(HonorDefinition def) {
        if (def == null || !HonorDefinition.validId(def.id()) || !definitions.containsKey(def.id())) {
            return false;
        }
        definitions.put(def.id(), def);
        return true;
    }

    /** Удалить определение и все его выдачи. */
    public synchronized boolean remove(String id) {
        String key = HonorDefinition.normalizeId(id);
        if (definitions.remove(key) == null) return false;
        grants.entrySet().removeIf(entry -> entry.getValue().honorId().equals(key));
        return true;
    }

    /** Выдать/заменить звание. Возвращает прежнюю выдачу (для аудита) или null. */
    public synchronized HonorGrant grant(HonorGrant grant) {
        if (grant == null || grant.key().isEmpty() || !definitions.containsKey(grant.honorId())) {
            return null;
        }
        if (!grants.containsKey(grant.key()) && grants.size() >= MAX_GRANTS) return null;
        return grants.put(grant.key(), grant);
    }

    /** Снять звание; пустой honorId снимает любое. */
    public synchronized HonorGrant revoke(String playerName, String honorId) {
        String key = HonorGrant.key(playerName);
        HonorGrant current = grants.get(key);
        if (current == null) return null;
        String wanted = HonorDefinition.normalizeId(honorId);
        if (!wanted.isEmpty() && !current.honorId().equals(wanted)) return null;
        return grants.remove(key);
    }

    /** Имя звания для реестра; неизвестный id не должен ронять отрисовку. */
    public synchronized String display(String honorId) {
        HonorDefinition def = definition(honorId);
        return def == null ? honorId : def.name();
    }

    private List<HonorGrant> sortedGrants() {
        List<HonorGrant> out = new ArrayList<>(grants.values());
        out.sort(Comparator.comparingLong(HonorGrant::grantedAt).reversed());
        return out;
    }
}
