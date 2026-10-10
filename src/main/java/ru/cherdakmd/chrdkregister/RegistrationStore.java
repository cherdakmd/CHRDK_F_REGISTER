package ru.cherdakmd.chrdkregister;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.slf4j.Logger;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Persistent whitelist grants, pending login tickets and the DonatePay polling cursor. */
public final class RegistrationStore {
    private static final int MAX_TICKETS = 20_000;
    private static final int MAX_PROCESSED_DONATIONS = 5_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Logger logger;
    private StoreData data;

    public RegistrationStore(Path configDirectory, Logger logger) {
        this.file = configDirectory.resolve("chrdk_f_register-data.json");
        this.logger = logger;
        load();
    }

    public synchronized Ticket issueTicket(UUID uuid, String name, long lifetimeMillis) {
        long now = System.currentTimeMillis();
        removeExpiredTickets(now);
        String canonical = canonicalName(name);
        for (Ticket ticket : data.tickets.values()) {
            if (ticket.expiresAt > now && uuid.toString().equals(ticket.uuid)) {
                ticket.minecraftName = name;
                ticket.canonicalName = canonical;
                ticket.expiresAt = Math.max(ticket.expiresAt, now + lifetimeMillis);
                save();
                return copy(ticket);
            }
        }

        if (data.tickets.size() >= MAX_TICKETS) {
            data.tickets.values().stream()
                    .min(Comparator.comparingLong(ticket -> ticket.createdAt))
                    .map(ticket -> ticket.token)
                    .ifPresent(data.tickets::remove);
        }

        Ticket ticket = new Ticket();
        ticket.token = randomToken();
        ticket.uuid = uuid.toString();
        ticket.minecraftName = name;
        ticket.canonicalName = canonical;
        ticket.createdAt = now;
        ticket.expiresAt = now + lifetimeMillis;
        data.tickets.put(ticket.token, ticket);
        save();
        return copy(ticket);
    }

    public synchronized Optional<Ticket> getTicket(String token) {
        long now = System.currentTimeMillis();
        Ticket ticket = data.tickets.get(token);
        if (ticket == null) return Optional.empty();
        if (ticket.expiresAt <= now) {
            data.tickets.remove(token);
            save();
            return Optional.empty();
        }
        return Optional.of(copy(ticket));
    }

    public synchronized Optional<Ticket> findTicketForDonation(String donorName, long donationCreatedAt) {
        if (donorName == null || donorName.isBlank()) return Optional.empty();
        long now = System.currentTimeMillis();
        String canonical = canonicalName(donorName);
        return data.tickets.values().stream()
                .filter(ticket -> ticket.expiresAt > now)
                .filter(ticket -> ticket.createdAt <= donationCreatedAt)
                .filter(ticket -> canonical.equals(ticket.canonicalName)
                        || canonical.equals(canonicalName(ticket.minecraftName)))
                .max(Comparator.comparingLong(ticket -> ticket.createdAt))
                .map(RegistrationStore::copy);
    }

    public synchronized Optional<Member> getMember(UUID uuid) {
        Member member = data.members.get(uuid.toString());
        return member == null ? Optional.empty() : Optional.of(copy(member));
    }

    public synchronized Optional<Member> findMemberByName(String name) {
        String canonical = canonicalName(name);
        return data.members.values().stream()
                .filter(member -> canonical.equals(canonicalName(member.minecraftName)))
                .findFirst()
                .map(RegistrationStore::copy);
    }

    public synchronized List<Member> getMembersSnapshot() {
        List<Member> result = new ArrayList<>();
        for (Member member : data.members.values()) result.add(copy(member));
        return result;
    }

    public synchronized int getPendingCount() {
        removeExpiredTickets(System.currentTimeMillis());
        return data.tickets.size();
    }

    public synchronized int getMemberCount() {
        return data.members.size();
    }

