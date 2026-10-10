package ru.cherdakmd.chrdkregister;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.ChatFormatting;
import org.slf4j.Logger;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** Applies temporary grants to the vanilla whitelist; all Minecraft state changes run on the server thread. */
public final class AccessManager {
    private static final Pattern MINECRAFT_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private final ModConfig config;
    private final RegistrationStore store;
    private final TwitchApiClient twitch;
    private final DonatePayClient donatePay;
    private final Logger logger;
    private final AtomicBoolean donationPollRunning = new AtomicBoolean();
    private final AtomicBoolean twitchPollRunning = new AtomicBoolean();

    private volatile MinecraftServer server;
    private volatile ScheduledExecutorService scheduler;
    private volatile RegistrationHttpServer webServer;
    private volatile long lastDonatePayWarning;
    private volatile long lastTwitchWarning;
    private volatile long firstDonationPollSince;

    public AccessManager(ModConfig config, RegistrationStore store, TwitchApiClient twitch,
                        DonatePayClient donatePay, Logger logger) {
        this.config = config;
        this.store = store;
        this.twitch = twitch;
        this.donatePay = donatePay;
        this.logger = logger;
    }

    public synchronized void onServerStarted(MinecraftServer minecraftServer) {
        if (scheduler != null) scheduler.shutdownNow();
        server = minecraftServer;
        firstDonationPollSince = System.currentTimeMillis();

        if (config.webReady()) {
            webServer = new RegistrationHttpServer(config, store, twitch, this, logger);
            webServer.start();
        } else {
            logger.warn("Registration web pages are disabled until publicBaseUrl is set to a public HTTPS URL in config/chrdk_f_register.json");
        }

        scheduler = Executors.newScheduledThreadPool(3, runnable -> {
            Thread thread = new Thread(runnable, "chrdk-f-register-worker");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::pollDonationsSafely, 5, config.donatePollSeconds, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(this::pollFollowsSafely, 10, config.twitchCheckSeconds, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(this::reconcileSafely, 15, 60, TimeUnit.SECONDS);
        runOnServerAndWait(() -> {
            reconcileAllOnServer();
            return true;
        }, false);
        logger.info("CHRDK Registration started; keep the vanilla server whitelist enabled. Web endpoint: {}:{}",
                config.bindAddress, config.webPort);
    }

    public synchronized void onServerStopping(MinecraftServer minecraftServer) {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (webServer != null) {
            webServer.stop();
            webServer = null;
        }
        if (server == minecraftServer) server = null;
    }

    public Component loginMessage(UUID playerId, String playerName, Component vanillaReason) {
        if (!config.webReady() || playerId == null || playerName == null
                || !MINECRAFT_NAME.matcher(playerName).matches()) {
            return vanillaReason;
        }

        try {
            RegistrationStore.Ticket ticket = store.issueTicket(
                    playerId, playerName, Duration.ofHours(config.ticketLifetimeHours).toMillis());
            String base = config.normalizedPublicBaseUrl();
            String encodedTicket = URLEncoder.encode(ticket.token, StandardCharsets.UTF_8);
            String twitchUrl = base + "/register/twitch?ticket=" + encodedTicket;
            String donateUrl = base + "/register/donate?ticket=" + encodedTicket;

            return Component.literal("Этот сервер закрыт по whitelist. Выбери способ получить проходку:\n")
                    .append(clickable("[ Twitch / фоллов ]", twitchUrl))
                    .append(Component.literal("\n"))
                    .append(clickable("[ DonatePay / донат ]", donateUrl))
                    .append(Component.literal("\nПосле подтверждения переподключись к серверу."));
        } catch (Exception exception) {
            logger.warn("Could not create a registration ticket for {}", playerName, exception);
            return vanillaReason;
        }
    }

    public boolean grantTwitch(String ticketToken, TwitchApiClient.TwitchUser user,
                               TwitchApiClient.TokenBundle tokens) {
        RegistrationStore.Ticket ticket = store.getTicket(ticketToken).orElse(null);
        if (ticket == null) return false;
        return runOnServerAndWait(() -> grantTwitchOnServer(ticket, user, tokens), false);
    }

    private boolean grantTwitchOnServer(RegistrationStore.Ticket ticket, TwitchApiClient.TwitchUser user,
                                        TwitchApiClient.TokenBundle tokens) {
        MinecraftServer current = server;
        if (current == null) return false;
        UUID uuid = UUID.fromString(ticket.uuid);
        Optional<RegistrationStore.Member> previous = store.getMember(uuid);
        boolean managed = previous.map(member -> member.managedWhitelist).orElse(true);
        long grantUntil = System.currentTimeMillis() + Duration.ofDays(config.grantDays).toMillis();
        if (!store.applyTwitchGrant(ticket, user, tokens, grantUntil, managed)) return false;
        ensureWhitelist(current, ticket.minecraftName, managed);
        logger.info("Twitch follow verified for Minecraft player {} (Twitch login {}).", ticket.minecraftName, user.login());
        return true;
    }

    private boolean grantDonationOnServer(RegistrationStore.Ticket ticket, DonatePayClient.Transaction transaction) {
        MinecraftServer current = server;
        if (current == null) return false;
        UUID uuid = UUID.fromString(ticket.uuid);
        Optional<RegistrationStore.Member> previous = store.getMember(uuid);
        boolean managed = previous.map(member -> member.managedWhitelist).orElse(true);
        long period = Duration.ofDays(config.grantDays).toMillis();
        long start = Math.max(transaction.createdAt(), previous.map(member -> member.donateUntil).orElse(0L));
        long grantUntil = start + period;
        if (grantUntil <= System.currentTimeMillis()) return false;
        if (!store.applyDonationGrant(ticket, grantUntil, managed)) return false;
        ensureWhitelist(current, ticket.minecraftName, managed);
        logger.info("DonatePay donation verified for Minecraft player {} ({} RUB).",
                ticket.minecraftName, transaction.amount().stripTrailingZeros().toPlainString());
        return true;
    }

    private boolean extendExistingDonationOnServer(RegistrationStore.Member member,
                                                    DonatePayClient.Transaction transaction) {
        MinecraftServer current = server;
        if (current == null) return false;
        UUID uuid = UUID.fromString(member.uuid);
        Optional<RegistrationStore.Member> latest = store.getMember(uuid);
        if (latest.isEmpty()) return false;
        long period = Duration.ofDays(config.grantDays).toMillis();
        long start = Math.max(transaction.createdAt(), latest.get().donateUntil);
        long grantUntil = start + period;
        if (grantUntil <= System.currentTimeMillis()) return false;
        store.extendDonationGrant(uuid, member.minecraftName, grantUntil);
        logger.info("DonatePay donation extended access for Minecraft player {} ({} RUB).",
                member.minecraftName, transaction.amount().stripTrailingZeros().toPlainString());
        return true;
    }

    public boolean revokeByName(String name) {
        RegistrationStore.Member member = store.findMemberByName(name).orElse(null);
        if (member == null) return false;
        return runOnServerAndWait(() -> {
            MinecraftServer current = server;
            if (current == null) return false;
            revokeOnServer(current, member);
            return true;
        }, false);
    }

    public String statusLine() {
        return "активных регистраций: " + store.getMemberCount()
                + ", ожидающих заявок: " + store.getPendingCount()
                + ", веб: " + (config.webReady() && webServer != null && webServer.isRunning() ? "запущен" : "не настроен");
    }

    private void pollDonationsSafely() {
        if (!config.donatePayReady() || !donationPollRunning.compareAndSet(false, true)) return;
        try {
            if (!store.isDonationCursorInitialized()) {
                List<DonatePayClient.Transaction> recent = donatePay.fetchRecentSuccessful(100);
                long latest = 0;
                for (DonatePayClient.Transaction transaction : recent) {
                    latest = Math.max(latest, transaction.id());
                    if (transaction.createdAt() < firstDonationPollSince || store.isDonationProcessed(transaction.id())) continue;
                    if (!processDonation(transaction)) return;
                    store.markDonationProcessed(transaction.id());
                }
                store.initializeDonationCursor(latest);
                logger.info("DonatePay transaction cursor initialized at id {} (payments from before server startup are ignored).", latest);
                return;
            }

            long cursor = store.getDonationCursor();
            List<DonatePayClient.Transaction> transactions = donatePay.fetchAfter(cursor, 100);
            for (DonatePayClient.Transaction transaction : transactions) {
                if (transaction.id() <= cursor || store.isDonationProcessed(transaction.id())) continue;
                if (!processDonation(transaction)) break;
                store.markDonationProcessed(transaction.id());
                cursor = Math.max(cursor, transaction.id());
            }
        } catch (Exception exception) {
            warnThrottled("donatepay", exception.getMessage());
        } finally {
            donationPollRunning.set(false);
        }
    }

    private boolean processDonation(DonatePayClient.Transaction transaction) {
        if (!transaction.isSuccessfulDonation()
                || transaction.amount().compareTo(BigDecimal.valueOf(config.minimumDonationRubles)) < 0
                || transaction.donorName() == null || transaction.donorName().isBlank()) return true;
        if (transaction.createdAt() + Duration.ofDays(config.grantDays).toMillis() <= System.currentTimeMillis()) return true;

        RegistrationStore.Ticket ticket = store.findTicketForDonation(transaction.donorName(), transaction.createdAt()).orElse(null);
        if (ticket != null) {
            boolean granted = runOnServerAndWait(() -> grantDonationOnServer(ticket, transaction), false);
            return granted || store.getTicket(ticket.token).isEmpty();
        }

        RegistrationStore.Member member = store.findMemberByExactName(transaction.donorName()).orElse(null);
        if (member != null) {
            return runOnServerAndWait(() -> extendExistingDonationOnServer(member, transaction), false);
        }
        return true;
    }

    private void pollFollowsSafely() {
        if (!config.twitchReady() || !twitchPollRunning.compareAndSet(false, true)) return;
        try {
            List<RegistrationStore.Member> members = store.getMembersSnapshot();
            for (RegistrationStore.Member member : members) {
                if (member.twitchUserId == null || member.twitchUserId.isBlank()
                        || member.twitchRefreshToken == null || member.twitchRefreshToken.isBlank()) continue;
                try {
                    TwitchApiClient.FollowCheck result = twitch.refreshAndCheck(member);
                    runOnServerAndWait(() -> {
                        MinecraftServer current = server;
                        if (current == null) return false;
                        UUID uuid = UUID.fromString(member.uuid);
                        if (result.following()) {
                            long until = System.currentTimeMillis() + Duration.ofDays(config.grantDays).toMillis();
                            store.updateTwitchCheck(uuid, result.tokens(), until);
                            store.getMember(uuid).ifPresent(latest -> reconcileMemberOnServer(current, latest));
                        } else {
                            store.clearTwitchGrant(uuid);
                            store.getMember(uuid).ifPresent(latest -> reconcileMemberOnServer(current, latest));
                            logger.info("Twitch follow removed for Minecraft player {}; Twitch access was revoked.", member.minecraftName);
                        }
                        return true;
                    }, false);
                } catch (Exception exception) {
                    warnThrottled("twitch", exception.getMessage());
                }
            }
        } finally {
            twitchPollRunning.set(false);
        }
    }

    private void reconcileSafely() {
        try {
            runOnServerAndWait(() -> {
                MinecraftServer current = server;
                if (current == null) return false;
                for (RegistrationStore.Member member : store.getMembersSnapshot()) {
                    reconcileMemberOnServer(current, member);
                }
                return true;
            }, false);
        } catch (Exception exception) {
            logger.error("Could not reconcile registration whitelist entries", exception);
        }
    }

    private void reconcileAllOnServer() {
        MinecraftServer current = server;
        if (current == null) return;
        for (RegistrationStore.Member member : store.getMembersSnapshot()) {
            if (member.hasActiveGrant(System.currentTimeMillis())) {
                ensureWhitelist(current, member.minecraftName, member.managedWhitelist);
            } else {
                reconcileMemberOnServer(current, member);
            }
        }
    }

    private void reconcileMemberOnServer(MinecraftServer current, RegistrationStore.Member member) {
        UUID uuid;
        try {
            uuid = UUID.fromString(member.uuid);
        } catch (IllegalArgumentException exception) {
            logger.error("Ignoring registration with invalid UUID for {}", member.minecraftName);
            return;
        }

        if (member.hasActiveGrant(System.currentTimeMillis())) return;
        if (member.managedWhitelist) removeWhitelistAndKick(current, member);
        store.removeMember(uuid);
        logger.info("Temporary whitelist access expired for {}.", member.minecraftName);
    }

    private void ensureWhitelist(MinecraftServer current, String playerName, boolean managed) {
        if (!managed || !MINECRAFT_NAME.matcher(playerName).matches()) return;
        performServerCommand(current, "whitelist add " + playerName);
    }

    private void revokeOnServer(MinecraftServer current, RegistrationStore.Member member) {
        if (member.managedWhitelist) removeWhitelistAndKick(current, member);
        store.removeMember(member.parsedUuid());
        logger.info("Registration grants revoked for {}.", member.minecraftName);
    }

    private void removeWhitelistAndKick(MinecraftServer current, RegistrationStore.Member member) {
        if (!MINECRAFT_NAME.matcher(member.minecraftName).matches()) return;
        performServerCommand(current, "whitelist remove " + member.minecraftName);
        if (current.getPlayerList().getPlayer(UUID.fromString(member.uuid)) != null) {
            performServerCommand(current, "kick " + member.minecraftName + " Срок доступа закончился или Twitch follow отменён.");
        }
    }

    private static void performServerCommand(MinecraftServer current, String command) {
        current.getCommands().performPrefixedCommand(
                current.createCommandSourceStack().withSuppressedOutput(), command);
    }

    private <T> T runOnServerAndWait(java.util.concurrent.Callable<T> action, T fallback) {
        MinecraftServer current = server;
        if (current == null) return fallback;
        if (current.isSameThread()) {
            try {
                return action.call();
            } catch (Exception exception) {
                logger.error("Server-thread registration operation failed", exception);
                return fallback;
            }
        }

        CompletableFuture<T> result = new CompletableFuture<>();
        current.execute(() -> {
            try {
                if (server != current) result.complete(fallback);
                else result.complete(action.call());
            } catch (Exception exception) {
                result.completeExceptionally(exception);
            }
        });
        try {
            return result.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return fallback;
        } catch (ExecutionException | TimeoutException exception) {
            logger.warn("Timed out waiting for the Minecraft server thread to apply a registration change");
            return fallback;
        }
    }

    private void warnThrottled(String service, String detail) {
        long now = System.currentTimeMillis();
        if ("donatepay".equals(service)) {
            if (now - lastDonatePayWarning < TimeUnit.MINUTES.toMillis(5)) return;
            lastDonatePayWarning = now;
        } else {
            if (now - lastTwitchWarning < TimeUnit.MINUTES.toMillis(5)) return;
            lastTwitchWarning = now;
        }
        logger.warn("{} registration check failed: {}. Access is not revoked on a temporary API error.",
                service, detail == null || detail.isBlank() ? "unknown error" : detail);
    }

    private static Component clickable(String label, String target) {
        return Component.literal(label).withStyle(Style.EMPTY
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.OpenUrl(URI.create(target))));
    }

}
