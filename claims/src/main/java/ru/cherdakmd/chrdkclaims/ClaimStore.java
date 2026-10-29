package ru.cherdakmd.chrdkclaims;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Данные приватов (claims-data.json): чанки по мирам (запись плоским списком),
 * казна игроков, отложенные вести (долги/утраты) и статистика.
 *
 * <p>Запись: снимок под блокировкой → JSON во временный файл → атомарная замена;
 * предыдущая версия сохраняется в {@code claims-data.json.bak}. Повреждённый файл
 * не перезаписывается, а откладывается рядом как {@code claims-data.json.corrupt-<millis>}.
 * Чтение и снимок синхронизированы, поэтому асинхронное сохранение не мешает игре.</p>
 */
public final class ClaimStore {

    /** Версия схемы данных (для будущих миграций). */
    public static final int SCHEMA_VERSION = 4;
    /** Сколько отложенных вестей хранить на игрока (старые вытесняются). */
    public static final int MAX_NOTICES = 40;

    private static final Type DATA_TYPE = new TypeToken<LinkedHashMap<String, Object>>() {
    }.getType();
    private static final Gson GSON = new GsonBuilder().setObjectToNumberStrategy(com.google.gson.ToNumberPolicy.BIG_DECIMAL).setPrettyPrinting().create();