    public synchronized boolean applyTwitchGrant(
            Ticket ticket,
            TwitchApiClient.TwitchUser user,
            TwitchApiClient.TokenBundle tokens,
            long twitchUntil,
            boolean managedWhitelist
    ) {
        Ticket currentTicket = liveTicket(ticket.token);
        if (currentTicket == null || !currentTicket.uuid.equals(ticket.uuid)) return false;

        Member member = data.members.computeIfAbsent(ticket.uuid, ignored -> new Member());
        initializeMember(member, currentTicket, managedWhitelist);
        member.twitchUserId = user.id();
        member.twitchLogin = user.login();
        member.twitchAccessToken = tokens.accessToken();
        member.twitchRefreshToken = tokens.refreshToken();
        member.twitchAccessExpiresAt = tokens.expiresAt();
        member.twitchUntil = twitchUntil;
        data.tickets.remove(ticket.token);
        save();
        return true;
    }

    public synchronized boolean applyDonationGrant(
            Ticket ticket,
            long donationGrantUntil,
            boolean managedWhitelist
    ) {
        Ticket currentTicket = liveTicket(ticket.token);
        if (currentTicket == null || !currentTicket.uuid.equals(ticket.uuid)) return false;

        Member member = data.members.computeIfAbsent(ticket.uuid, ignored -> new Member());
        initializeMember(member, currentTicket, managedWhitelist);
        member.donateUntil = Math.max(member.donateUntil, donationGrantUntil);
        data.tickets.remove(ticket.token);
        save();
        return true;
    }

    public synchronized void updateTwitchCheck(
            UUID uuid,
            TwitchApiClient.TokenBundle tokens,
            long twitchUntil
    ) {
        Member member = data.members.get(uuid.toString());
        if (member == null) return;
        member.twitchAccessToken = tokens.accessToken();
        member.twitchRefreshToken = tokens.refreshToken();
        member.twitchAccessExpiresAt = tokens.expiresAt();
        member.twitchUntil = twitchUntil;
        save();
    }

    public synchronized void clearTwitchGrant(UUID uuid) {
        Member member = data.members.get(uuid.toString());
        if (member == null) return;
        member.twitchUserId = "";
        member.twitchLogin = "";
        member.twitchAccessToken = "";
        member.twitchRefreshToken = "";
        member.twitchAccessExpiresAt = 0;
        member.twitchUntil = 0;
        save();
    }

    public synchronized void extendDonationGrant(UUID uuid, String minecraftName, long donationUntil) {
        Member member = data.members.get(uuid.toString());
        if (member == null) return;
        member.minecraftName = minecraftName;
        member.donateUntil = Math.max(member.donateUntil, donationUntil);
        save();
    }

    public synchronized void removeMember(UUID uuid) {
        if (data.members.remove(uuid.toString()) != null) save();
    }

    public synchronized boolean isDonationCursorInitialized() {
        return data.donationCursorInitialized;
    }

    public synchronized long getDonationCursor() {
        return data.donationCursor;
    }

    public synchronized void initializeDonationCursor(long cursor) {
        if (data.donationCursorInitialized) return;
        data.donationCursor = Math.max(0, cursor);
        data.donationCursorInitialized = true;
        save();
    }

    public synchronized boolean isDonationProcessed(long id) {
        return data.processedDonationIds.contains(Long.toString(id));
    }

    public synchronized void markDonationProcessed(long id) {
        String key = Long.toString(id);
        data.processedDonationIds.remove(key);
        data.processedDonationIds.add(key);
        data.donationCursor = Math.max(data.donationCursor, id);
        while (data.processedDonationIds.size() > MAX_PROCESSED_DONATIONS) {
            String first = data.processedDonationIds.iterator().next();
            data.processedDonationIds.remove(first);
        }
        save();
    }

    private Ticket liveTicket(String token) {
        Ticket ticket = data.tickets.get(token);
        if (ticket == null) return null;
        if (ticket.expiresAt <= System.currentTimeMillis()) {
            data.tickets.remove(token);
            save();
            return null;
        }
        return ticket;
    }

