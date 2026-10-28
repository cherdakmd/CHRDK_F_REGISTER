package ru.cherdakmd.chrdkregister;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import ru.cherdakmd.chrdkregister.api.AuctionPayment;
import ru.cherdakmd.chrdkregister.api.BankCharge;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Алмазный банк: счёт каждого игрока в {@code bank.json}.
 *
 * <p>Формат файла: {@code {"schema":1,"accounts":{"<uuid>":{"name":"Steve",
 * "balance":120,"deposited":300,"withdrawn":180,"sent":0,"received":0,
 * "updated":1690000000000}}}. Баланс — целое число алмазов; дробных сумм в
 * экономике сервера нет, поэтому и здесь нет {@code double}.</p>
 *
 * <p>Класс чистый (Gson + NIO, без Bukkit), мутации синхронизированы и идут
 * только с главного потока; запись — дебаунс: {@link #saveNow()} вызывает
 * периодическая асинхронная задача, {@link #flush()} — выключение плагина.
 * Потеря при аварийном завершении ограничена последними секундами, зато файл
 * никогда не пишется на каждую операцию.</p>
 *
 * <p>Ник — не ключ: игрок может сменить имя, счёт остаётся. Индекс ников нужен
 * только для {@code /pay <ник>} офлайн-игроку и {@code /bal <ник>}; он
 * пересобирается из счетов при загрузке (свежая запись побеждает).</p>
 */
public final class BankStore {

    /** Версия формата файла. */
    public static final int SCHEMA_VERSION = 4;
    /** Верхняя граница счёта: защита от переполнения и «бесконечных» начислений. */
    public static final long MAX_BALANCE = 1_000_000_000L;

    /** Счёт игрока. Поля не финальные: Gson заполняет их напрямую. */
    public static final class Account {
        public String name = "";
        public long balance;
        public long deposited;
        public long withdrawn;
        public long sent;
        public long received;
        public long updated;
        public boolean legacyShopImported;
        public long legacyShopAmount;

        public Account copy() {
            Account copy = new Account();
            copy.name = name;
            copy.balance = balance;
            copy.deposited = deposited;
            copy.withdrawn = withdrawn;
            copy.sent = sent;
            copy.received = received;
            copy.updated = updated;
            copy.legacyShopImported = legacyShopImported;
            copy.legacyShopAmount = legacyShopAmount;
            return copy;
        }
    }

    /** Строка топа: имя и баланс. */
    public record Entry(UUID id, String name, long balance) {
    }

    /** Итог перевода: статус и фактически списанные/зачисленные суммы. */
    public enum Status {
        OK,
        UNAVAILABLE,
        UNKNOWN_PLAYER,
        SELF_TRANSFER,
        INVALID_AMOUNT,
        NOT_ENOUGH,
        LIMIT_EXCEEDED,
        COOLDOWN
    }

    public record TransferResult(Status status, long amount, long tax, long credited,
                                 long fromBalance, long toBalance) {
        public boolean ok() {
            return status == Status.OK;
        }

        static TransferResult failed(Status status, long amount) {
            return new TransferResult(status, amount, 0L, 0L, 0L, 0L);
        }
    }

    private static final class Envelope {
        int schema = SCHEMA_VERSION;
        Map<String, Account> accounts = new LinkedHashMap<>();
        Map<String, ClaimsImport> claimsImports = new LinkedHashMap<>();
        Map<String, BankCharge> chargeSettlements = new LinkedHashMap<>();
        Map<String, UUID> chargeWitnesses = new LinkedHashMap<>();
        Map<String, UUID> entitlementSources = new LinkedHashMap<>();
        Map<String, Entitlement> entitlementCredits = new LinkedHashMap<>();
        Map<UUID, AuctionImport> auctionImports = new LinkedHashMap<>();
        Map<UUID, AuctionPayment> auctionSettlements = new LinkedHashMap<>();
    }

    private final Map<String, BankCharge> chargeSettlements = new LinkedHashMap<>();
    private final Map<String, UUID> chargeWitnesses = new LinkedHashMap<>();
    public record Entitlement(UUID owner, long credited) { }
    private final Map<String, UUID> entitlementSources = new LinkedHashMap<>();
    private final Map<String, Entitlement> entitlementCredits = new LinkedHashMap<>();
    public record AuctionImport(UUID market, long amount) { }
    private final Map<UUID, AuctionImport> auctionImports = new LinkedHashMap<>();
    private final Map<UUID, AuctionPayment> auctionSettlements = new LinkedHashMap<>();
    public record ClaimsImport(UUID player, long amount) { }
    private final Map<String, ClaimsImport> claimsImports = new LinkedHashMap<>();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    @FunctionalInterface interface WriteCheckpoint { void afterReplace() throws IOException; }
    private final WriteCheckpoint checkpoint;
    private final Path file;
    private final Logger logger;
    private final Map<UUID, Account> accounts = new LinkedHashMap<>();
    private final Map<String, UUID> byName = new LinkedHashMap<>();
    private boolean dirty;
    private boolean available = true;
    public synchronized boolean available() { return available; }

    public BankStore(Path dataFolder, Logger logger) {
        this(dataFolder, logger, () -> { });
    }
    BankStore(Path dataFolder, Logger logger, WriteCheckpoint checkpoint) {
        this.file = dataFolder.resolve("bank.json"); this.logger = logger; this.checkpoint = checkpoint;
    }

    public Path file() {
        return file;
    }

    /* --------------------------------- загрузка -------------------------------- */

    public synchronized void load() {
        if (!Files.exists(file)) return;
        if (!Files.isRegularFile(file)) { available = false; logger.severe("[bank] bank.json не является файлом; банк остановлен"); return; }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            var document = com.google.gson.JsonParser.parseReader(reader);
            ru.cherdakmd.chrdkregister.api.FinanceNumbers.requireIntegral(document);
            if (!document.isJsonObject() || !document.getAsJsonObject().has("accounts")
                    || !document.getAsJsonObject().get("accounts").isJsonObject()) throw new IllegalArgumentException("Нет таблицы банковских счетов");
            var object = document.getAsJsonObject();
            int schema = object.has("schema") ? object.get("schema").getAsInt() : 1;
            if (schema != 1 && schema != 2 && schema != 3 && schema != SCHEMA_VERSION) throw new IllegalArgumentException("Неизвестная схема банка");
            if (schema >= 2) for (String key : java.util.List.of("claimsImports", "auctionImports", "auctionSettlements"))
                if (!object.has(key) || !object.get(key).isJsonObject()) throw new IllegalArgumentException("Нет таблицы " + key);
            if (schema >= 3) for (String key : java.util.List.of("entitlementSources", "entitlementCredits"))
                if (!object.has(key) || !object.get(key).isJsonObject()) throw new IllegalArgumentException("Нет таблицы " + key);
            if (schema >= 4) for (String key : java.util.List.of("chargeSettlements", "chargeWitnesses"))
                if (!object.has(key) || !object.get(key).isJsonObject()) throw new IllegalArgumentException("Нет таблицы " + key);
            Envelope envelope = GSON.fromJson(document, Envelope.class);
            entitlementSources.clear(); entitlementCredits.clear();
            if (envelope.entitlementSources != null) for (var row : envelope.entitlementSources.entrySet()) {
                if (!validIncomeSource(row.getKey()) || row.getValue() == null) throw new IllegalArgumentException("Неверный источник доходов");
                entitlementSources.put(row.getKey(), row.getValue());
            }
            if (envelope.entitlementCredits != null) for (var row : envelope.entitlementCredits.entrySet()) {
                var key = row.getKey(); var receipt = row.getValue(); int separator = key == null ? -1 : key.indexOf(':');
                if (separator <= 0 || !entitlementSources.containsKey(key.substring(0, separator))
                        || !validIncomeKey(key.substring(separator + 1)) || receipt == null || receipt.owner() == null || receipt.credited() < 0)
                    throw new IllegalArgumentException("Неверная квитанция дохода");
                entitlementCredits.put(key, receipt);
            }
            chargeSettlements.clear();
            if (envelope.chargeSettlements != null) for (var row : envelope.chargeSettlements.entrySet()) {
                var payment = row.getValue();
                if (payment == null || !payment.valid() || !payment.source().equals(row.getKey())
                        || !payment.lineage().equals(entitlementSources.get(row.getKey()))) throw new IllegalArgumentException("Неверная квитанция расхода");
                chargeSettlements.put(row.getKey(), payment);
            }
            if (envelope == null || envelope.accounts == null) throw new IllegalArgumentException("Пустой денежный файл");
            claimsImports.clear();
            if (envelope.claimsImports != null) for (var row : envelope.claimsImports.entrySet()) {
                var receipt = row.getValue();
                if (row.getKey() == null || normalize(row.getKey()).isEmpty() || normalize(row.getKey()).length() > 16 || receipt == null || receipt.player() == null
                        || receipt.amount() < 0 || receipt.amount() > MAX_BALANCE) throw new IllegalArgumentException("Неверная квитанция казны");
                if (claimsImports.put(normalize(row.getKey()), receipt) != null) throw new IllegalArgumentException("Повтор имени казны");
            }
            auctionImports.clear(); auctionSettlements.clear();
            if (envelope.auctionImports != null) for (var row : envelope.auctionImports.entrySet()) {
                var receipt = row.getValue();
                if (row.getKey() == null || receipt == null || receipt.market() == null || receipt.amount() < 0 || receipt.amount() > MAX_BALANCE)
                    throw new IllegalArgumentException("Неверная квитанция аукциона");
                auctionImports.put(row.getKey(), receipt);
            }
            if (envelope.auctionSettlements != null) for (var row : envelope.auctionSettlements.entrySet()) {
                var payment = row.getValue();
                if (row.getKey() == null || payment == null || !payment.valid() || !row.getKey().equals(payment.market()))
                    throw new IllegalArgumentException("Неверный счётчик платежей аукциона");
                auctionSettlements.put(row.getKey(), payment);
            }
            List<Map.Entry<UUID, Account>> rows = new ArrayList<>();
            for (Map.Entry<String, Account> raw : envelope.accounts.entrySet()) {
                UUID id = parseUuid(raw.getKey());
                Account account = raw.getValue();
                if (id == null || account == null) throw new IllegalArgumentException("Неверный банковский UUID/счёт");
                account.name = sanitize(account.name);
                if (account.balance < 0 || account.balance > MAX_BALANCE) throw new IllegalArgumentException("Неверный банковский баланс");
                account.deposited = Math.max(0L, account.deposited);
                account.withdrawn = Math.max(0L, account.withdrawn);
                account.sent = Math.max(0L, account.sent);
                account.received = Math.max(0L, account.received);
                accounts.put(id, account);
                rows.add(Map.entry(id, account));
            }
            chargeWitnesses.clear();
            if (envelope.chargeWitnesses != null) for (var row : envelope.chargeWitnesses.entrySet()) {
                int colon = row.getKey() == null ? -1 : row.getKey().indexOf(':');
                if (colon < 1 || row.getValue() == null || !entitlementSources.containsKey(row.getKey().substring(0, colon))
                        || !accounts.containsKey(UUID.fromString(row.getKey().substring(colon + 1)))
                        || !UUID.fromString(row.getKey().substring(colon + 1)).toString().equals(row.getKey().substring(colon + 1))) throw new IllegalArgumentException("Неверная метка playerdata");
                chargeWitnesses.put(row.getKey(), row.getValue());
            }
            for (var payment : chargeSettlements.values()) {
                if (!accounts.containsKey(payment.payer())) throw new IllegalArgumentException("Расход без счёта");
                if (payment.cash() > 0 && !payment.transaction().equals(chargeWitnesses.get(payment.source() + ":" + payment.payer())))
                    throw new IllegalArgumentException("Предметная оплата без метки playerdata");
            }
            for (var receipt : entitlementCredits.values()) if (!accounts.containsKey(receipt.owner())) throw new IllegalArgumentException("Доход без счёта");
            for (var receipt : claimsImports.values()) if (!accounts.containsKey(receipt.player())) throw new IllegalArgumentException("Квитанция казны без счёта");
            for (var id : auctionImports.keySet()) if (!accounts.containsKey(id)) throw new IllegalArgumentException("Импорт аукциона без счёта");
            for (var payment : auctionSettlements.values()) if (!accounts.containsKey(payment.buyer()) || !accounts.containsKey(payment.seller())
                    || !auctionImported(payment.buyer(), payment.market()) || !auctionImported(payment.seller(), payment.market()))
                throw new IllegalArgumentException("Платёж аукциона без счёта/миграционной квитанции");
            // Индекс ников: свежая запись (updated) побеждает старую.
            rows.sort(Comparator.comparingLong((Map.Entry<UUID, Account> row) -> row.getValue().updated));
            for (Map.Entry<UUID, Account> row : rows) {
                if (!row.getValue().name.isBlank()) {
                    byName.put(normalize(row.getValue().name), row.getKey());
                }
            }
            logger.info("[bank] Загружено счетов: " + accounts.size()
                    + ", в обороте " + total() + " алмазов.");
        } catch (Exception exception) {
            available = false;
            // Битый файл не должен превращаться в пустой: сохраняем копию и блокируем изменения.
            logger.severe("[bank] Не удалось прочитать " + file + ": " + exception.getMessage()
                    + " — файл сохранён рядом как bank.json.corrupt");
            try {
                Files.copy(file, file.resolveSibling("bank.json.corrupt"),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // копия — диагностика, а не критичный путь
            }
        }
    }

    /* ---------------------------------- запросы -------------------------------- */

    /** Счёт игрока или {@code null}, если он ещё не открывал банк. */
    public synchronized Account account(UUID id) {
        return id == null ? null : accounts.get(id);
    }

    /** Счёт по нику (без учёта регистра) или {@code null}. */
    public synchronized Account accountByName(String name) {
        UUID id = idByName(name);
        return id == null ? null : accounts.get(id);
    }

    /** UUID по нику (без учёта регистра) или {@code null}. */
    public synchronized UUID idByName(String name) {
        String key = normalize(name);
        return key.isEmpty() ? null : byName.get(key);
    }

    public synchronized long balance(UUID id) {
        Account account = account(id);
        return account == null ? 0L : account.balance;
    }

    public synchronized long balanceOf(String name) {
        Account account = accountByName(name);
        return account == null ? 0L : account.balance;
    }

    public synchronized long sentOf(UUID id) {
        Account account = account(id);
        return account == null ? 0L : account.sent;
    }

    public synchronized long receivedOf(UUID id) {
        Account account = account(id);
        return account == null ? 0L : account.received;
    }

    public synchronized int accountCount() {
        return accounts.size();
    }

    /** Сумма всех счетов — для сводки администратора. */
    public synchronized long total() {
        long sum = 0L;
        for (Account account : accounts.values()) {
            sum += account.balance;
        }
        return sum;
    }

    /** Топ по балансу: только ненулевые счета, порядок — убывание. */
    public synchronized List<Entry> top(int limit) {
        int size = Math.clamp(limit, 1, 100);
        List<Map.Entry<UUID, Account>> rows = new ArrayList<>(accounts.entrySet());
        rows.sort(Comparator.comparingLong((Map.Entry<UUID, Account> row) -> row.getValue().balance)
                .reversed()
                .thenComparing(row -> row.getValue().name, String.CASE_INSENSITIVE_ORDER));
        List<Entry> out = new ArrayList<>(size);
        for (Map.Entry<UUID, Account> row : rows) {
            if (row.getValue().balance <= 0L || out.size() >= size) {
                break;
            }
            out.add(new Entry(row.getKey(), row.getValue().name.isBlank() ? row.getKey().toString() : row.getValue().name, row.getValue().balance));
        }
        return List.copyOf(out);
    }

    /* --------------------------------- мутации --------------------------------- */

    /**
     * Гарантирует, что счёт существует, и обновляет ник.
     *
     * @return счёт игрока (создаётся при первом обращении)
     */
    public synchronized Account open(UUID id, String name) {
        if (id == null) {
            return null;
        }
        Account account = accounts.computeIfAbsent(id, key -> new Account());
        String clean = sanitize(name);
        if (!clean.isEmpty() && !clean.equalsIgnoreCase(account.name)) {
            if (!account.name.isBlank()) {
                byName.remove(normalize(account.name), id);
            }
            account.name = clean;
            byName.put(normalize(clean), id);
            dirty = true;
        }
        account.updated = System.currentTimeMillis();
        return account;
    }

    /**
     * Зачисление на счёт.
     *
     * @return новый баланс или {@code -1}, если счёт переполнился
     */
    public synchronized long deposit(UUID id, String name, long amount) {
        if (!available) return -1L;
        if (id == null || amount <= 0L) {
            return balance(id);
        }
        Account account = open(id, name);
        if (account.balance > MAX_BALANCE - amount) {
            return -1L;
        }
        account.balance += amount;
        account.deposited += amount;
        account.updated = System.currentTimeMillis();
        dirty = true;
        return account.balance;
    }

    /**
     * Списание со счёта.
     *
     * @return {@code true}, если денег хватило
     */
    public synchronized boolean withdraw(UUID id, long amount) {
        if (!available) return false;
        if (id == null || amount <= 0L) {
            return false;
        }
        Account account = accounts.get(id);
        if (account == null || account.balance < amount) {
            return false;
        }
        account.balance -= amount;
        account.withdrawn += amount;
        account.updated = System.currentTimeMillis();
        dirty = true;
        return true;
    }

    /**
     * Прямая установка баланса администратором (с учётом верхней границы).
     *
     * @return фактический баланс после операции
     */
    public synchronized long setBalance(UUID id, String name, long balance) {
        if (!available) return balance(id);
        Account account = open(id, name);
        long clamped = Math.clamp(balance, 0L, MAX_BALANCE);
        if (clamped > account.balance) {
            account.deposited += clamped - account.balance;
        } else {
            account.withdrawn += account.balance - clamped;
        }
        account.balance = clamped;
        account.updated = System.currentTimeMillis();
        dirty = true;
        return clamped;
    }

    /**
     * Перевод между счетами. Комиссия вычитается из суммы перевода и не
     * зачисляется никому — это «сожжённые» алмазы, а не доход сервера.
     */
    public synchronized TransferResult transfer(UUID from, String fromName, UUID to, String toName,
                                                long amount, int taxPercent) {
        if (!available) return TransferResult.failed(Status.UNAVAILABLE, amount);
        if (from == null || to == null) {
            return TransferResult.failed(Status.UNKNOWN_PLAYER, amount);
        }
        if (from.equals(to)) {
            return TransferResult.failed(Status.SELF_TRANSFER, amount);
        }
        if (amount <= 0L) {
            return TransferResult.failed(Status.INVALID_AMOUNT, amount);
        }
        Account source = accounts.computeIfAbsent(from, key -> new Account());
        if (!source.name.isBlank()) {
            byName.put(normalize(source.name), from);
        } else {
            String clean = sanitize(fromName);
            if (!clean.isEmpty()) {
                source.name = clean;
            }
        }
        Account target = accounts.computeIfAbsent(to, key -> new Account());
        String targetName = sanitize(toName);
        if (target.name.isBlank() && !targetName.isEmpty()) {
            target.name = targetName;
            byName.put(normalize(targetName), to);
        }
        if (source.balance < amount) {
            return TransferResult.failed(Status.NOT_ENOUGH, amount);
        }
        int percent = Math.clamp(taxPercent, 0, 50);
        long tax = percent == 0 ? 0L : amount * percent / 100L;
        long credited = amount - tax;
        if (credited <= 0L) {
            return TransferResult.failed(Status.INVALID_AMOUNT, amount);
        }
        if (target.balance > MAX_BALANCE - credited) {
            return TransferResult.failed(Status.LIMIT_EXCEEDED, amount);
        }
        long now = System.currentTimeMillis();
        source.balance -= amount;
        source.sent += amount;
        source.updated = now;
        target.balance += credited;
        target.received += credited;
        target.updated = now;
        dirty = true;
        return new TransferResult(Status.OK, amount, tax, credited, source.balance, target.balance);
    }

    /** Казна раньше была по нику: глобальная квитанция не даёт повторить импорт под другим UUID. */
    public synchronized boolean importLegacyClaims(UUID id, String name, long amount) {
        String source = normalize(name);
        if (!available || id == null || source.isEmpty() || source.length() > 16 || amount < 0 || amount > MAX_BALANCE) return false;
        var receipt = claimsImports.get(source);
        if (receipt != null) return receipt.player().equals(id);
        var account = open(id, name);
        if (account.balance > MAX_BALANCE - amount) return false;
        account.balance += amount; account.deposited += amount; account.updated = System.currentTimeMillis();
        claimsImports.put(source, new ClaimsImport(id, amount)); dirty = true; return true;
    }

    /** Одна main-thread сделка нескольких личных счетов: сначала проверяем ВСЕ пределы. */
    public synchronized boolean exchangeAccounts(Map<UUID, Long> deltas, Runnable commit) {
        if (!available || deltas == null || deltas.isEmpty() || deltas.size() > 8 || commit == null) return false;
        for (var entry : deltas.entrySet()) {
            Long delta = entry.getValue();
            if (entry.getKey() == null || delta == null || delta < -MAX_BALANCE || delta > MAX_BALANCE) return false;
            long next = balance(entry.getKey()) + delta;
            if (next < 0 || next > MAX_BALANCE) return false;
        }
        commit.run(); // Только подготовленный storage-setter, без команд/событий.
        for (var entry : deltas.entrySet()) {
            var account = open(entry.getKey(), ""); long delta = entry.getValue();
            account.balance += delta;
            if (delta > 0) account.deposited += delta; else account.withdrawn -= delta;
            account.updated = System.currentTimeMillis();
        }
        dirty = true; return true;
    }

    /** Баланс и квитанция в одном bank.json: повторное чтение старого shop-data не начисляет деньги снова. */
    public synchronized boolean importLegacyShop(UUID id, long amount) {
        if (!available || id == null || amount < 0 || amount > MAX_BALANCE) return false;
        var account = open(id, "");
        if (account.legacyShopImported) return true;
        if (account.balance > MAX_BALANCE - amount) return false;
        account.balance += amount; account.deposited += amount;
        account.legacyShopAmount = amount; account.legacyShopImported = true;
        account.updated = System.currentTimeMillis(); dirty = true; return true;
    }

    /** Проверяем деньги до inventoryCommit; callback обязан откатить собственный снимок при исключении. */
    public synchronized boolean exchange(UUID id, String name, long debit, long credit, Runnable inventoryCommit) {
        if (!available || id == null || inventoryCommit == null || debit < 0 || credit < 0 || debit > MAX_BALANCE || credit > MAX_BALANCE) return false;
        var account = open(id, name);
        if (account.balance < debit || account.balance - debit > MAX_BALANCE - credit) return false;
        inventoryCommit.run(); // Не вызывает событий/команд; исключение не меняет деньги.
        account.balance = account.balance - debit + credit;
        account.withdrawn += debit; account.deposited += credit;
        account.updated = System.currentTimeMillis(); dirty = true; return true;
    }

    /** Последняя предметная оплата каждого источника для владельца; null — банк не может дать авторитетный ответ. */
    public synchronized Map<String, UUID> chargeWitnesses(UUID owner) {
        if (!available || owner == null) return null;
        var result = new LinkedHashMap<String, UUID>(); String suffix = ":" + owner;
        chargeWitnesses.forEach((key, value) -> { if (key.endsWith(suffix)) result.put(key.substring(0, key.length() - suffix.length()), value); });
        return Map.copyOf(result);
    }
    public synchronized long bankChargeSequence(String source, UUID lineage) {
        if (!incomeSourceAvailable(source,lineage)) return -1L;
        var last=chargeSettlements.get(source); return last==null ? 0L:last.sequence();
    }
    public synchronized BankCharge.Status bankChargeStatus(BankCharge payment) {
        if (!available) return BankCharge.Status.UNAVAILABLE;
        if (payment == null || !payment.valid()) return BankCharge.Status.CONFLICT;
        if (!incomeSourceAvailable(payment.source(), payment.lineage())) return BankCharge.Status.UNAVAILABLE;
        var last = chargeSettlements.get(payment.source());
        if (payment.equals(last)) return BankCharge.Status.COMMITTED;
        return payment.sequence() == (last == null ? 1 : last.sequence() + 1) ? BankCharge.Status.MISSING : BankCharge.Status.CONFLICT;
    }
    /** Расход и банковская сдача одним файлом с high-water квитанцией. */
    public synchronized boolean settleCharge(BankCharge payment) {
        var status = bankChargeStatus(payment);
        if (status == BankCharge.Status.COMMITTED) return true;
        if (status != BankCharge.Status.MISSING) return false;
        long balance = balance(payment.payer());
        if (balance < payment.debit() || balance - payment.debit() + payment.change() > MAX_BALANCE) return false;
        var next = envelope();
        adjust(next.accounts.computeIfAbsent(payment.payer().toString(), ignored -> new Account()), -payment.debit() + payment.change());
        next.chargeSettlements.put(payment.source(), payment);
        if (payment.cash() > 0) next.chargeWitnesses.put(payment.source() + ":" + payment.payer(), payment.transaction());
        return publishAuction(next);
    }

    private static boolean validIncomeSource(String source) { return source != null && source.matches("[a-z][a-z0-9-]{0,47}"); }
    private static boolean validIncomeKey(String key) { return key != null && key.matches("[a-z0-9_-]{1,80}"); }
    public synchronized boolean incomeSourceAvailable(String source, UUID lineage) {
        return available && validIncomeSource(source) && lineage != null && lineage.equals(entitlementSources.get(source));
    }
    public synchronized boolean registerIncomeSource(String source, UUID lineage) {
        if (!available || !validIncomeSource(source) || lineage == null) return false;
        var existing = entitlementSources.get(source);
        if (existing != null) return existing.equals(lineage);
        var next = envelope(); next.entitlementSources.put(source, lineage); return publishAuction(next);
    }
    /** Возвращает подтверждённую накопительную сумму; -1 означает неизвестность/конфликт, НЕ ноль. */
    public synchronized long creditedEntitlement(String source, UUID lineage, String key, UUID owner) {
        if (!available || !validIncomeSource(source) || !validIncomeKey(key) || lineage == null || owner == null) return -1;
        var registered = entitlementSources.get(source);
        if (!lineage.equals(registered)) return -1;
        var receipt = entitlementCredits.get(source + ":" + key);
        return receipt == null ? 0 : receipt.owner().equals(owner) ? receipt.credited() : -1;
    }
    /** Источник — долговечный монотонный total, не расходуемый кошелёк. Кредитуется только delta. */
    public synchronized boolean creditEntitlement(String source, UUID lineage, String key, UUID owner, long total) {
        long credited = creditedEntitlement(source, lineage, key, owner);
        if (credited < 0 || total < credited || total < 0) return false;
        long delta = total - credited;
        if (total == credited && entitlementCredits.containsKey(source + ":" + key)) return true;
        long accepted = Math.min(delta, MAX_BALANCE - balance(owner));
        if (delta > 0 && accepted == 0) return false;
        var next = envelope();
        var account = next.accounts.computeIfAbsent(owner.toString(), ignored -> new Account());
        adjust(account, accepted);
        next.entitlementSources.put(source, lineage);
        next.entitlementCredits.put(source + ":" + key, new Entitlement(owner, credited + accepted));
        return publishAuction(next) && accepted == delta;
    }

    /** Синхронный once-import. Источник заморожен в одном конкретном рынке. */
    public synchronized boolean importLegacyAuction(UUID market, UUID owner, long amount) {
        if (!available || market == null || owner == null || amount < 0 || amount > MAX_BALANCE) return false;
        var previous = auctionImports.get(owner);
        if (previous != null) return previous.equals(new AuctionImport(market, amount));
        if (balance(owner) > MAX_BALANCE - amount) return false;
        var next = envelope();
        var account = next.accounts.computeIfAbsent(owner.toString(), ignored -> new Account());
        account.balance += amount; account.deposited = counter(account.deposited, amount); account.updated = System.currentTimeMillis();
        next.auctionImports.put(owner, new AuctionImport(market, amount));
        return publishAuction(next);
    }

    /** Только следующая последовательность может быть новой. Старые попытки — конфликт, не повторное начисление. */
    public synchronized AuctionPayment.Status auctionPaymentStatus(AuctionPayment payment) {
        if (!available) return AuctionPayment.Status.UNAVAILABLE;
        if (payment == null || !payment.valid()) return AuctionPayment.Status.CONFLICT;
        if (!auctionImported(payment.buyer(), payment.market()) || !auctionImported(payment.seller(), payment.market())) return AuctionPayment.Status.CONFLICT;
        var last = auctionSettlements.get(payment.market());
        if (last != null && last.equals(payment)) return AuctionPayment.Status.COMMITTED;
        long next = last == null ? 1 : last.sequence() + 1;
        return payment.sequence() == next ? AuctionPayment.Status.MISSING : AuctionPayment.Status.CONFLICT;
    }

    private boolean auctionImported(UUID owner, UUID market) {
        var receipt = auctionImports.get(owner); return receipt != null && receipt.market().equals(market);
    }
    /** Банк+квитанция сохраняются ДО изменения storage/публикации товара. Исключение записи блокирует банк до restart. */
    public synchronized boolean settleAuction(AuctionPayment payment) {
        var status = auctionPaymentStatus(payment);
        if (status == AuctionPayment.Status.COMMITTED) return true;
        if (status != AuctionPayment.Status.MISSING) return false;
        if (!auctionImports.containsKey(payment.buyer()) || !auctionImports.containsKey(payment.seller())
                || !auctionImports.get(payment.buyer()).market().equals(payment.market())
                || !auctionImports.get(payment.seller()).market().equals(payment.market())) return false;
        long buyerAfter = balance(payment.buyer()) - payment.debit() + payment.change();
        if (balance(payment.buyer()) < payment.debit() || buyerAfter > MAX_BALANCE
                || balance(payment.seller()) > MAX_BALANCE - (payment.price() - payment.fee())) return false;
        var next = envelope();
        adjust(next.accounts.get(payment.buyer().toString()), -payment.debit() + payment.change());
        adjust(next.accounts.get(payment.seller().toString()), payment.price() - payment.fee());
        next.auctionSettlements.put(payment.market(), payment);
        return publishAuction(next);
    }

    private static long counter(long current, long add) { return current > Long.MAX_VALUE - add ? Long.MAX_VALUE : current + add; }
    private static void adjust(Account account, long delta) {
        account.balance += delta;
        if (delta >= 0) account.deposited = counter(account.deposited, delta); else account.withdrawn = counter(account.withdrawn, -delta);
        account.updated = System.currentTimeMillis();
    }
    private boolean publishAuction(Envelope next) {
        try { writeStrict(next); }
        catch (IOException error) {
            available = false;
            logger.severe("[bank] Неоднозначный сбой финансовой записи; банк остановлен до проверки/restart: " + error.getMessage());
            return false;
        }
        accounts.clear(); next.accounts.forEach((id, account) -> accounts.put(UUID.fromString(id), account));
        chargeSettlements.clear(); chargeSettlements.putAll(next.chargeSettlements);
        chargeWitnesses.clear(); chargeWitnesses.putAll(next.chargeWitnesses);
        entitlementSources.clear(); entitlementSources.putAll(next.entitlementSources);
        entitlementCredits.clear(); entitlementCredits.putAll(next.entitlementCredits);
        auctionImports.clear(); auctionImports.putAll(next.auctionImports);
        auctionSettlements.clear(); auctionSettlements.putAll(next.auctionSettlements);
        dirty = false; return true;
    }

    /* -------------------------------- сохранение ------------------------------- */

    public synchronized boolean isDirty() {
        return dirty;
    }

    public synchronized boolean hasAccounts() {
        return !accounts.isEmpty();
    }

    private Envelope envelope() {
        var envelope = new Envelope();
        accounts.forEach((id, account) -> envelope.accounts.put(id.toString(), account.copy()));
        envelope.claimsImports.putAll(claimsImports);
        envelope.chargeSettlements.putAll(chargeSettlements);
        envelope.chargeWitnesses.putAll(chargeWitnesses);
        envelope.entitlementSources.putAll(entitlementSources); envelope.entitlementCredits.putAll(entitlementCredits);
        envelope.auctionImports.putAll(auctionImports); envelope.auctionSettlements.putAll(auctionSettlements);
        return envelope;
    }
    private void writeStrict(Envelope envelope) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling("bank.json.tmp");
        Files.writeString(temp, GSON.toJson(envelope), StandardCharsets.UTF_8);
        try (var channel = java.nio.channels.FileChannel.open(temp, java.nio.file.StandardOpenOption.WRITE)) { channel.force(true); }
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        checkpoint.afterReplace();
        try (var directory = java.nio.channels.FileChannel.open(file.getParent(), java.nio.file.StandardOpenOption.READ)) { directory.force(true); }
    }
    /** Синхронизировано с durable-платежами: устаревший async-снимок не перезапишет квитанцию. */
    public synchronized void saveNow() {
        if (!available || !dirty) return;
        try { writeStrict(envelope()); dirty = false; }
        catch (IOException error) { available = false; logger.severe("[bank] Запись остановлена: " + error.getMessage()); }
    }
    public synchronized String snapshotJson() { return GSON.toJson(envelope()); }

    /** Запись без сетевой задержки: используется при выключении плагина. */
    public synchronized void flush() {
        saveNow();
    }

    /* --------------------------------- утилиты --------------------------------- */

    static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private static String sanitize(String name) {
        if (name == null) {
            return "";
        }
        String trimmed = name.trim();
        return trimmed.length() > 16 ? trimmed.substring(0, 16) : trimmed;
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
