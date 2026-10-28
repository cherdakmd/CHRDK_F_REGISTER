package ru.cherdakmd.chrdkregister;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

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
    public static final int SCHEMA_VERSION = 1;
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
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path file;
    private final Logger logger;
    private final Map<UUID, Account> accounts = new LinkedHashMap<>();
    private final Map<String, UUID> byName = new LinkedHashMap<>();
    private boolean dirty;

    public BankStore(Path dataFolder, Logger logger) {
        this.file = dataFolder.resolve("bank.json");
        this.logger = logger;
    }

    public Path file() {
        return file;
    }

    /* --------------------------------- загрузка -------------------------------- */

    public synchronized void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Envelope envelope = GSON.fromJson(reader, Envelope.class);
            if (envelope == null || envelope.accounts == null) {
                return;
            }
            List<Map.Entry<UUID, Account>> rows = new ArrayList<>();
            for (Map.Entry<String, Account> raw : envelope.accounts.entrySet()) {
                UUID id = parseUuid(raw.getKey());
                Account account = raw.getValue();
                if (id == null || account == null) {
                    continue;
                }
                account.name = sanitize(account.name);
                account.balance = Math.clamp(account.balance, 0L, MAX_BALANCE);
                account.deposited = Math.max(0L, account.deposited);
                account.withdrawn = Math.max(0L, account.withdrawn);
                account.sent = Math.max(0L, account.sent);
                account.received = Math.max(0L, account.received);
                accounts.put(id, account);
                rows.add(Map.entry(id, account));
            }
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
            // Битый файл не должен превращаться в пустой: сохраняем копию и начинаем заново.
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
            out.add(new Entry(row.getKey(), row.getValue().name, row.getValue().balance));
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

    /** Баланс и квитанция в одном bank.json: повторное чтение старого shop-data не начисляет деньги снова. */
    public synchronized boolean importLegacyShop(UUID id, long amount) {
        if (id == null || amount < 0 || amount > MAX_BALANCE) return false;
        var account = open(id, "");
        if (account.legacyShopImported) return true;
        if (account.balance > MAX_BALANCE - amount) return false;
        account.balance += amount; account.deposited += amount;
        account.legacyShopAmount = amount; account.legacyShopImported = true;
        account.updated = System.currentTimeMillis(); dirty = true; return true;
    }

    /** Проверяем деньги до inventoryCommit; callback обязан откатить собственный снимок при исключении. */
    public synchronized boolean exchange(UUID id, String name, long debit, long credit, Runnable inventoryCommit) {
        if (id == null || inventoryCommit == null || debit < 0 || credit < 0 || debit > MAX_BALANCE || credit > MAX_BALANCE) return false;
        var account = open(id, name);
        if (account.balance < debit || account.balance - debit > MAX_BALANCE - credit) return false;
        inventoryCommit.run(); // Не вызывает событий/команд; исключение не меняет деньги.
        account.balance = account.balance - debit + credit;
        account.withdrawn += debit; account.deposited += credit;
        account.updated = System.currentTimeMillis(); dirty = true; return true;
    }

    /* -------------------------------- сохранение ------------------------------- */

    public synchronized boolean isDirty() {
        return dirty;
    }

    public synchronized boolean hasAccounts() {
        return !accounts.isEmpty();
    }

    /** Немедленная запись (может вызываться с асинхронного потока). */
    public synchronized void saveNow() {
        if (!dirty) {
            return;
        }
        Envelope envelope = new Envelope();
        for (Map.Entry<UUID, Account> row : accounts.entrySet()) {
            envelope.accounts.put(row.getKey().toString(), row.getValue());
        }
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling("bank.json.tmp");
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(envelope, writer);
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (IOException exception) {
            logger.severe("[bank] Не удалось сохранить " + file + ": " + exception.getMessage());
        }
    }

    /** Сериализованный снимок — для тестов и диагностики. */
    public synchronized String snapshotJson() {
        Envelope envelope = new Envelope();
        for (Map.Entry<UUID, Account> row : accounts.entrySet()) {
            envelope.accounts.put(row.getKey().toString(), row.getValue());
        }
        return GSON.toJson(envelope);
    }

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