    private void initializeMember(Member member, Ticket ticket, boolean managedWhitelist) {
        if (member.uuid == null || member.uuid.isBlank()) {
            member.uuid = ticket.uuid;
            member.minecraftName = ticket.minecraftName;
            member.managedWhitelist = managedWhitelist;
        } else {
            member.minecraftName = ticket.minecraftName;
        }
    }

    private void removeExpiredTickets(long now) {
        boolean changed = data.tickets.values().removeIf(ticket -> ticket.expiresAt <= now);
        if (changed) save();
    }

    private void load() {
        try {
            Files.createDirectories(file.getParent());
            if (Files.notExists(file)) {
                data = new StoreData();
                save();
                return;
            }
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                data = GSON.fromJson(reader, StoreData.class);
                if (data == null) data = new StoreData();
            }
            if (data.members == null) data.members = new LinkedHashMap<>();
            if (data.tickets == null) data.tickets = new LinkedHashMap<>();
            if (data.processedDonationIds == null) data.processedDonationIds = new LinkedHashSet<>();
        } catch (Exception exception) {
            logger.error("Could not load registration data from {}", file.toAbsolutePath(), exception);
            try {
                if (Files.exists(file)) {
                    Path backup = file.resolveSibling(file.getFileName() + ".corrupt-" + Instant.now().toEpochMilli());
                    Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
                    logger.error("The unreadable data file was copied to {}", backup.toAbsolutePath());
                }
            } catch (Exception backupException) {
                logger.error("Could not back up the unreadable registration data", backupException);
            }
            data = new StoreData();
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(data, writer);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception unsupportedAtomicMove) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Windows and some filesystems do not support POSIX permissions.
            }
        } catch (Exception exception) {
            logger.error("Could not save registration data to {}", file.toAbsolutePath(), exception);
        }
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String canonicalName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private static Ticket copy(Ticket source) {
        Ticket result = new Ticket();
        result.token = source.token;
        result.uuid = source.uuid;
        result.minecraftName = source.minecraftName;
        result.canonicalName = source.canonicalName;
        result.createdAt = source.createdAt;
        result.expiresAt = source.expiresAt;
        return result;
    }

    private static Member copy(Member source) {
        Member result = new Member();
        result.uuid = source.uuid;
        result.minecraftName = source.minecraftName;
        result.managedWhitelist = source.managedWhitelist;
        result.donateUntil = source.donateUntil;
        result.twitchUserId = source.twitchUserId;
        result.twitchLogin = source.twitchLogin;
        result.twitchAccessToken = source.twitchAccessToken;
        result.twitchRefreshToken = source.twitchRefreshToken;
        result.twitchAccessExpiresAt = source.twitchAccessExpiresAt;
        result.twitchUntil = source.twitchUntil;
        return result;
    }

    public static final class Ticket {
        public String token = "";
        public String uuid = "";
        public String minecraftName = "";
        public String canonicalName = "";
        public long createdAt;
        public long expiresAt;
    }

    public static final class Member {
        public String uuid = "";
        public String minecraftName = "";
        public boolean managedWhitelist = true;
        public long donateUntil;
        public String twitchUserId = "";
        public String twitchLogin = "";
        public String twitchAccessToken = "";
        public String twitchRefreshToken = "";
        public long twitchAccessExpiresAt;
        public long twitchUntil;

        public boolean hasTwitchGrant(long now) {
            return twitchUntil > now && twitchUserId != null && !twitchUserId.isBlank();
        }

        public boolean hasDonationGrant(long now) {
            return donateUntil > now;
        }

        public boolean hasActiveGrant(long now) {
            return hasTwitchGrant(now) || hasDonationGrant(now);
        }

        public UUID parsedUuid() {
            return UUID.fromString(uuid);
        }
    }

    private static final class StoreData {
        Map<String, Member> members = new LinkedHashMap<>();
        Map<String, Ticket> tickets = new LinkedHashMap<>();
        Set<String> processedDonationIds = new LinkedHashSet<>();
        long donationCursor;
        boolean donationCursorInitialized;
    }
}