    private final Path file;
    private final Path backup;
    private final Logger logger;
    /** world → (chunkKey → Claim). */
    private final Map<String, Map<Long, Claim>> claims = new HashMap<>();
    private ClaimAccounts accounts;
    ClaimFinance finance;
    private java.util.UUID financialLineage=java.util.UUID.randomUUID();
    private boolean financialRegistered;
    private long financialSequence;
    private ClaimFinance.Pending financialPending;
    @FunctionalInterface interface WriteCheckpoint { void afterReplace() throws IOException; }
    private final WriteCheckpoint checkpoint;
    synchronized java.util.UUID financialLineage() { return financialLineage; }
    synchronized boolean financialRegistered() { return financialRegistered; }
    synchronized long financialSequence() { return financialSequence; }
    synchronized ClaimFinance.Pending financialPending() { return financialPending; }
    synchronized long financeRevision() { return revision; }
    synchronized boolean ensureFinance() { save(); return moneyAvailable()&&(financialPending!=null||!dirty)&&Files.isRegularFile(file); }
    synchronized boolean markFinanceRegistered() {
        if(!moneyAvailable()) return false; if(financialRegistered) return true;
        var data=snapshot().data(); financeMap(data).put("registered",true);
        if(!write(data)) return false; financialRegistered=true; dirty=false; return true;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> financeMap(Map<String,Object> data) { return (Map<String,Object>) data.get("finance"); }
    private Map<String,Object> financialMetadata() {
        var data=new LinkedHashMap<String,Object>(); data.put("lineage",financialLineage.toString()); data.put("registered",financialRegistered); data.put("sequence",financialSequence);
        if(financialPending!=null) data.put("pending",financialPending); return data;
    }
    synchronized String domainJson() { var data=snapshot().data(); data.remove("finance"); return GSON.toJson(canonical(GSON.toJsonTree(data))); }
    private static com.google.gson.JsonElement canonical(com.google.gson.JsonElement value) {
        if(value.isJsonObject()) { var result=new com.google.gson.JsonObject(); var sorted=new java.util.TreeMap<String,com.google.gson.JsonElement>(); value.getAsJsonObject().entrySet().forEach(e->sorted.put(e.getKey(),e.getValue())); sorted.forEach((k,v)->result.add(k,canonical(v))); return result; }
        if(value.isJsonArray()) { var result=new com.google.gson.JsonArray(); value.getAsJsonArray().forEach(v->result.add(canonical(v)));return result; } return value;
    }
    synchronized ClaimStore financeCopy(ClaimAccounts port) {
        var copy=new ClaimStore(file.getParent(),logger); var data=snapshot().data(); data.remove("finance"); copy.read(data); copy.accounts=port; return copy;
    }
    synchronized void stopFinance(String message) { moneyAvailable=false; blocked=true; logger.severe("[Claims] "+message+"; файл не перезаписывается"); }
    synchronized boolean prepareFinance(ClaimFinance.Pending p) {
        if(!moneyAvailable()||!financialRegistered||financialPending!=null||!p.valid()||!p.exchange().lineage().equals(financialLineage)||p.exchange().sequence()!=financialSequence+1||!p.beforeHash().equals(ru.cherdakmd.chrdkregister.api.ClaimExchange.hash(domainJson()))) return false;
        try { validateDomain(GSON.fromJson(p.after(),com.google.gson.JsonObject.class)); } catch(RuntimeException e) {return false;}
        var data=snapshot().data(); financeMap(data).put("pending",p);
        if(!write(data)) return false; financialPending=p; dirty=false; return true;
    }
    synchronized boolean cancelFinance(ClaimFinance.Pending p) {
        if(!moneyAvailable()||!p.equals(financialPending)) return false;
        if(!p.beforeHash().equals(ru.cherdakmd.chrdkregister.api.ClaimExchange.hash(domainJson()))) {stopFinance("Изменился domain при отмене");return false;}
        var data=snapshot().data(); financeMap(data).remove("pending"); if(!write(data)) return false; financialPending=null; dirty=false; return true;
    }
    synchronized boolean finishFinance(ClaimFinance.Pending p) {
        if(!moneyAvailable()||!p.equals(financialPending)) return false;
        if(!p.beforeHash().equals(ru.cherdakmd.chrdkregister.api.ClaimExchange.hash(domainJson()))) {stopFinance("Изменился зарезервированный domain snapshot");return false;}
        Map<String,Object> after=GSON.fromJson(p.after(),DATA_TYPE); var meta=financialMetadata(); meta.remove("pending"); meta.put("sequence",p.exchange().sequence()); after.put("finance",meta);
        if(!write(after)) return false;
        publishDomain(after); financialSequence=p.exchange().sequence(); financialPending=null; dirty=false; return true;
    }
    private void publishDomain(Map<String,Object> data) {
        var copy=new ClaimStore(file.getParent(),logger); copy.read(data);
        for(var world:copy.claims.values()) for(var e:new ArrayList<>(world.entrySet())) {
            var n=e.getValue(); var old=claimAt(n.world,n.x,n.z); if(old==null) continue;
            old.owner=n.owner; old.paidUntil=n.paidUntil; old.debtDays=n.debtDays; old.salePrice=n.salePrice; old.greeting=n.greeting;
            old.rentOffer=n.rentOffer; old.tenant=n.tenant; old.rentPrice=n.rentPrice; old.rentUntil=n.rentUntil; old.rentRenew=n.rentRenew;
            old.clearMembers(); old.members.addAll(n.members); n.explicitRoles().forEach(old::applyRole); world.put(e.getKey(),old);
        }
        claims.clear(); claims.putAll(copy.claims); treasury.clear(); treasury.putAll(copy.treasury); notices.clear(); notices.putAll(copy.notices); limits.clear(); limits.putAll(copy.limits);
        chunksClaimedTotal=copy.chunksClaimedTotal; diamondsSpent=copy.diamondsSpent; taxBurned=copy.taxBurned; revision++;
    }
    synchronized Claim protectedClaimAt(String world,int x,int z) {
        var before=claimAt(world,x,z); if(before!=null||financialPending==null) return before;
        var copy=new ClaimStore(file.getParent(),logger); copy.read(GSON.fromJson(financialPending.after(),DATA_TYPE)); return copy.claimAt(world,x,z);
    }

    private volatile boolean moneyAvailable = true;
    private static final class LegacyMoneyException extends IllegalArgumentException {
        LegacyMoneyException(String message) { super(message); }
    }
    public boolean moneyAvailable() { return moneyAvailable && !blocked; }
    void accounts(ClaimAccounts accounts) { this.accounts = java.util.Objects.requireNonNull(accounts); }
    boolean externalAccounts() { return accounts != null; }
    long balanceLimit() { return accounts == null ? Integer.MAX_VALUE : 1_000_000_000L; }
    boolean fundsReady(String name) { return accounts == null ? moneyAvailable() : accounts.ready(name); }
    Map<String, Integer> legacyTreasuries() { return Map.copyOf(treasury); }
    synchronized int legacyTreasuryOf(String name) {
        Integer value = treasury.get(keyIgnoreCase(treasury, name)); return value == null ? 0 : value;
    }
    boolean transferFunds(Map<String, Long> changes, String inventoryPayer) {
        if (accounts != null) return accounts.exchange(changes, inventoryPayer);
        // Чистый embedded-режим старых unit-тестов. ClaimsPlugin всегда ставит Core-мост до таймеров.
        synchronized (this) {
            var next = new LinkedHashMap<String, Long>();
            for (var entry : changes.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) return false;
                next.merge(entry.getKey().toLowerCase(java.util.Locale.ROOT), entry.getValue(), Long::sum);
            }
            for (var entry : next.entrySet()) {
                long value = (long) legacyTreasuryOf(entry.getKey()) + entry.getValue();
                if (value < 0 || value > Integer.MAX_VALUE) return false;
            }
            for (var entry : next.entrySet()) {
                String key = keyIgnoreCase(treasury, entry.getKey());
                treasury.put(key == null ? entry.getKey() : key, (int) (legacyTreasuryOf(entry.getKey()) + entry.getValue()));
            }
            touch(); return true;
        }
    }
    private final Map<String, Integer> treasury = new LinkedHashMap<>();
    private final Map<String, List<String>> notices = new LinkedHashMap<>();
    /** Персональные лимиты чанков, выданные администратором (ник → чанков). */
    private final Map<String, Integer> limits = new LinkedHashMap<>();
    private final AtomicBoolean saving = new AtomicBoolean();
    private final Object writeLock = new Object();

    private long chunksClaimedTotal;
    private long diamondsSpent;
    private long taxBurned;
    private long revision;
    private boolean dirty;
    /** Запись запрещена: повреждённый файл не удалось отложить, затирать его нельзя. */
    private volatile boolean blocked;

    public ClaimStore(Path dataFolder, Logger logger) {
        this(dataFolder,logger,()->{});
    }
    ClaimStore(Path dataFolder,Logger logger,WriteCheckpoint checkpoint) {
        this.checkpoint=checkpoint;
        this.file = dataFolder.resolve("claims-data.json");
        this.backup = dataFolder.resolve("claims-data.json.bak");
        this.logger = logger;
    }

    // ------------------------------------------------------------------ io --

