package ru.cherdakmd.chrdkregister;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Чистая доменная логика модерации: варны, роли модераторов, баны и журнал.
 *
 * <p>Здесь нет Bukkit и Gson — только синхронизированное состояние в памяти,
 * поэтому правила покрываются обычными юнит-тестами. Постоянство обеспечивает
 * {@link ModerationStore}, а действия с сервером (BanList, кик, TAB) —
 * {@link ModerationService}.</p>
 *
 * <p>Варны постоянные: снятие не удаляет запись, а помечает её
 * {@code removedBy/removedAt}. Поэтому история профиля полная, а к лимиту
 * считаются только активные варны. Ключ игрока — ник в нижнем регистре:
 * смена UUID или регистра не обнуляет историю.</p>
 */
public final class ModerationLedger {

    public static final int SCHEMA_VERSION = 1;
    public static final int DEFAULT_WARN_LIMIT = 5;
    /** Журнал в moderation.json не растёт бесконечно; старое уже есть в audit.jsonl. */
    public static final int MAX_JOURNAL = 5000;

    public static final String ACTION_WARN = "warn";
    public static final String ACTION_UNWARN = "unwarn";
    public static final String ACTION_BAN = "ban";
    public static final String ACTION_UNBAN = "unban";
    public static final String ACTION_ROLE_ADD = "moderator.add";
    public static final String ACTION_ROLE_REMOVE = "moderator.remove";
    public static final String ACTION_MUTE = "mute";
    public static final String ACTION_UNMUTE = "unmute";
    public static final String ACTION_KICK = "kick";
    public static final String ACTION_IP_BAN = "ip_ban";
    public static final String ACTION_IP_UNBAN = "ip_unban";

    /** Одна запись о варне. Снятие — пометка, а не удаление. */
    public static final class Warn {
        public long id;
        public String target = "";
        public String issuer = "";
        public String reason = "";
        public long at;
        public String removedBy = "";
        public long removedAt;

        public Warn() { }

        Warn(long id, String target, String issuer, String reason, long at) {
            this.id = id;
            this.target = target;
            this.issuer = issuer;
            this.reason = reason;
            this.at = at;
        }

        Warn copy() {
            Warn out = new Warn();
            out.id = id; out.target = target; out.issuer = issuer; out.reason = reason;
            out.at = at; out.removedBy = removedBy; out.removedAt = removedAt;
            return out;
        }
    }

    /** Роль модератора, выданная через админ-панель. */
    public static final class Moderator {
        public String name = "";
        public String addedBy = "";
        public long addedAt;

        public Moderator() { }

        Moderator(String name, String addedBy, long addedAt) {
            this.name = name; this.addedBy = addedBy; this.addedAt = addedAt;
        }

        Moderator copy() {
            Moderator out = new Moderator();
            out.name = name; out.addedBy = addedBy; out.addedAt = addedAt;
            return out;
        }
    }

    /** Зеркало ванильного BanList: причина, автор и время для GUI и журнала. */
    public static final class Ban {
        public String target = "";
        public String reason = "";
        public String by = "";
        public long at;

        public Ban() { }

        Ban(String target, String reason, String by, long at) {
            this.target = target; this.reason = reason; this.by = by; this.at = at;
        }

        Ban copy() {
            Ban out = new Ban();
            out.target = target; out.reason = reason; out.by = by; out.at = at;
            return out;
        }
    }

    /** Одно действие модерации: кто, над кем, что и с каким комментарием. */
    public static final class JournalEntry {
        public long id;
        public long at;
        public String action = "";
        public String actor = "";
        public String target = "";
        public String detail = "";

        public JournalEntry() { }

        JournalEntry(long id, long at, String action, String actor, String target, String detail) {
            this.id = id; this.at = at; this.action = action;
            this.actor = actor; this.target = target; this.detail = detail;
        }

        JournalEntry copy() {
            JournalEntry out = new JournalEntry();
            out.id = id; out.at = at; out.action = action;
            out.actor = actor; out.target = target; out.detail = detail;
            return out;
        }
    }

    /** Снимок для Gson: только публичные поля, старые файлы с отсутствующими полями читаются. */
    public static final class State {
        public int version = SCHEMA_VERSION;
        public long nextWarnId = 1L;
        public long nextJournalId = 1L;
        public List<Warn> warns = new ArrayList<>();
        public List<Moderator> moderators = new ArrayList<>();
        public List<Ban> bans = new ArrayList<>();
        public List<JournalEntry> journal = new ArrayList<>();
        /** SHA-256 канонического JSON без этого поля; пустая строка — файл старого формата. */
        public String checksum = "";
    }

