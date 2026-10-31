package ru.cherdakmd.chrdkguilds;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import ru.cherdakmd.chrdkregister.storage.SafeJsonWriter;

/**
 * Хранилище гильдий в {@code guilds.json} (1.4.163).
 *
 * <p>Переживают перезапуск состав, казна, уровни, слава, приглашения и войны.
 * Здесь же лежит индекс «игрок → гильдия»: без него каждый вход в чат
 * перебирал бы все гильдии сервера.</p>
 *
 * <p>Запись общая для репозитория: снимок на серверном потоке, файл — во
 * временный и атомарное переименование. Класс чистый (Gson + NIO) и
 * проверяется тестами без Bukkit.</p>
 */
final class GuildStore {

    /** Версия формата файла. */
    static final int VERSION = 3;
    /** Сколько последних войн хранится в истории. */
    static final int MAX_HISTORY = 50;

    private static final Gson GSON = new GsonBuilder().setObjectToNumberStrategy(com.google.gson.ToNumberPolicy.BIG_DECIMAL).setPrettyPrinting().create();
    private static final Type DATA_TYPE = new TypeToken<LinkedHashMap<String, Object>>() {
    }.getType();
    private static final DateTimeFormatter STAMP = DateTimeFormatter
            .ofPattern("dd.MM HH:mm")
            .withZone(ZoneId.systemDefault());

    /** Запись истории войн: кто, с кем, когда и чем кончилось. */
    record WarRecord(String warId, String challenger, String target, String winner, String result,
                     long wager, long endedAt) {

        String time() {
            return endedAt <= 0 ? "—" : STAMP.format(Instant.ofEpochMilli(endedAt));
        }
    }

    private final Path file;
    private final Logger logger;
    private final Map<String, Guild> guilds = new LinkedHashMap<>();
    private final Map<UUID, String> byMember = new HashMap<>();
    private final List<GuildInvite> invites = new ArrayList<>();
    private final List<GuildWar> wars = new ArrayList<>();
    private final List<WarRecord> history = new ArrayList<>();
    private final Map<UUID,GuildSanction> sanctions = new LinkedHashMap<>();
    private final Map<UUID,List<String>> peaceNotices = new LinkedHashMap<>();
    private java.util.function.Predicate<UUID> combatGuard = player -> false;
    void combatGuard(java.util.function.Predicate<UUID> guard) { combatGuard=guard; }
    boolean combatBlocked(UUID player) { return combatGuard.test(player); }
    synchronized void peaceNotice(UUID player,String text) {
        detachedOnly(); var rows=peaceNotices.computeIfAbsent(player,id->new ArrayList<>());
        if(rows.size()>=8)rows.removeFirst();rows.add(text);dirty=true;
    }
    synchronized List<String> peaceNotices(UUID player) { refresh();return List.copyOf(peaceNotices.getOrDefault(player,List.of())); }
    synchronized void clearPeaceNotices(UUID player) { detachedOnly();peaceNotices.remove(player);dirty=true; }

    synchronized GuildSanction sanction(UUID owner) { refresh(); return sanctions.getOrDefault(owner,new GuildSanction(0,0)); }
    synchronized Map<UUID,GuildSanction> sanctions() { refresh(); return Map.copyOf(sanctions); }
    synchronized void sanction(UUID owner,GuildSanction value) { detachedOnly(); sanctions.put(owner,value); dirty=true; }

    private boolean dirty;
    private boolean healthy = true;
    private ru.cherdakmd.chrdkregister.api.PaidActionStore finance;
    private String lastDomain;
    static final String FINANCE_KEY = "guild-state";

    boolean financed() { return finance != null; }

    /** Старый JSON читается ровно до durable отметки импорта. После неё — только архив. */
    synchronized void bind(ru.cherdakmd.chrdkregister.api.PaidActionStore source) throws IOException {
        if (!source.valuesInitialized()) {
            if (!source.values().isEmpty()) throw new IOException("У гильдий потерян флаг импорта");
            load();
            source.initializeValues(Map.of(FINANCE_KEY, snapshotJson()));
        }
        if (source.values().size() != 1 || !source.values().containsKey(FINANCE_KEY)) throw new IOException("Неверный источник гильдий");
        finance = source;
        refresh();
        // Миграция срока первого взноса фиксируется до запуска биллинга, а не сдвигается при restart.
        if(com.google.gson.JsonParser.parseString(source.values().get(FINANCE_KEY)).getAsJsonObject().get("version").getAsInt()<VERSION){
            source.putValue(FINANCE_KEY,snapshotJson());refresh();
        }
        dirty = false;
    }

