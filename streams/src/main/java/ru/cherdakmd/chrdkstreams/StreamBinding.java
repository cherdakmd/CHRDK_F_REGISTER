package ru.cherdakmd.chrdkstreams;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Привязка игрока к каналам и его прогресс.
 *
 * <p>С 1.4.121 у стримера может быть <b>несколько каналов</b> — по одному на
 * площадку (Twitch и VK Play Live одновременно). Прогресс при этом общий:
 * час эфира даёт час ранга независимо от того, сколько площадок стримит
 * игрок в эту минуту, — иначе одновременная трансляция удваивала бы награду.</p>
 *
 * <p>Запись неизменяемая, сервис заменяет её целиком через {@code with...}
 * методы: так нет «наполовину обновлённой» записи и легко проверять арифметику
 * в тестах.</p>
 *
 * @param uuid       игрок
 * @param playerName ник на момент привязки (для логов и {@code /stream stats})
 * @param channels   привязанные каналы (по одному на площадку)
 * @param state      накопленный прогресс наград
 */
record StreamBinding(UUID uuid, String playerName, List<Channel> channels,
                     StreamRewards.State state) {

    /**
     * Один привязанный канал.
     *
     * @param platform   площадка
     * @param channel    имя канала в том виде, как его ввёл игрок
     * @param code       код подтверждения ({@code CHRDK-XXXX})
     * @param verifiedAt когда канал подтверждён; {@code 0} — ещё нет
     */
    record Channel(StreamPlatform platform, String channel, String code, long verifiedAt) {

        Channel {
            channel = channel == null ? "" : channel.trim();
            code = code == null ? "" : code.trim();
        }

        /** Канал подтверждён кодом в названии эфира. */
        boolean verified() {
            return verifiedAt > 0;
        }

        /** Ссылка на канал для сообщений. */
        String url() {
            return platform == null ? "" : platform.urlFor(channel);
        }

        /** Подпись «площадка/канал» для логов и сообщений. */
        String label() {
            return (platform == null ? "?" : platform.id()) + "/" + channel;
        }

        /** Пометить канал подтверждённым (код найден в названии эфира). */
        Channel markVerified(long nowMillis) {
            return new Channel(platform, channel, code, nowMillis);
        }
    }

    StreamBinding {
        playerName = playerName == null ? "" : playerName.trim();
        state = state == null ? StreamRewards.State.empty() : state;
        List<Channel> clean = new ArrayList<>();
        if (channels != null) {
            for (Channel channel : channels) {
                if (channel == null || channel.platform() == null || channel.channel().isEmpty()) {
                    continue;
                }
                boolean duplicate = clean.stream().anyMatch(existing ->
                        existing.platform() == channel.platform()
                                && existing.channel().equalsIgnoreCase(channel.channel()));
                if (!duplicate) {
                    clean.add(channel);
                }
            }
        }
        channels = List.copyOf(clean);
    }

    /** Есть ли хоть один привязанный канал. */
    boolean linked() {
        return !channels.isEmpty();
    }

    /**
     * «Основной» канал для плейсхолдеров и кратких сообщений: первый
     * подтверждённый, а если подтверждённых нет — первый привязанный.
     */
    Optional<Channel> primary() {
        Optional<Channel> verified = channels.stream().filter(Channel::verified).findFirst();
        return verified.isPresent() ? verified : channels.stream().findFirst();
    }

    /** Канал на конкретной площадке (у площадки не больше одного канала). */
    Optional<Channel> find(StreamPlatform platform) {
        if (platform == null) {
            return Optional.empty();
        }
        return channels.stream().filter(channel -> channel.platform() == platform).findFirst();
    }

    /** Привязан ли именно этот канал (регистр не важен). */
    boolean has(StreamPlatform platform, String channel) {
        return find(platform).map(existing -> existing.channel().equalsIgnoreCase(
                channel == null ? "" : channel.trim())).orElse(false);
    }

    /** Канал игрока подтверждён хотя бы на одной площадке. */
    boolean verified() {
        return channels.stream().anyMatch(Channel::verified);
    }

    long verifiedAt() {
        return primary().filter(Channel::verified).map(Channel::verifiedAt).orElse(0L);
    }

    /** Площадка основного канала; {@code null}, если каналов нет. */
    StreamPlatform platform() {
        return primary().map(Channel::platform).orElse(null);
    }

    /** Имя основного канала; пустая строка, если каналов нет. */
    String channel() {
        return primary().map(Channel::channel).orElse("");
    }

    String code() {
        return primary().map(Channel::code).orElse("");
    }

    String url() {
        return primary().map(Channel::url).orElse("");
    }

    /** Каналы, прошедшие проверку кодом. */
    List<Channel> verifiedChannels() {
        return channels.stream().filter(Channel::verified).toList();
    }

    /** «twitch/foo, vkplay/bar» — для сообщений «уже привязаны». */
    String labels() {
        return channels.stream().map(Channel::label)
                .reduce((left, right) -> left + ", " + right).orElse("");
    }

    /**
     * Добавляет канал или заменяет канал той же площадки: у одной площадки
     * не может быть двух привязок.
     */
    StreamBinding withChannel(Channel fresh) {
        if (fresh == null || fresh.platform() == null || fresh.channel().isEmpty()) {
            return this;
        }
        List<Channel> updated = new ArrayList<>();
        for (Channel existing : channels) {
            if (existing.platform() != fresh.platform()) {
                updated.add(existing);
            }
        }
        updated.add(fresh);
        return new StreamBinding(uuid, playerName, updated, state);
    }

    /** Убирает все каналы площадки (отвязка). */
    StreamBinding without(StreamPlatform platform) {
        if (platform == null) {
            return this;
        }
        List<Channel> updated = channels.stream()
                .filter(channel -> channel.platform() != platform)
                .toList();
        return updated.size() == channels.size()
                ? this
                : new StreamBinding(uuid, playerName, updated, state);
    }

    /** Помечает конкретный канал подтверждённым. */
    StreamBinding markVerified(StreamPlatform platform, String channel, long nowMillis) {
        String wanted = channel == null ? "" : channel.trim();
        List<Channel> updated = new ArrayList<>(channels.size());
        for (Channel existing : channels) {
            if (existing.platform() == platform && existing.channel().equalsIgnoreCase(wanted)) {
                updated.add(existing.markVerified(nowMillis));
            } else {
                updated.add(existing);
            }
        }
        return new StreamBinding(uuid, playerName, updated, state);
    }

    StreamBinding withState(StreamRewards.State newState) {
        return new StreamBinding(uuid, playerName, channels, newState);
    }

    StreamBinding renamed(String newName) {
        return new StreamBinding(uuid, newName, channels, state);
    }
}