    public synchronized void load() {
        reset();
        if (!Files.exists(file)) return;
        Map<String, Object> data;
        try {
            data = GSON.fromJson(ClaimJson.read(file), DATA_TYPE);
        } catch (IOException | RuntimeException exception) {
            quarantine(exception);
            return;
        }
        if (data == null) {
            quarantine(new IllegalArgumentException("Пустой файл приватов"));
            return;
        }
        try {
            read(data);
            var json=GSON.toJsonTree(data).getAsJsonObject();
            if(longOf(data.get("version"))>=4) {
                try {
                    validateDomain(json); var f=json.getAsJsonObject("finance"); if(f==null) throw new IllegalArgumentException("missing finance");
                    financialLineage=ru.cherdakmd.chrdkregister.api.MilestoneMigration.uuid(text(f,"lineage"));
                    var registered=f.get("registered"); if(registered==null||!registered.isJsonPrimitive()||!registered.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("registration ACK");
                    financialRegistered=registered.getAsBoolean(); financialSequence=integer(f,"sequence"); if(financialSequence<0||financialSequence>=Long.MAX_VALUE||!financialRegistered&&financialSequence!=0) throw new IllegalArgumentException("claim sequence");
                    if(f.has("pending")) {
                        var row=f.getAsJsonObject("pending"); ru.cherdakmd.chrdkregister.api.ClaimExchange.requireJson(row.getAsJsonObject("exchange"));
                        text(row,"beforeHash"); text(row,"after"); financialPending=GSON.fromJson(row,ClaimFinance.Pending.class);
                        if(!financialRegistered||!financialPending.valid()||!financialLineage.equals(financialPending.exchange().lineage())||financialPending.exchange().sequence()!=financialSequence+1||!financialPending.beforeHash().equals(ru.cherdakmd.chrdkregister.api.ClaimExchange.hash(domainJson()))) throw new IllegalArgumentException("claim WAL");
                        validateDomain(GSON.fromJson(financialPending.after(),com.google.gson.JsonObject.class));
                    }
                } catch(RuntimeException e) { throw new LegacyMoneyException("Неверный финансовый domain/WAL: "+e.getMessage()); }
            } else if(json.has("finance")) throw new LegacyMoneyException("Пониженная схема с финансовым WAL");
            dirty = (int) longOf(data.get("version")) < SCHEMA_VERSION;
        } catch (LegacyMoneyException exception) {
            treasury.clear(); // Территории уже прочитаны: финансовая ошибка не снимает их защиту.
            quarantine(exception);
        } catch (RuntimeException exception) {
            reset();
            quarantine(exception);
        }
    }

    /** Пишет данные синхронно. Вызывается из onDisable и асинхронной задачи. */
    public synchronized void save() {
        if(blocked||financialPending!=null||!dirty&&Files.isRegularFile(file)) return;
        var snapshot=snapshot(); if(write(snapshot.data())) dirty=false;
    }

    /** Планирует асинхронную запись; параллельные запуски не дублируются. */
    public void saveAsync(org.bukkit.plugin.Plugin plugin) {
        if (!dirty || blocked || plugin == null) {
            return;
        }
        if (!saving.compareAndSet(false, true)) {
            return;
        }
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    save();
                } finally {
                    saving.set(false);
                }
            });
        } catch (RuntimeException exception) {
            saving.set(false);
            logger.log(Level.WARNING, "[CHRDK_Claims] Не удалось запланировать сохранение: "
                    + exception.getMessage(), exception);
        }
    }

    public synchronized void markDirty() {
        touch();
    }

    private void reset() {
        moneyAvailable = true;
        financialLineage=java.util.UUID.randomUUID(); financialRegistered=false; financialSequence=0; financialPending=null;
        claims.clear();
        treasury.clear();
        notices.clear();
        limits.clear();
        chunksClaimedTotal = 0;
        diamondsSpent = 0;
        taxBurned = 0;
        revision = 0;
        dirty = false;
        blocked = false;
    }

    private void touch() {
        dirty = true;
        revision++;
    }

    @SuppressWarnings("unchecked")
    private void read(Map<String, Object> data) {
        int version = (int) longOf(data.get("version"));
        if (version > SCHEMA_VERSION) throw new LegacyMoneyException("Неизвестная версия claims-data.json");
        long now = System.currentTimeMillis();
        int repairedDates = 0;
        int duplicates = 0;
        Object rawClaims = data.get("claims");
        if (rawClaims instanceof List<?> list) {
            for (Object entry : list) {
                if (!(entry instanceof Map<?, ?> map)) {
                    continue;
                }
                String world = str(map.get("w"));
                String owner = str(map.get("o"));
                if (world == null || world.isBlank() || owner == null || owner.isBlank()) {
                    continue;
                }
                int x = (int) longOf(map.get("x"));
                int z = (int) longOf(map.get("z"));
                long paidUntil = longOf(map.get("p"));
                if (paidUntil <= 0) {
                    paidUntil = now;
                    repairedDates++;
                }
                Claim claim = new Claim(world, x, z, owner, paidUntil);
                claim.debtDays = clamp((int) longOf(map.get("d")), 0, 100_000);
                claim.salePrice = clamp((int) longOf(map.get("s")), 0, 1_000_000);
                claim.greeting = Claim.normalizeGreeting(str(map.get("g")));
                Set<String> seenMembers = new LinkedHashSet<>();
                if (map.get("m") instanceof List<?> members) {
                    for (Object member : members) {
                        if (member == null) {
                            continue;
                        }
                        String name = String.valueOf(member).trim();
                        if (name.isEmpty() || claim.isOwner(name)
                                || !seenMembers.add(name.toLowerCase(Locale.ROOT))) {
                            continue;
                        }
                        claim.members.add(name);
                    }
                }
                // Роли участников: только известные значения, только для участников.
                if (map.get("mr") instanceof Map<?, ?> roleMap) {
                    for (Map.Entry<?, ?> roleEntry : roleMap.entrySet()) {
                        if (roleEntry.getKey() == null || roleEntry.getValue() == null) {
                            continue;
                        }
                        String member = String.valueOf(roleEntry.getKey()).trim();
                        Claim.Role role = parseRole(String.valueOf(roleEntry.getValue()).trim());
                        if (role != null) {
                            claim.applyRole(member, role);
                        }
                    }
                }
                claim.rentOffer = clamp((int) longOf(map.get("ro")), 0, 1_000_000);
                String tenant = str(map.get("rt"));
                int rentPrice = clamp((int) longOf(map.get("rp")), 0, 1_000_000);
                long rentUntil = longOf(map.get("ru"));
                if (tenant != null && !tenant.isBlank() && !claim.isOwner(tenant) && rentPrice > 0 && rentUntil > 0) {
                    claim.tenant = tenant;
                    claim.rentPrice = rentPrice;
                    claim.rentUntil = rentUntil;
                    claim.rentRenew = Boolean.TRUE.equals(map.get("rr"));
                }
                Claim previous = claims.computeIfAbsent(world, key -> new HashMap<>())
                        .putIfAbsent(claim.key(), claim);
                if (previous != null) {
                    duplicates++;
                }
            }
        }
        if (repairedDates > 0) {
            logger.warning("[CHRDK_Claims] В claims-data.json " + repairedDates
                    + " приват(ов) без корректной даты оплаты — рента начнёт начисляться с этого запуска.");
        }
        if (duplicates > 0) {
            logger.warning("[CHRDK_Claims] В claims-data.json найдено " + duplicates
                    + " дубликат(ов) чанков — оставлены первые записи.");
        }

        if (data.get("treasury") != null && !(data.get("treasury") instanceof Map<?, ?>)) throw new LegacyMoneyException("Неверная казна");
        if (data.get("treasury") instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                String name = String.valueOf(entry.getKey());
                if (entry.getKey() == null || name.isBlank() || name.length() > 16 || keyIgnoreCase(treasury, name) != null
                        || !(entry.getValue() instanceof Number number) || !Double.isFinite(number.doubleValue())
                        || number.doubleValue() != Math.rint(number.doubleValue()) || number.doubleValue() < 0 || number.doubleValue() > Integer.MAX_VALUE)
                    throw new LegacyMoneyException("Неверный остаток казны: " + name);
                treasury.put(name, ((Number) entry.getValue()).intValue());
            }
        }
        if (data.get("notices") instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getValue() instanceof List<?> list) || entry.getKey() == null) {
                    continue;
                }
                List<String> lines = new ArrayList<>();
                for (Object line : list) {
                    if (line != null) {
                        lines.add(String.valueOf(line));
                    }
                }
                trimNotices(lines);
                if (!lines.isEmpty()) {
                    notices.put(String.valueOf(entry.getKey()), lines);
                }
            }
        }
        if (data.get("limits") instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                String name = String.valueOf(entry.getKey()).trim();
                int limit = clamp((int) longOf(entry.getValue()), 1, 100_000);
                if (!name.isEmpty()) {
                    limits.put(name, limit);
                }
            }
        }
        for (List<Claim> group : ClaimTerritories.groups(all())) {
            if (group.size() > 1) ClaimTerritories.normalize(group, group, version < 3);
        }
        if (version < 3) logger.info("[CHRDK_Claims] Территории объединены по общей стороне. Старые лоты объединённых территорий сняты; доступ — безопасное пересечение участников.");
        if (data.get("stats") instanceof Map<?, ?> stats) {
            chunksClaimedTotal = Math.max(0, longOf(stats.get("chunksClaimedTotal")));
            diamondsSpent = Math.max(0, longOf(stats.get("diamondsSpent")));
            taxBurned = Math.max(0, longOf(stats.get("taxBurned")));
        }
    }

    private Snapshot snapshot() {
        synchronized (this) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("version", SCHEMA_VERSION); data.put("finance",financialMetadata());
            List<Map<String, Object>> claimsList = new ArrayList<>();
            for (Map<Long, Claim> world : claims.values()) {
                for (Claim claim : world.values()) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("w", claim.world);
                    entry.put("x", claim.x);
                    entry.put("z", claim.z);
                    entry.put("o", claim.owner);
                    entry.put("p", claim.paidUntil);
                    entry.put("d", claim.debtDays);
                    entry.put("s", claim.salePrice);
                    entry.put("ro", claim.rentOffer);
                    if (claim.tenant != null && !claim.tenant.isBlank()) {
                        entry.put("rt", claim.tenant); entry.put("rp", claim.rentPrice);
                        entry.put("ru", claim.rentUntil); entry.put("rr", claim.rentRenew);
                    }
                    if (claim.hasGreeting()) {
                        entry.put("g", claim.greeting);
                    }
                    if (!claim.members.isEmpty()) {
                        entry.put("m", new ArrayList<>(claim.members));
                        Map<String, Claim.Role> explicit = claim.explicitRoles();
                        if (!explicit.isEmpty()) {
                            Map<String, String> saved = new LinkedHashMap<>();
                            explicit.forEach((name, role) -> saved.put(name, role.name()));
                            entry.put("mr", saved);
                        }
                    }
                    claimsList.add(entry);
                }
            }
            claimsList.sort(java.util.Comparator.comparing((Map<String,Object> r)->r.get("w").toString()).thenComparingInt(r->((Number)r.get("x")).intValue()).thenComparingInt(r->((Number)r.get("z")).intValue()));
            data.put("claims", claimsList);
            data.put("treasury", new LinkedHashMap<>(treasury));
            if (!limits.isEmpty()) {
                data.put("limits", new LinkedHashMap<>(limits));
            }
            if (!notices.isEmpty()) {
                Map<String, List<String>> copy = new LinkedHashMap<>();
                notices.forEach((name, lines) -> copy.put(name, new ArrayList<>(lines)));
                data.put("notices", copy);
            }
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("chunksClaimedTotal", chunksClaimedTotal);
            stats.put("diamondsSpent", diamondsSpent);
            stats.put("taxBurned", taxBurned);
            data.put("stats", stats);
            return new Snapshot(data, revision);
        }
    }

    private boolean write(Map<String,Object> data) {
        if(blocked) return false;
        try {
            String json=GSON.toJson(data); if(json.getBytes(StandardCharsets.UTF_8).length>64L*1024*1024) throw new IOException("claim capacity");
            Files.createDirectories(file.getParent()); var tmp=file.resolveSibling("claims-data.json.tmp");
            Files.writeString(tmp,json,StandardCharsets.UTF_8);
            try(var channel=java.nio.channels.FileChannel.open(tmp,java.nio.file.StandardOpenOption.WRITE)) {channel.force(true);}
            if(Files.isRegularFile(file)) Files.copy(file,backup,StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); checkpoint.afterReplace();
            try(var channel=java.nio.channels.FileChannel.open(file.getParent(),java.nio.file.StandardOpenOption.READ)) {channel.force(true);}
            return true;
        } catch(IOException|RuntimeException e) {stopFinance("Неоднозначный сбой записи: "+e.getMessage());return false;}
    }
    private static String text(com.google.gson.JsonObject row,String field) {
        var v=row.get(field); if(v==null||!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("claim text "+field);return v.getAsString();
    }
    private static long integer(com.google.gson.JsonObject row,String field) {
        var v=row.get(field); if(v==null||!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("claim number "+field);return v.getAsBigDecimal().longValueExact();
    }
    private static void validateDomain(com.google.gson.JsonObject json) {
        if(integer(json,"version")!=4||!json.has("claims")||!json.get("claims").isJsonArray()||!json.has("treasury")||!json.get("treasury").isJsonObject()) throw new IllegalArgumentException("claim domain");
        var seen=new java.util.HashSet<String>();
        for(var entry:json.getAsJsonArray("claims")) {
            var row=entry.getAsJsonObject(); var world=text(row,"w"); var owner=text(row,"o"); if(world.isBlank()||owner.isBlank()) throw new IllegalArgumentException("claim identity");
            for(String f:java.util.List.of("x","z","d","s","ro")) {long n=integer(row,f); Math.toIntExact(n); if(!f.equals("x")&&!f.equals("z")&&n<0) throw new IllegalArgumentException("negative claim counter");}
            if(integer(row,"p")<1||!seen.add(world+":"+integer(row,"x")+":"+integer(row,"z"))) throw new IllegalArgumentException("claim date/duplicate");
            if(row.has("rt")) {text(row,"rt");if(integer(row,"rp")<0||integer(row,"ru")<0)throw new IllegalArgumentException("rent amount/date");var renew=row.get("rr");if(renew==null||!renew.isJsonPrimitive()||!renew.getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException("rent renew");}
        }
        var stats=json.getAsJsonObject("stats"); for(String f:java.util.List.of("chunksClaimedTotal","diamondsSpent","taxBurned")) if(integer(stats,f)<0) throw new IllegalArgumentException("claim statistic");
    }

    /** Повреждённый файл не затирается: откладываем его рядом для ручного восстановления. */
    private void quarantine(Exception exception) {
        moneyAvailable = false;
        blocked = true;
        Path broken = file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis());
        try {
            Files.copy(file, broken, StandardCopyOption.REPLACE_EXISTING);
            logger.log(Level.SEVERE, "[CHRDK_Claims] claims-data.json не удалось прочитать ("
                    + exception.getMessage() + "). Исходник не изменён, копия сохранена как " + broken.getFileName()
                    + "; запись и финансовые операции приостановлены. Проверьте резервную копию "
                    + backup.getFileName() + ".", exception);
        } catch (IOException moveException) {
            blocked = true;
            logger.log(Level.SEVERE, "[CHRDK_Claims] claims-data.json повреждён, и его не удалось "
                    + "отложить в сторону. Запись новых данных остановлена, чтобы не затереть файл: "
                    + "освободите место/права и перезапустите сервер.", moveException);
        }
    }

    private record Snapshot(Map<String, Object> data, long revision) {
    }

    // ------------------------------------------------------------ backups --

    /** Файл резервной копии для списка администратора. */
    public record BackupEntry(String name, long size, long modified) {
    }

    /** Все копии рядом с данными: `.bak`, `.corrupt-*`, `.pre-restore-*` (свежие первыми). */
    public synchronized List<BackupEntry> backups() {
        Path parent = file.getParent();
        List<BackupEntry> entries = new ArrayList<>();
        if (parent == null || !Files.isDirectory(parent)) {
            return entries;
        }
        try (var stream = Files.list(parent)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("claims-data.json"))
                    .forEach(path -> {
                        try {
                            entries.add(new BackupEntry(path.getFileName().toString(),
                                    Files.size(path), Files.getLastModifiedTime(path).toMillis()));
                        } catch (IOException exception) {
                            logger.log(Level.WARNING, "[CHRDK_Claims] Не удалось прочитать "
                                    + path.getFileName() + ": " + exception.getMessage());
                        }
                    });
        } catch (IOException exception) {
            logger.log(Level.WARNING, "[CHRDK_Claims] Не удалось перечислить резервные копии: "
                    + exception.getMessage(), exception);
            return entries;
        }
        entries.sort((left, right) -> Long.compare(right.modified(), left.modified()));
        return entries;
    }

    /** Итог восстановления: успех, файла нет, имя недопустимо или файл нечитаем. */
    public enum RestoreResult {
        OK, NOT_FOUND, INVALID, IO_ERROR, PROTECTED
    }

    /**
     * Восстанавливает данные из копии рядом с {@code claims-data.json}.
     *
     * <p>Имя принимается только как имя файла в папке данных (без путей и «..»),
     * содержимое сначала проверяется разбором JSON, текущие данные сохраняются как
     * {@code claims-data.json.pre-restore-<millis>}. Так неудачное восстановление
     * не может уничтожить рабочий файл.</p>
     */
    public synchronized RestoreResult restoreFrom(String fileName) {
        if(finance!=null||financialRegistered||financialPending!=null) return RestoreResult.PROTECTED;
        if (all().stream().anyMatch(c -> c.rentedAt(System.currentTimeMillis()))) return RestoreResult.PROTECTED;
        if (fileName == null || fileName.isBlank()) {
            return RestoreResult.INVALID;
        }
        String name = fileName.trim();
        if (name.contains("/") || name.contains("\\") || name.contains("..")
                || !name.startsWith("claims-data.json")) {
            return RestoreResult.INVALID;
        }
        Path source = file.resolveSibling(name);
        if (source.equals(file) || !Files.isRegularFile(source)) {
            return RestoreResult.NOT_FOUND;
        }
        if (!isReadableData(source)) {
            return RestoreResult.INVALID;
        }
        try {
            // Тот же лок, что у save(): незавершённая запись не перезапишет восстановленное.
            synchronized (writeLock) {
                if (Files.isRegularFile(file)) {
                    Files.copy(file, file.resolveSibling(
                                    "claims-data.json.pre-restore-" + System.currentTimeMillis()),
                            StandardCopyOption.REPLACE_EXISTING);
                }
                Files.copy(source, file, StandardCopyOption.REPLACE_EXISTING);
                load();
            }
            logger.info("[CHRDK_Claims] Данные восстановлены из " + name + ".");
            return RestoreResult.OK;
        } catch (IOException exception) {
            logger.log(Level.SEVERE, "[CHRDK_Claims] Не удалось восстановить данные из "
                    + name + ": " + exception.getMessage(), exception);
            return RestoreResult.IO_ERROR;
        }
    }

    /** Проверка, что файл — корректный JSON-объект данных (до подмены рабочего файла). */
    private boolean isReadableData(Path source) {
        try (Reader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            Map<String, Object> data = GSON.fromJson(reader, DATA_TYPE);
            return data != null;
        } catch (IOException | RuntimeException exception) {
            logger.warning("[CHRDK_Claims] Копия " + source.getFileName()
                    + " не читается как данные приватов: " + exception.getMessage());
            return false;
        }
    }

    // -------------------------------------------------------------- claims --

    public synchronized Claim claimAt(String world, int chunkX, int chunkZ) {
        Map<Long, Claim> inWorld = claims.get(world);
        return inWorld == null ? null : inWorld.get(ClaimMath.chunkKey(chunkX, chunkZ));
    }

    /** Общая территория; записи оплаты и аренды по-прежнему принадлежат отдельным чанкам. */
    public synchronized List<Claim> territory(Claim claim) {
        if (claim == null || claimAt(claim.world, claim.x, claim.z) != claim) return List.of();
        // Проход индексированных соседей: стоимость зависит от размера территории, не всего мира.
        List<Claim> result = new ArrayList<>();
        java.util.ArrayDeque<Claim> queue = new java.util.ArrayDeque<>();
        Set<Long> seen = new java.util.HashSet<>();
        queue.add(claim); seen.add(claim.key());
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!queue.isEmpty()) {
            Claim current = queue.removeFirst(); result.add(current);
            for (int[] side : sides) {
                long x = (long) current.x + side[0], z = (long) current.z + side[1];
                if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) continue;
                Claim next = claimAt(claim.world, (int) x, (int) z);
                if (next != null && next.isOwner(claim.owner) && seen.add(next.key())) queue.add(next);
            }
        }
        result.sort(java.util.Comparator.comparingInt((Claim c) -> c.x).thenComparingInt(c -> c.z));
        return result;
    }

    /** Один свободный чанк между чужими территориями, включая диагональ. */
    public synchronized boolean foreignNearby(String world, int x, int z, String owner, java.util.Collection<Claim> moving) {
        Set<?> ignored = moving instanceof Set<?> set ? set : new java.util.HashSet<>(moving);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            long nx = (long) x + dx, nz = (long) z + dz;
            if (nx < Integer.MIN_VALUE || nx > Integer.MAX_VALUE || nz < Integer.MIN_VALUE || nz > Integer.MAX_VALUE) continue;
            Claim near = claimAt(world, (int) nx, (int) nz);
            if (near != null && !ignored.contains(near) && !near.isOwner(owner)) return true;
        }
        return false;
    }

    synchronized void mergeAcquired(List<Claim> incoming) {
        Set<Claim> incomingSet = new java.util.HashSet<>(incoming);
        for (List<Claim> group : ClaimTerritories.groups(all())) {
            if (group.stream().noneMatch(incomingSet::contains)) continue;
            List<Claim> sources = group.stream().filter(c -> !incomingSet.contains(c)).toList();
            ClaimTerritories.normalize(group, sources.isEmpty() ? incoming.stream().filter(group::contains).toList() : sources, true);
        }
        touch();
    }

    public synchronized void add(Claim claim) {
        Map<Long, Claim> world = claims.computeIfAbsent(claim.world, key -> new HashMap<>());
        Claim prior = world.get(claim.key());
        if (prior != null && prior != claim && prior.rentedAt(System.currentTimeMillis()))
            throw new IllegalStateException("Paid rental is protected");
        Claim previous = world.put(claim.key(), claim);
        if (previous != null) {
            logger.warning("[CHRDK_Claims] Приват " + claim.where() + " перезаписан новым владельцем "
                    + claim.owner + " (был " + previous.owner + ").");
        }
        List<Claim> region = territory(claim);
        List<Claim> sources = region.stream().filter(c -> c != claim).toList();
        if (!sources.isEmpty()) ClaimTerritories.normalize(region, sources, true);
        chunksClaimedTotal++;
        touch();
    }

    public synchronized void remove(Claim claim) {
        if (claim.rentedAt(System.currentTimeMillis())) throw new IllegalStateException("Paid rental is protected");
        if (claimAt(claim.world, claim.x, claim.z) == claim) territory(claim).forEach(c -> c.salePrice = 0);
        Map<Long, Claim> world = claims.get(claim.world);
        if (world != null) {
            if (!world.remove(claim.key(), claim)) {
                logger.warning("[CHRDK_Claims] Приват " + claim.where()
                        + " уже заменён другой записью — удаление пропущено.");
            }
            if (world.isEmpty()) {
                claims.remove(claim.world);
            }
        }
        touch();
    }

    /** Удаляет все приваты мира (например, после смены карты). @return сколько удалено. */
    public synchronized int removeWorld(String world) {
        if (inWorld(world).stream().anyMatch(c -> c.rentedAt(System.currentTimeMillis())))
            throw new IllegalStateException("Paid rental is protected");
        Map<Long, Claim> removed = claims.remove(world);
        if (removed == null || removed.isEmpty()) {
            return 0;
        }
        touch();
        return removed.size();
    }

    public synchronized List<Claim> ofOwner(String owner) {
        List<Claim> result = new ArrayList<>();
        for (Map<Long, Claim> world : claims.values()) {
            for (Claim claim : world.values()) {
                if (claim.isOwner(owner)) {
                    result.add(claim);
                }
            }
        }
        return result;
    }

    public synchronized List<Claim> inWorld(String world) {
        Map<Long, Claim> inWorld = claims.get(world);
        return inWorld == null ? List.of() : new ArrayList<>(inWorld.values());
    }

    public synchronized List<Claim> all() {
        List<Claim> result = new ArrayList<>();
        for (Map<Long, Claim> world : claims.values()) {
            result.addAll(world.values());
        }
        return result;
    }

    public synchronized int count(String owner) {
        int total = 0;
        for (Map<Long, Claim> world : claims.values()) {
            for (Claim claim : world.values()) {
                if (claim.isOwner(owner)) {
                    total++;
                }
            }
        }
        return total;
    }

    // ------------------------------------------------------------ treasury --

    /**
     * Баланс казны. Ник сравнивается без учёта регистра: администратор может
     * передать «steve», пока в данных записан «Steve».
     */
    public synchronized int treasuryOf(String name) {
        if (accounts != null) return (int) Math.min(Integer.MAX_VALUE, accounts.balance(name));
        if (name == null) {
            return 0;
        }
        Integer balance = treasury.get(keyIgnoreCase(treasury, name));
        return balance == null ? 0 : balance;
    }

    /** @return новый баланс (не больше {@link Integer#MAX_VALUE}). */
    public synchronized int addTreasury(String name, int amount) {
        if (accounts != null) return amount >= 0 && transferFunds(Map.of(name, (long) amount), null) ? treasuryOf(name) : -1;
        if (name == null || name.isBlank()) {
            return 0;
        }
        long sum = (long) treasuryOf(name) + Math.max(0, amount);
        int balance = (int) Math.min(Integer.MAX_VALUE, sum);
        String key = keyIgnoreCase(treasury, name);
        treasury.put(key == null ? name : key, balance);
        touch();
        return balance;
    }

    public synchronized boolean takeTreasury(String name, int amount) {
        if (accounts != null) return amount >= 0 && transferFunds(Map.of(name, -(long) amount), null);
        if (amount <= 0) {
            return true;
        }
        String key = keyIgnoreCase(treasury, name);
        if (key == null) {
            return false;
        }
        int balance = treasury.get(key);
        if (balance < amount) {
            return false;
        }
        treasury.put(key, balance - amount);
        touch();
        return true;
    }

    public synchronized long totalTreasury() {
        long total = 0;
        for (int balance : treasury.values()) {
            total += balance;
        }
        return total;
    }

    // ------------------------------------------------------------- notices --

    public synchronized void addNotice(String name, String text) {
        if (name == null || name.isBlank() || text == null || text.isBlank()) {
            return;
        }
        String existing = keyIgnoreCase(notices, name);
        List<String> lines = notices.computeIfAbsent(
                existing == null ? name : existing, key -> new ArrayList<>());
        lines.add(text);
        trimNotices(lines);
        touch();
    }

    private void trimNotices(List<String> lines) {
        while (lines.size() > MAX_NOTICES) {
            lines.remove(0);
        }
    }

    /** Забирает (и очищает) вести игрока. */
    public synchronized List<String> pollNotices(String name) {
        if (name == null) {
            return List.of();
        }
        List<String> lines = notices.remove(keyIgnoreCase(notices, name));
        if (lines == null) {
            return List.of();
        }
        touch();
        return List.copyOf(lines);
    }

    // -------------------------------------------------------------- limits --

    /** Персональный лимит администратора или {@code null}, если действует общий. */
    public synchronized Integer limitOverride(String name) {
        return name == null ? null : limits.get(keyIgnoreCase(limits, name));
    }

    /** Назначает персональный лимит (1..100000) — он главнее базы и бонуса ранга. */
    public synchronized void setLimitOverride(String name, int limit) {
        if (name == null || name.isBlank()) {
            return;
        }
        String key = keyIgnoreCase(limits, name);
        limits.put(key == null ? name : key, clamp(limit, 1, 100_000));
        touch();
    }

    /** Снимает персональный лимит. @return был ли он назначен */
    public synchronized boolean clearLimitOverride(String name) {
        String key = name == null ? null : keyIgnoreCase(limits, name);
        if (key == null) {
            return false;
        }
        limits.remove(key);
        touch();
        return true;
    }

    /** Все персональные лимиты (для админской статистики/диагностики). */
    public synchronized Map<String, Integer> limitOverrides() {
        return Map.copyOf(limits);
    }

    // ------------------------------------------------------------ transfer --

    /** Итог переноса владений: приваты, казна, отложенные вести, персональный лимит. */
    public record TransferSummary(int claims, int treasury, int notices, boolean limitMoved) {
    }

    /**
     * Переносит всё с одного ника на другой — сценарий смены ника игрока.
     *
     * <p>Переносятся приваты (владение), казна, отложенные вести и персональный
     * лимит; ники сравниваются без учёта регистра. Если получатель был участником
     * чужого привата, из списка участников он удаляется — он теперь владелец.</p>
     */
    public synchronized TransferSummary transferAll(String from, String to) {
        if (from == null || to == null || from.isBlank() || to.isBlank()
                || from.equalsIgnoreCase(to)) {
            return new TransferSummary(0, 0, 0, false);
        }
        List<Claim> moving = ofOwner(from);
        Set<Claim> movingSet = new java.util.HashSet<>(moving);
        if (moving.stream().anyMatch(c -> c.rentedAt(System.currentTimeMillis())
                || foreignNearby(c.world, c.x, c.z, to, movingSet))) throw new IllegalStateException("Protected rental or foreign buffer");
        int movedClaims = 0;
        for (Claim claim : all()) {
            if (!claim.isOwner(from)) {
                continue;
            }
            claim.owner = to;
            claim.removeMember(to);
            movedClaims++;
        }

        if (!moving.isEmpty()) mergeAcquired(moving);
        int movedTreasury = 0;
        String fromTreasury = keyIgnoreCase(treasury, from);
        if (accounts == null && fromTreasury != null) {
            movedTreasury = treasury.remove(fromTreasury);
        }
        if (movedTreasury > 0) {
            String toTreasury = keyIgnoreCase(treasury, to);
            String key = toTreasury == null ? to : toTreasury;
            int current = treasury.getOrDefault(key, 0);
            treasury.put(key, (int) Math.min(Integer.MAX_VALUE, (long) current + movedTreasury));
        }

        int movedNotices = 0;
        String fromNotices = keyIgnoreCase(notices, from);
        if (fromNotices != null) {
            List<String> pending = notices.remove(fromNotices);
            movedNotices = pending == null ? 0 : pending.size();
            if (movedNotices > 0) {
                String toNotices = keyIgnoreCase(notices, to);
                List<String> target = notices.computeIfAbsent(
                        toNotices == null ? to : toNotices, key -> new ArrayList<>());
                target.addAll(pending);
                trimNotices(target);
            }
        }

        boolean limitMoved = false;
        String fromLimit = keyIgnoreCase(limits, from);
        if (fromLimit != null) {
            Integer override = limits.remove(fromLimit);
            if (override != null) {
                limits.put(to, override);
                limitMoved = true;
            }
        }

        if (movedClaims > 0 || movedTreasury > 0 || movedNotices > 0 || limitMoved) {
            touch();
        }
        return new TransferSummary(movedClaims, movedTreasury, movedNotices, limitMoved);
    }

    private static <V> String keyIgnoreCase(Map<String, V> map, String name) {
        if (name == null) {
            return null;
        }
        for (String key : map.keySet()) {
            if (key.equalsIgnoreCase(name)) {
                return key;
            }
        }
        return null;
    }

    // --------------------------------------------------------------- stats --

    public synchronized void addDiamondsSpent(long amount) {
        if (amount <= 0) {
            return;
        }
        diamondsSpent += amount;
        touch();
    }

    public synchronized void addTaxBurned(long amount) {
        if (amount <= 0) {
            return;
        }
        taxBurned += amount;
        touch();
    }

    public synchronized long chunksClaimedTotal() {
        return chunksClaimedTotal;
    }

    public synchronized long diamondsSpent() {
        return diamondsSpent;
    }

    public synchronized long taxBurned() {
        return taxBurned;
    }

    // ----------------------------------------------------------- helpers ----

    /** Разбор роли из JSON/команды; неизвестное значение — {@code null}. */
    static Claim.Role parseRole(String value) {
        if (value == null) {
            return null;
        }
        String key = value.trim().toUpperCase(Locale.ROOT);
        return switch (key) {
            case "ACCESS", "ДОСТУП" -> Claim.Role.ACCESS;
            case "CONTAINER", "СУНДУКИ", "КОНТЕЙНЕРЫ" -> Claim.Role.CONTAINER;
            case "BUILD", "ПОЛНЫЙ", "ПОЛНЫЙ_ДОСТУП" -> Claim.Role.BUILD;
            default -> null;
        };
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }

    private static long longOf(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }
}