    private void refresh() {
        if (finance == null) return;
        String json = finance.values().get(FINANCE_KEY);
        if (json == null) throw new IllegalStateException("Потеряно состояние гильдий");
        if (!json.equals(lastDomain)) {
            GuildStore parsed = detached(json);
            guilds.clear(); guilds.putAll(parsed.guilds);
            byMember.clear(); byMember.putAll(parsed.byMember);
            invites.clear(); invites.addAll(parsed.invites);
            wars.clear(); wars.addAll(parsed.wars);
            history.clear(); history.addAll(parsed.history);
            sanctions.clear(); sanctions.putAll(parsed.sanctions);
            peaceNotices.clear();peaceNotices.putAll(parsed.peaceNotices);
            lastDomain = json;
        }
    }

    synchronized GuildStore detached(String json) {
        GuildStore copy = new GuildStore(file.getParent(), logger);
        copy.readData(json);
        copy.combatGuard=combatGuard;
        return copy;
    }

    synchronized GuildStore copy() { refresh(); return detached(snapshotJson()); }

    /** Все мутации, включая результат войны и состав, публикуются до внешнего ответа. */
    synchronized <T> T transaction(java.util.function.Function<GuildStore,T> operation, T failure) {
        try {
            if (finance == null || !finance.healthy()) return failure;
            GuildStore copy = copy();
            String before = copy.snapshotJson();
            T result = operation.apply(copy);
            String after = copy.snapshotJson();
            if (!before.equals(after)) {
                detached(after); // валидация перед записью, не после получения денег
                finance.putValue(FINANCE_KEY, after);
                refresh();
            }
            return result;
        } catch (IOException | RuntimeException error) {
            if (logger != null) logger.log(java.util.logging.Level.SEVERE,"Гильдии: изменение не подтверждено; проверьте финансовый журнал",error);
            return failure;
        }
    }

    private void detachedOnly() {
        if (!healthy || finance != null) throw new IllegalStateException("Live guild mutations require transaction");
    }


    GuildStore(Path dataFolder, Logger logger) {
        this.file = dataFolder.resolve("guilds.json");
        this.logger = logger;
    }

    Path file() {
        return file;
    }

    /* --------------------------------- чтение --------------------------------- */