    private final List<Warn> warns = new ArrayList<>();
    private final Map<String, Moderator> moderators = new LinkedHashMap<>();
    private final Map<String, Ban> bans = new LinkedHashMap<>();
    private final List<JournalEntry> journal = new ArrayList<>();
    private long nextWarnId = 1L;
    private long nextJournalId = 1L;

    /** Ключ хранилища: регистронезависимый ник без пробелов. */
    public static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    /** Ник для показа: обрезаем пробелы, но сохраняем регистр игрока. */
    public static String display(String name) {
        return name == null ? "" : name.trim();
    }

    /* ------------------------------- варны -------------------------------- */

    public synchronized Warn issueWarn(String target, String issuer, String reason, long now) {
        String to = key(target);
        if (to.isEmpty() || reason == null || reason.isBlank()) return null;
        String text = reason.trim();
        Warn warn = new Warn(nextWarnId++, to, key(issuer), text, now);
        warns.add(warn);
        record(ACTION_WARN, key(issuer), to, text, now);
        return warn.copy();
    }

    public synchronized boolean removeWarn(long id, String actor, long now) {
        for (Warn warn : warns) {
            if (warn.id == id && warn.removedAt <= 0) {
                warn.removedBy = key(actor);
                warn.removedAt = now;
                record(ACTION_UNWARN, key(actor), warn.target, "#" + id, now);
                return true;
            }
        }
        return false;
    }

    public synchronized List<Warn> activeWarns(String target) {
        String to = key(target);
        if (to.isEmpty()) return List.of();
        List<Warn> out = new ArrayList<>();
        for (Warn warn : warns) {
            if (warn.target.equals(to) && warn.removedAt <= 0) out.add(warn.copy());
        }
        return List.copyOf(out);
    }

    public synchronized List<Warn> warnHistory(String target) {
        String to = key(target);
        if (to.isEmpty()) return List.of();
        List<Warn> out = new ArrayList<>();
        for (Warn warn : warns) {
            if (warn.target.equals(to)) out.add(warn.copy());
        }
        out.sort(Comparator.comparingLong((Warn w) -> w.at).reversed());
        return List.copyOf(out);
    }

    public synchronized int activeCount(String target) {
        return activeWarns(target).size();
    }

    /** Пятой активный варн (или любой порог из конфига) достигнут. */
    public synchronized boolean reachedLimit(String target, int limit) {
        return limit > 0 && activeCount(target) >= limit;
    }

    public synchronized int warnTotal() {
        return warns.size();
    }

