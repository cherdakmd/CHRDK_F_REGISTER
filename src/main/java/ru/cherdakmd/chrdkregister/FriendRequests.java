package ru.cherdakmd.chrdkregister;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Временные приглашения: оба участника онлайн проверяются вызывающим кодом. */
final class FriendRequests {
    static final long TTL_MILLIS = 60_000L;
    static final long COOLDOWN_MILLIS = 10_000L;
    static final int MAX_PENDING = 5;
    enum Result { OK, SELF, FRIENDS, PENDING, REVERSE_PENDING, NO_REQUEST, LIMIT, COOLDOWN, UNAVAILABLE }
    private record Pair(UUID sender, UUID recipient) { }
    private final FriendStore store;
    private final Map<Pair, Long> pending = new HashMap<>();
    private final Map<UUID, Long> lastInvite = new HashMap<>();

    FriendRequests(FriendStore store) { this.store = store; }

    synchronized Result invite(UUID sender, UUID recipient, long now) {
        expire(now);
        if (sender == null || recipient == null || !store.available()) return Result.UNAVAILABLE;
        if (sender.equals(recipient)) return Result.SELF;
        if (store.areFriends(sender, recipient)) return Result.FRIENDS;
        if (pending.containsKey(new Pair(sender, recipient))) return Result.PENDING;
        if (pending.containsKey(new Pair(recipient, sender))) return Result.REVERSE_PENDING;
        if (store.friendsOf(sender).size() >= FriendStore.MAX_FRIENDS
                || store.friendsOf(recipient).size() >= FriendStore.MAX_FRIENDS) return Result.LIMIT;
        long sent = pending.keySet().stream().filter(p -> p.sender().equals(sender)).count();
        long received = pending.keySet().stream().filter(p -> p.recipient().equals(recipient)).count();
        if (sent >= MAX_PENDING || received >= MAX_PENDING) return Result.LIMIT;
        Long last = lastInvite.get(sender);
        if (last != null && (now < last || now - last < COOLDOWN_MILLIS)) return Result.COOLDOWN;
        pending.put(new Pair(sender, recipient), now + TTL_MILLIS);
        lastInvite.put(sender, now);
        return Result.OK;
    }

    synchronized Result accept(UUID recipient, UUID sender, long now) {
        expire(now);
        if (recipient == null || sender == null || !store.available()) return Result.UNAVAILABLE;
        Pair request = new Pair(sender, recipient);
        if (!pending.containsKey(request)) return Result.NO_REQUEST;
        FriendStore.Result result = store.add(sender, recipient);
        if (result == FriendStore.Result.OK) {
            pending.remove(request);
            pending.remove(new Pair(recipient, sender));
            return Result.OK;
        }
        if (result == FriendStore.Result.ALREADY) { pending.remove(request); return Result.FRIENDS; }
        return result == FriendStore.Result.LIMIT ? Result.LIMIT : Result.UNAVAILABLE;
    }

    synchronized Result decline(UUID recipient, UUID sender, long now) {
        expire(now);
        return pending.remove(new Pair(sender, recipient)) != null ? Result.OK : Result.NO_REQUEST;
    }

    synchronized void quit(UUID player) {
        pending.keySet().removeIf(pair -> pair.sender().equals(player) || pair.recipient().equals(player));
        lastInvite.remove(player);
    }

    private void expire(long now) {
        pending.entrySet().removeIf(entry -> now >= entry.getValue());
        lastInvite.entrySet().removeIf(entry -> now >= entry.getValue() && now - entry.getValue() >= COOLDOWN_MILLIS);
    }
}