    /** Повреждение казны/резерва останавливает модуль, а не создаёт пустую казну. */
    synchronized void load() {
        detachedOnly();
        if (!Files.exists(file)) return;
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > 8L*1024*1024) throw new IOException("guild source capacity/type");
            GuildStore parsed = detached(Files.readString(file));
            guilds.clear(); guilds.putAll(parsed.guilds);
            byMember.clear(); byMember.putAll(parsed.byMember);
            invites.clear(); invites.addAll(parsed.invites);
            wars.clear(); wars.addAll(parsed.wars);
            history.clear(); history.addAll(parsed.history);
            sanctions.clear(); sanctions.putAll(parsed.sanctions);
            peaceNotices.clear();peaceNotices.putAll(parsed.peaceNotices);
        } catch (IOException | RuntimeException error) {
            healthy = false;
            throw new IllegalStateException("Повреждён guilds.json; исходник не перезаписывается",error);
        }
    }

    private void readData(String json) {
        Map<String,Object> data = GSON.fromJson(json, DATA_TYPE);
        validateInput(data);
        if(data.get("peace-notices") instanceof Map<?,?> outbox)for(var entry:outbox.entrySet()) {
            var lines=new ArrayList<String>();for(Object text:(List<?>)entry.getValue())lines.add((String)text);
            peaceNotices.put(strictUuid(entry.getKey()),lines);
        }
        if (data.get("sanctions") instanceof Map<?,?> fines) for (var entry:fines.entrySet()) {
            Map<?,?> row=(Map<?,?>)entry.getValue();
            sanctions.put(strictUuid(entry.getKey()), new GuildSanction(number(row.get("debt")),number(row.get("next-incident-at"))));
        }
        for (Map<?,?> row : rows(data,"guilds")) {
            Guild guild = readGuild(row,number(data.get("version")));
            guilds.put(guild.id(),guild);
            if(number(data.get("version"))<3)for(UUID member:guild.memberIds())peaceNotice(member,
                    "Введён обязательный взнос 10 алмазов/сутки лично в казну клана, дополнительно к мирному режиму. Первый срок после переходных суток: "+STAMP.format(Instant.ofEpochMilli(guild.duesTerm(member).dueAt()))+". /clan dues — условия и срок. Неоплата: ещё сутки, затем исключение; у лидера — роспуск клана.");
            for (UUID member : guild.memberIds()) byMember.put(member,guild.id());
        }
        for (Map<?,?> row : rows(data,"invites")) invites.add(readInvite(row));
        for (Map<?,?> row : rows(data,"wars")) wars.add(readWar(row));
        for (Map<?,?> row : rows(data,"history")) history.add(new WarRecord(
                string(row.get("id")),string(row.get("challenger")),string(row.get("target")),
                string(row.get("winner")),string(row.get("result")),number(row.get("wager")),number(row.get("ended-at"))));
    }

    private static Guild readGuild(Map<?, ?> row,long version) {
        String id = string(row.get("id"));
        String name = string(row.get("name"));
        if (id.isEmpty() || name.isEmpty()) {
            return null;
        }
        UUID leader = uuid(row.get("leader"));
        Guild guild = new Guild(id, name, string(row.get("tag")), leader,
                Math.max(0L, number(row.get("created-at"))));
        if (row.get("members") instanceof Map<?, ?> members) {
            for (Map.Entry<?, ?> entry : members.entrySet()) {
                UUID member = uuid(entry.getKey());
                if (member == null) {
                    continue;
                }
                guild.addMember(member, GuildRank.parse(string(entry.getValue())));
            }
        }
        guild.restore(number(row.get("treasury")), (int) number(row.get("level")),
                (int) number(row.get("glory")), (int) number(row.get("wars-won")),
                (int) number(row.get("wars-lost")));
        guild.setWarCooldownUntil(number(row.get("war-cooldown-until")));
        guild.restoreWarReturns(number(row.get("war-returns")));
        guild.mode(row.containsKey("mode") ? GuildMode.valueOf(string(row.get("mode"))) : GuildMode.BATTLE, number(row.get("mode-changed-at")));
        if (row.get("peace-terms") instanceof Map<?,?> terms) for(var entry:terms.entrySet()) {
            Map<?,?> term=(Map<?,?>)entry.getValue();
            guild.peaceTerm(strictUuid(entry.getKey()),new GuildPeaceTerm(number(term.get("paid-until")),number(term.get("grace-since")),number(term.get("activates-at"))));
        }
        if(version>=3){
            guild.duesRate(Math.toIntExact(number(row.get("dues-rate"))),number(row.get("dues-changed-at")));
            guild.restoreDuesReserve(number(row.get("dues-reserve")));
            for(var entry:((Map<?,?>)row.get("dues-terms")).entrySet()){
                Map<?,?> term=(Map<?,?>)entry.getValue();
                guild.duesTerm(strictUuid(entry.getKey()),new GuildDuesTerm(number(term.get("due-at")),number(term.get("grace-since")),Math.toIntExact(number(term.get("amount")))));
            }
        }else{
            long migration=System.currentTimeMillis();
            for(UUID owner:guild.memberIds())guild.duesTerm(owner,GuildDuesTerm.start(migration,10));
        }
        return guild;
    }

    private static GuildInvite readInvite(Map<?, ?> row) {
        String guildId = string(row.get("guild"));
        UUID player = uuid(row.get("player"));
        UUID inviter = uuid(row.get("inviter"));
        if (guildId.isEmpty() || player == null) {
            return null;
        }
        return new GuildInvite(guildId, string(row.get("guild-name")), player, inviter,
                Math.max(0L, number(row.get("created-at"))),
                Math.max(0L, number(row.get("expires-at"))));
    }

    private static GuildWar readWar(Map<?, ?> row) {
        String challenger = string(row.get("challenger"));
        String target = string(row.get("target"));
        if (challenger.isEmpty() || target.isEmpty() || challenger.equals(target)) {
            return null;
        }
        GuildWar war = new GuildWar(string(row.get("id")), challenger, target,
                number(row.get("wager")), Math.max(0L, number(row.get("created-at"))));
        Map<String, Long> kills = new LinkedHashMap<>();
        if (row.get("last-kills") instanceof Map<?, ?> rows) {
            for (Map.Entry<?, ?> entry : rows.entrySet()) {
                if (entry.getKey() != null && entry.getValue() instanceof Number value) {
                    kills.put(entry.getKey().toString(), value.longValue());
                }
            }
        }
        GuildWar.Status status = Status.parse(string(row.get("status")));
        GuildWar.Result result = ResultValue.parse(string(row.get("result")));
        war.restore(status, result, number(row.get("starts-at")), number(row.get("ends-at")),
                (int) number(row.get("challenger-points")), (int) number(row.get("target-points")),
                kills);
        return war;
    }

    /* --------------------------------- доступ --------------------------------- */

    synchronized List<Guild> guilds() {
        refresh();
        return List.copyOf(guilds.values());
    }

    synchronized int count() {
        refresh();
        return guilds.size();
    }

    synchronized Guild guild(String id) {
        refresh();
        return id == null ? null : guilds.get(id.trim());
    }

    synchronized Guild of(UUID player) {
        refresh();
        if (player == null) {
            return null;
        }
        String id = byMember.get(player);
        return id == null ? null : guilds.get(id);
    }

    synchronized Guild byName(String name) {
        refresh();
        String key = GuildMath.key(name);
        if (key.isEmpty()) {
            return null;
        }
        for (Guild guild : guilds.values()) {
            if (GuildMath.key(guild.name()).equals(key)) {
                return guild;
            }
        }
        return null;
    }

    synchronized Guild byTag(String tag) {
        refresh();
        String key = GuildMath.key(tag);
        if (key.isEmpty()) {
            return null;
        }
        for (Guild guild : guilds.values()) {
            if (GuildMath.key(guild.tag()).equals(key)) {
                return guild;
            }
        }
        return null;
    }

    /** Поиск по названию или тегу — для команд и войн. */
    synchronized Guild find(String query) {
        refresh();
        Guild byName = byName(query);
        return byName != null ? byName : byTag(query);
    }

    synchronized List<Guild> top(int limit) {
        refresh();
        List<Guild> list = new ArrayList<>(guilds.values());
        list.sort((left, right) -> {
            int byGlory = Integer.compare(right.glory(), left.glory());
            if (byGlory != 0) {
                return byGlory;
            }
            int byLevel = Integer.compare(right.level(), left.level());
            return byLevel != 0 ? byLevel : left.name().compareToIgnoreCase(right.name());
        });
        return list.subList(0, Math.clamp(limit, 0, list.size()));
    }

    /* -------------------------------- изменения ------------------------------- */

    synchronized void add(Guild guild) {
        detachedOnly();
        if (guild == null || guilds.containsKey(guild.id())) {
            return;
        }
        guilds.put(guild.id(), guild);
        for (UUID member : guild.memberIds()) {
            byMember.put(member, guild.id());
        }
        dirty = true;
    }

    synchronized void remove(Guild guild) {
        detachedOnly();
        if (guild == null || guilds.remove(guild.id()) == null) {
            return;
        }
        for (UUID member : guild.memberIds()) {
            byMember.remove(member);
        }
        invites.removeIf(invite -> invite.guildId().equals(guild.id()));
        wars.removeIf(war -> war.involves(guild.id()) && !war.finished());
        dirty = true;
    }

    /** Пересчитывает индекс «игрок → гильдия» после изменений состава. */
    synchronized void reindex(Guild guild) {
        detachedOnly();
        if (guild == null) {
            return;
        }
        for (Map.Entry<UUID, String> entry : new ArrayList<>(byMember.entrySet())) {
            if (entry.getValue().equals(guild.id()) && !guild.isMember(entry.getKey())) {
                byMember.remove(entry.getKey());
            }
        }
        for (UUID member : guild.memberIds()) {
            byMember.put(member, guild.id());
        }
        dirty = true;
    }

    /* ------------------------------- приглашения ------------------------------ */

    synchronized void addInvite(GuildInvite invite) {
        detachedOnly();
        if (invite == null) {
            return;
        }
        invites.removeIf(existing -> existing.player().equals(invite.player()));
        invites.add(invite);
        dirty = true;
    }

    synchronized GuildInvite inviteOf(UUID player) {
        refresh();
        if (player == null) {
            return null;
        }
        for (GuildInvite invite : invites) {
            if (invite.player().equals(player)) {
                return invite;
            }
        }
        return null;
    }

    synchronized boolean removeInvite(UUID player) {
        detachedOnly();
        boolean removed = invites.removeIf(invite -> invite.player().equals(player));
        if (removed) {
            dirty = true;
        }
        return removed;
    }

    /** Убирает просроченные приглашения; возвращает число удалённых. */
    synchronized int pruneInvites(long now) {
        detachedOnly();
        int before = invites.size();
        invites.removeIf(invite -> invite.expired(now));
        int removed = before - invites.size();
        if (removed > 0) {
            dirty = true;
        }
        return removed;
    }

    /* ---------------------------------- войны --------------------------------- */

    synchronized void addWar(GuildWar war) {
        detachedOnly();
        if (war == null) {
            return;
        }
        wars.removeIf(existing -> existing.id().equals(war.id()));
        wars.add(war);
        dirty = true;
    }

    synchronized List<GuildWar> wars() {
        refresh();
        return List.copyOf(wars);
    }

    /** Незакрытая война гильдии или {@code null}. */
    synchronized GuildWar warOf(String guildId) {
        refresh();
        if (guildId == null) {
            return null;
        }
        for (GuildWar war : wars) {
            if (!war.finished() && war.involves(guildId)) {
                return war;
            }
        }
        return null;
    }

    /** Война между двумя гильдиями, если она ещё не закрыта. */
    synchronized GuildWar warBetween(String first, String second) {
        refresh();
        if (first == null || second == null) {
            return null;
        }
        for (GuildWar war : wars) {
            if (!war.finished() && war.involves(first) && war.involves(second)) {
                return war;
            }
        }
        return null;
    }

    synchronized int activeWars() {
        refresh();
        int count = 0;
        for (GuildWar war : wars) {
            if (war.active()) {
                count++;
            }
        }
        return count;
    }

    synchronized int pendingWars() {
        refresh();
        int count = 0;
        for (GuildWar war : wars) {
            if (war.pending()) {
                count++;
            }
        }
        return count;
    }

    /** Закрывает войну и убирает её из активных, оставляя в истории. */
    synchronized void closeWar(GuildWar war, String winnerName) {
        detachedOnly();
        if (war == null) {
            return;
        }
        wars.remove(war);
        history.add(new WarRecord(war.id(), war.challengerId(), war.targetId(),
                winnerName == null ? "" : winnerName,
                war.result() == null ? "" : war.result().name(),
                war.wager(), System.currentTimeMillis()));
        while (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
        dirty = true;
    }

    synchronized List<WarRecord> history() {
        refresh();
        return List.copyOf(history);
    }

    synchronized void clearHistory() {
        detachedOnly();
        if (history.isEmpty()) {
            return;
        }
        history.clear();
        dirty = true;
    }

    /* --------------------------------- запись --------------------------------- */

    synchronized boolean isDirty() {
        return dirty;
    }

    synchronized String snapshotJson() {
        refresh();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", VERSION);
        if(!peaceNotices.isEmpty()) {Map<String,Object> outbox=new LinkedHashMap<>();peaceNotices.forEach((id,lines)->outbox.put(id.toString(),List.copyOf(lines)));data.put("peace-notices",outbox);}
        List<Map<String, Object>> guildRows = new ArrayList<>();
        for (Guild guild : guilds.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", guild.id());
            row.put("name", guild.name());
            if (!guild.tag().isEmpty()) {
                row.put("tag", guild.tag());
            }
            row.put("leader", String.valueOf(guild.leader()));
            row.put("created-at", guild.createdAt());
            row.put("dues-rate",guild.duesRate());row.put("dues-changed-at",guild.duesChangedAt());
            row.put("dues-reserve",guild.duesReserve());
            Map<String,Object> dues=new LinkedHashMap<>();
            guild.duesTerms().forEach((id,t)->dues.put(id.toString(),Map.of("due-at",t.dueAt(),"grace-since",t.graceSince(),"amount",t.amount())));
            row.put("dues-terms",dues);
            row.put("mode",guild.mode().name());
            row.put("mode-changed-at",guild.modeChangedAt());
            if(guild.peaceful()) {
                Map<String,Object> terms=new LinkedHashMap<>();
                guild.peaceTerms().forEach((id,t)->terms.put(id.toString(),Map.of("paid-until",t.paidUntil(),"grace-since",t.graceSince(),"activates-at",t.activatesAt())));
                row.put("peace-terms",terms);
            }
            Map<String, Object> members = new LinkedHashMap<>();
            for (Map.Entry<UUID, GuildRank> entry : guild.members().entrySet()) {
                members.put(entry.getKey().toString(), entry.getValue().id());
            }
            row.put("members", members);
            row.put("treasury", guild.treasury());
            if (guild.warReturns() > 0) row.put("war-returns", guild.warReturns());
            row.put("level", guild.level());
            row.put("glory", guild.glory());
            row.put("wars-won", guild.warsWon());
            row.put("wars-lost", guild.warsLost());
            if (guild.warCooldownUntil() > 0) {
                row.put("war-cooldown-until", guild.warCooldownUntil());
            }
            guildRows.add(row);
        }
        data.put("guilds", guildRows);
        Map<String,Object> fines=new LinkedHashMap<>();
        sanctions.forEach((id,t)->fines.put(id.toString(),Map.of("debt",t.debt(),"next-incident-at",t.nextIncidentAt())));
        if(!fines.isEmpty())data.put("sanctions",fines);

        List<Map<String, Object>> inviteRows = new ArrayList<>();
        for (GuildInvite invite : invites) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("guild", invite.guildId());
            row.put("guild-name", invite.guildName());
            row.put("player", invite.player().toString());
            if (invite.inviter() != null) {
                row.put("inviter", invite.inviter().toString());
            }
            row.put("created-at", invite.createdAt());
            row.put("expires-at", invite.expiresAt());
            inviteRows.add(row);
        }
        if (!inviteRows.isEmpty()) {
            data.put("invites", inviteRows);
        }

        List<Map<String, Object>> warRows = new ArrayList<>();
        for (GuildWar war : wars) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", war.id());
            row.put("challenger", war.challengerId());
            row.put("target", war.targetId());
            row.put("wager", war.wager());
            row.put("created-at", war.createdAt());
            row.put("starts-at", war.startsAt());
            row.put("ends-at", war.endsAt());
            row.put("status", war.status().name());
            if (war.result() != null) {
                row.put("result", war.result().name());
            }
            row.put("challenger-points", war.challengerPoints());
            row.put("target-points", war.targetPoints());
            if (!war.lastKills().isEmpty()) {
                row.put("last-kills", new LinkedHashMap<>(war.lastKills()));
            }
            warRows.add(row);
        }
        if (!warRows.isEmpty()) {
            data.put("wars", warRows);
        }

        if (!history.isEmpty()) {
            List<Map<String, Object>> historyRows = new ArrayList<>();
            for (WarRecord record : history) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", record.warId());
                row.put("challenger", record.challenger());
                row.put("target", record.target());
                if (!record.winner().isEmpty()) {
                    row.put("winner", record.winner());
                }
                if (!record.result().isEmpty()) {
                    row.put("result", record.result());
                }
                row.put("wager", record.wager());
                row.put("ended-at", record.endedAt());
                historyRows.add(row);
            }
            data.put("history", historyRows);
        }
        return GSON.toJson(data);
    }

    /** Пишет файл, только если были изменения. */
    void saveNow() {
        if (finance != null || !healthy) return;
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
    void flush() {
        if (finance != null || !healthy) return;
        write(snapshotJson());
    }

    private void write(String json) {
        if (SafeJsonWriter.write(file, json, logger, "[CHRDK_Guilds]")) {
            synchronized (this) { dirty = false; }
        } else {
            // Диск не принял данные — помечаем состояние изменённым, иначе
            // следующее автосохранение решит, что писать нечего, и прогресс
            // потеряется молча.
            synchronized (this) {
                dirty = true;
            }
        }
    }

    /** Строгая граница: нельзя округлять деньги, чинить владельца или терять резерв. */
    private static void validateInput(Map<String,Object> data) {
        if (data == null || (number(data.get("version")) < 1 || number(data.get("version")) > VERSION) || !(data.get("guilds") instanceof List<?>)) bad("schema");
        if(data.containsKey("peace-notices")) {
            if(!(data.get("peace-notices") instanceof Map<?,?>))bad("peace outbox");
            Map<?,?> outbox=(Map<?,?>)data.get("peace-notices");if(outbox.size()>100_000)bad("peace outbox capacity");
            for(var entry:outbox.entrySet()) {
                strictUuid(entry.getKey());if(!(entry.getValue() instanceof List<?>))bad("peace notice");
                List<?> lines=(List<?>)entry.getValue();if(lines.size()>8)bad("peace notice capacity");
                for(Object text:lines)if(!(text instanceof String)||text.toString().length()>512)bad("peace notice text");
            }
        }
        if(data.containsKey("sanctions")) {
            if(!(data.get("sanctions") instanceof Map<?,?> fines) || fines.size()>100_000)bad("sanctions");
            for(var entry:((Map<?,?>)data.get("sanctions")).entrySet()) {
                strictUuid(entry.getKey()); if(!(entry.getValue() instanceof Map<?,?>))bad("sanction");
                Map<?,?> fine=(Map<?,?>)entry.getValue();
                range(fine,"debt",0,Long.MAX_VALUE,true);range(fine,"next-incident-at",0,Long.MAX_VALUE,true);
            }
        }
        var ids = new java.util.HashSet<String>();
        var names = new java.util.HashSet<String>();
        var tags = new java.util.HashSet<String>();
        var members = new java.util.HashSet<UUID>();
        var guildRows = rows(data,"guilds");
        if (guildRows.size() > GuildConfig.MAX_GUILDS_LIMIT) bad("guild capacity");
        for (Map<?,?> row : guildRows) {
            String id = text(row,"id",true), name = text(row,"name",true), tag = text(row,"tag",false);
            if (!ids.add(id) || !name.equals(Guild.cleanName(name)) || !tag.equals(Guild.cleanTag(tag))
                    || !names.add(GuildMath.key(name)) || !tag.isEmpty() && !tags.add(GuildMath.key(tag))) bad("guild identity");
            UUID leader = strictUuid(row.get("leader"));
            if (!(row.get("members") instanceof Map<?,?>)) bad("members");
            Map<?,?> roster = (Map<?,?>) row.get("members");
            if (roster.isEmpty() || !"leader".equals(roster.get(leader.toString()))) bad("leader");
            for (var member : roster.entrySet()) {
                UUID player = strictUuid(member.getKey());
                if (!members.add(player) || !(member.getValue() instanceof String)) bad("member identity");
                String rank = member.getValue().toString();
                if (!(rank.equals("leader") || rank.equals("officer") || rank.equals("member")) || rank.equals("leader") != player.equals(leader)) bad("rank");
            }
            if(number(data.get("version"))<3){
                for(String key:List.of("dues-rate","dues-changed-at","dues-reserve","dues-terms"))if(row.containsKey(key))bad("downgraded dues schema");
            }
            if(number(data.get("version"))>=3){
                if(!row.containsKey("mode")||!row.containsKey("mode-changed-at"))bad("missing mode");
                range(row,"dues-rate",10,1_000_000,true);range(row,"dues-changed-at",0,Long.MAX_VALUE-GuildPeaceTerm.DAY,true);
                range(row,"dues-reserve",0,Long.MAX_VALUE,true);
                if(!(row.get("dues-terms") instanceof Map<?,?> terms)||terms.size()!=roster.size())bad("dues roster");
                for(var entry:((Map<?,?>)row.get("dues-terms")).entrySet()){
                    if(!roster.containsKey(entry.getKey())||!(entry.getValue() instanceof Map<?,?>))bad("dues owner");
                    Map<?,?> term=(Map<?,?>)entry.getValue();
                    range(term,"due-at",1,Long.MAX_VALUE-GuildPeaceTerm.DAY,true);
                    range(term,"grace-since",0,Long.MAX_VALUE-GuildPeaceTerm.DAY,true);range(term,"amount",10,1_000_000,true);
                }
                if(number(row.get("dues-reserve"))>0&&number(row.get("treasury"))!=Guild.MAX_TREASURY)bad("unfunded dues reserve");
                Math.addExact(Math.addExact(number(row.get("treasury")),number(row.get("war-returns"))),number(row.get("dues-reserve")));
            }
            if(row.containsKey("mode")) {
                String mode=text(row,"mode",true);
                if(!(mode.equals("BATTLE")||mode.equals("PEACEFUL")))bad("mode");
                range(row,"mode-changed-at",0,Long.MAX_VALUE,true);
                if(mode.equals("PEACEFUL")) {
                    if(!(row.get("peace-terms") instanceof Map<?,?> terms) || terms.size()!=roster.size())bad("peace roster");
                    for(var entry:((Map<?,?>)row.get("peace-terms")).entrySet()) {
                        if(!roster.containsKey(entry.getKey()) || !(entry.getValue() instanceof Map<?,?>))bad("peace owner");
                        Map<?,?> term=(Map<?,?>)entry.getValue();
                        range(term,"paid-until",1,Long.MAX_VALUE-GuildPeaceTerm.DAY,true);
                        range(term,"grace-since",0,Long.MAX_VALUE-GuildPeaceTerm.DAY,true);
                        range(term,"activates-at",0,number(term.get("paid-until"))-1,true);
                    }
                } else if(row.containsKey("peace-terms"))bad("battle terms");
            } else if(row.containsKey("peace-terms")||row.containsKey("mode-changed-at"))bad("orphan peace terms");
            range(row,"created-at",0,Long.MAX_VALUE,true);
            range(row,"treasury",0,Guild.MAX_TREASURY,true);
            range(row,"war-returns",0,Long.MAX_VALUE,false);
            if (number(row.get("war-returns")) > 0 && number(row.get("treasury")) != Guild.MAX_TREASURY) bad("unfunded return");
            Math.addExact(number(row.get("treasury")),number(row.get("war-returns")));
            range(row,"level",1,GuildMath.MAX_LEVEL,true);
            for (String key : List.of("glory","wars-won","wars-lost")) range(row,key,0,Integer.MAX_VALUE,true);
            range(row,"war-cooldown-until",0,Long.MAX_VALUE,false);
        }
        var invited = new java.util.HashSet<UUID>();
        for (Map<?,?> row : rows(data,"invites")) {
            if (!ids.contains(text(row,"guild",true))) bad("invite guild");
            text(row,"guild-name",true);
            UUID player = strictUuid(row.get("player"));
            if (!invited.add(player) || members.contains(player)) bad("invite owner");
            if (row.containsKey("inviter")) strictUuid(row.get("inviter"));
            range(row,"created-at",0,Long.MAX_VALUE,true);
            range(row,"expires-at",0,Long.MAX_VALUE,true);
        }
        var warIds = new java.util.HashSet<String>();
        var reserved = new java.util.HashSet<String>();
        for (Map<?,?> row : rows(data,"wars")) {
            String challenger = text(row,"challenger",true), target = text(row,"target",true);
            if (!warIds.add(text(row,"id",true)) || !ids.contains(challenger) || !ids.contains(target)
                    || challenger.equals(target) || !reserved.add(challenger) || !reserved.add(target)) bad("war ownership");
            for(var guildRow:guildRows)if((challenger.equals(string(guildRow.get("id")))||target.equals(string(guildRow.get("id"))))&&"PEACEFUL".equals(guildRow.get("mode")))bad("peace war");
            String status = text(row,"status",true);
            if (!(status.equals("PENDING") || status.equals("ACTIVE")) || row.containsKey("result")) bad("closed/unknown war");
            range(row,"wager",0,Guild.MAX_TREASURY,true);
            for (String key : List.of("created-at","starts-at","ends-at")) range(row,key,0,Long.MAX_VALUE,true);
            for (String key : List.of("challenger-points","target-points")) range(row,key,0,Integer.MAX_VALUE,true);
            if (status.equals("PENDING") && (number(row.get("starts-at")) != 0 || number(row.get("ends-at")) != 0)) bad("pending war time");
            if (status.equals("ACTIVE") && number(row.get("ends-at")) < number(row.get("starts-at"))) bad("active war time");
            if (row.containsKey("last-kills")) {
                if (!(row.get("last-kills") instanceof Map<?,?>)) bad("kill receipts");
                for (var entry : ((Map<?,?>)row.get("last-kills")).entrySet()) {
                    if (!(entry.getKey() instanceof String) || entry.getValue() == null || number(entry.getValue()) <= 0) bad("kill receipt");
                    String[] pair = entry.getKey().toString().split("\\|",-1);
                    if (pair.length != 2) bad("kill pair");
                    strictUuid(pair[0]); strictUuid(pair[1]);
                    if (pair[0].compareTo(pair[1]) >= 0) bad("kill pair order");
                }
            }
        }
        var records = rows(data,"history");
        if (records.size() > MAX_HISTORY) bad("history capacity");
        for (Map<?,?> row : records) {
            text(row,"id",true); text(row,"challenger",true); text(row,"target",true);
            text(row,"winner",false); String result = text(row,"result",false);
            if (!result.isEmpty()) GuildWar.Result.valueOf(result);
            range(row,"wager",0,Guild.MAX_TREASURY,true);
            range(row,"ended-at",0,Long.MAX_VALUE,true);
        }
    }

    private static List<Map<?,?>> rows(Map<?,?> data,String key) {
        if (!data.containsKey(key)) return List.of();
        if (!(data.get(key) instanceof List<?>)) throw new IllegalArgumentException("Invalid rows: " + key);
        var result = new ArrayList<Map<?,?>>();
        for (Object element : (List<?>)data.get(key)) {
            if (!(element instanceof Map<?,?> row)) throw new IllegalArgumentException("Invalid row: " + key);
            result.add(row);
        }
        return result;
    }
    private static void range(Map<?,?> row,String key,long min,long max,boolean required) {
        if (!row.containsKey(key) && !required) return;
        if (row.get(key) == null) bad("missing " + key);
        long value = number(row.get(key));
        if (value < min || value > max) bad("range " + key);
    }
    private static String text(Map<?,?> row,String key,boolean required) {
        if (!row.containsKey(key) && !required) return "";
        if (!(row.get(key) instanceof String value) || value.length() > 160 || !value.equals(value.trim()) || required && value.isBlank()) throw new IllegalArgumentException("Invalid text: " + key);
        return value;
    }
    private static UUID strictUuid(Object value) {
        if (!(value instanceof String raw) || !UUID.fromString(raw).toString().equals(raw)) throw new IllegalArgumentException("Noncanonical UUID");
        return UUID.fromString(raw);
    }
    private static void bad(String reason) { throw new IllegalArgumentException("Invalid guild source: " + reason); }

    /* -------------------------------- помощники ------------------------------- */

    private static String string(Object raw) {
        return raw == null ? "" : raw.toString().trim();
    }

    private static long number(Object raw) {
        if (raw == null) return 0;
        if (!(raw instanceof Number)) throw new IllegalArgumentException("Not a number");
        return new java.math.BigDecimal(raw.toString()).longValueExact();
    }

    private static UUID uuid(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw.toString().trim());
        } catch (IllegalArgumentException broken) {
            return null;
        }
    }

    /** Обёртки разбора перечислений: неизвестное значение не роняет загрузку. */
    private static final class Status {
        static GuildWar.Status parse(String raw) {
            return switch (raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT)) {
                case "ACTIVE" -> GuildWar.Status.ACTIVE;
                case "FINISHED" -> GuildWar.Status.FINISHED;
                default -> GuildWar.Status.PENDING;
            };
        }
    }

    private static final class ResultValue {
        static GuildWar.Result parse(String raw) {
            return switch (raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT)) {
                case "CHALLENGER_WIN" -> GuildWar.Result.CHALLENGER_WIN;
                case "TARGET_WIN" -> GuildWar.Result.TARGET_WIN;
                case "DRAW" -> GuildWar.Result.DRAW;
                case "CANCELLED" -> GuildWar.Result.CANCELLED;
                default -> null;
            };
        }
    }
}