    /**
     * Снимок «ник → число активных варнов» для публичного кадра TAB. Снятые
     * варны не учитываются; причины, даты и авторы выдачи не покидают журнал.
     */
    public synchronized List<PublicWarnSummary.Row> activeWarnSummary() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Warn warn : warns) {
            if (warn.removedAt <= 0 && warn.target != null && !warn.target.isBlank()) {
                counts.merge(warn.target, 1, Integer::sum);
            }
        }
        List<PublicWarnSummary.Row> out = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            out.add(new PublicWarnSummary.Row(display(entry.getKey()), entry.getValue()));
        }
        return List.copyOf(out);
    }

    /* ---------------------------- модераторы ------------------------------ */

    public synchronized boolean addModerator(String name, String actor, long now) {
        String to = key(name);
        if (to.isEmpty()) return false;
        boolean added = moderators.put(to, new Moderator(to, key(actor), now)) == null;
        if (added) record(ACTION_ROLE_ADD, key(actor), to, "", now);
        return added;
    }

    public synchronized boolean removeModerator(String name, String actor, long now) {
        String to = key(name);
        if (to.isEmpty() || moderators.remove(to) == null) return false;
        record(ACTION_ROLE_REMOVE, key(actor), to, "", now);
        return true;
    }

    public synchronized boolean isModerator(String name) {
        return moderators.containsKey(key(name));
    }

    public synchronized List<Moderator> moderators() {
        List<Moderator> out = new ArrayList<>();
        for (Moderator value : moderators.values()) out.add(value.copy());
        out.sort(Comparator.comparing(m -> m.name));
        return List.copyOf(out);
    }

    /* -------------------------------- баны --------------------------------- */

    public synchronized Ban ban(String target, String reason, String actor, long now) {
        String to = key(target);
        if (to.isEmpty()) return null;
        Ban ban = new Ban(to, reason == null ? "" : reason, key(actor), now);
        bans.put(to, ban);
        record(ACTION_BAN, key(actor), to, ban.reason, now);
        return ban.copy();
    }

    public synchronized boolean unban(String target, String actor, long now) {
        String to = key(target);
        if (to.isEmpty() || bans.remove(to) == null) return false;
        record(ACTION_UNBAN, key(actor), to, "", now);
        return true;
    }

    public synchronized boolean isBanned(String target) {
        return bans.containsKey(key(target));
    }

    public synchronized Ban banOf(String target) {
        Ban ban = bans.get(key(target));
        return ban == null ? null : ban.copy();
    }

    public synchronized int bannedCount() {
        return bans.size();
    }

    /* -------------------------------- журнал ------------------------------- */

    public synchronized JournalEntry record(String action, String actor, String target, String detail, long now) {
        JournalEntry entry = new JournalEntry(nextJournalId++, now,
                action == null ? "" : action, key(actor), key(target),
                detail == null ? "" : detail);
        journal.add(entry);
        if (journal.size() > MAX_JOURNAL) {
            journal.subList(0, journal.size() - MAX_JOURNAL).clear();
        }
        return entry.copy();
    }

    /** Новые сверху; фильтр ищет по действию, автору, цели и комментарию. */
    public synchronized List<JournalEntry> journal(String filter, int limit) {
        String needle = key(filter);
        List<JournalEntry> out = new ArrayList<>();
        for (JournalEntry entry : journal) {
            if (!needle.isEmpty()
                    && !entry.action.contains(needle)
                    && !entry.actor.contains(needle)
                    && !entry.target.contains(needle)
                    && !entry.detail.toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            out.add(entry.copy());
        }
        out.sort(Comparator.comparingLong((JournalEntry e) -> e.id).reversed());
        if (limit > 0 && out.size() > limit) return List.copyOf(out.subList(0, limit));
        return List.copyOf(out);
    }

    public synchronized int journalCount() {
        return journal.size();
    }

    /* ------------------------------ сохранение ------------------------------ */

    public synchronized State snapshot() {
        State state = new State();
        state.nextWarnId = nextWarnId;
        state.nextJournalId = nextJournalId;
        for (Warn warn : warns) state.warns.add(warn.copy());
        for (Moderator value : moderators.values()) state.moderators.add(value.copy());
        for (Ban value : bans.values()) state.bans.add(value.copy());
        for (JournalEntry entry : journal) state.journal.add(entry.copy());
        return state;
    }

    /** Восстанавливает состояние из файла; битые/пустые поля не ломают панель. */
    public synchronized void restore(State state) {
        warns.clear(); moderators.clear(); bans.clear(); journal.clear();
        nextWarnId = 1L; nextJournalId = 1L;
        if (state == null) return;
        long maxWarn = 0L, maxJournal = 0L;
        if (state.warns != null) {
            for (Warn source : state.warns) {
                if (source == null || key(source.target).isEmpty()) continue;
                Warn warn = source.copy();
                warn.target = key(warn.target);
                warn.issuer = key(warn.issuer);
                warns.add(warn);
                maxWarn = Math.max(maxWarn, warn.id);
            }
        }
        if (state.moderators != null) {
            for (Moderator source : state.moderators) {
                if (source == null || key(source.name).isEmpty()) continue;
                Moderator moderator = source.copy();
                moderator.name = key(moderator.name);
                moderators.put(moderator.name, moderator);
            }
        }
        if (state.bans != null) {
            for (Ban source : state.bans) {
                if (source == null || key(source.target).isEmpty()) continue;
                Ban ban = source.copy();
                ban.target = key(ban.target);
                bans.put(ban.target, ban);
            }
        }
        if (state.journal != null) {
            for (JournalEntry source : state.journal) {
                if (source == null) continue;
                JournalEntry entry = source.copy();
                journal.add(entry);
                maxJournal = Math.max(maxJournal, entry.id);
            }
        }
        nextWarnId = Math.max(maxWarn + 1, Math.max(1L, state.nextWarnId));
        nextJournalId = Math.max(maxJournal + 1, Math.max(1L, state.nextJournalId));
    }
}
