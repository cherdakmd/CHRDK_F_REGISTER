package ru.cherdakmd.chrdksocial;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Единый роутер интерактива сообщества (№12): одну и ту же схему
 * {@link SocialCallback.Action} обрабатывают и VK ({@code message_event}), и
 * MAX ({@code message_callback}). Провайдеры различаются только транспортом
 * ответа — snackbar/reply/edit у VK и обычный reply у MAX.
 *
 * <p>Здесь же живут диалоги подтверждения, опросы, тикеты и кулдауны. Хаб не
 * знает о Bukkit, токенах и игровой логике: опасные действия delegated в
 * {@link ActionExecutor}, который реализуют сервисы провайдеров.</p>
 */
public final class SocialInteractionHub {

    public record Reply(String text, String snackbar, boolean edit,
                        List<SocialCallback.Button> buttons) {
        public static Reply empty() {
            return new Reply("", "", false, List.of());
        }
        public boolean hasContent() {
            return !text.isBlank() || !snackbar.isBlank();
        }
    }

    public record AuditRow(String event, String player, String provider, String detail) { }

    public interface ActionExecutor {
        /** Выполняет подтверждённое действие и возвращает текст для пользователя. */
        String run(String provider, String kind, String arg, long userId, long now);

        /** Ник для аудита; пустая строка, если привязки нет. */
        default String playerName(String provider, long userId) {
            return "";
        }
    }

    private volatile SocialInteractionConfig config;
    private final ActionExecutor executor;
    private final Consumer<AuditRow> audit;
    private final SocialDialogStore dialogs = new SocialDialogStore();
    private final SocialPollService polls = new SocialPollService();
    private final SocialTicketService tickets = new SocialTicketService();
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();

    public SocialInteractionHub(SocialInteractionConfig config,
                                ActionExecutor executor,
                                Consumer<AuditRow> audit) {
        this.config = config == null ? SocialInteractionConfig.DEFAULT : config;
        this.executor = executor;
        this.audit = audit == null ? row -> { } : audit;
    }

    public void applyConfig(SocialInteractionConfig next) {
        this.config = next == null ? SocialInteractionConfig.DEFAULT : next;
        cooldowns.clear();
        dialogs.purge(Long.MAX_VALUE);
    }

    public SocialInteractionConfig config() {
        return config;
    }

    public SocialPollService polls() {
        return polls;
    }

    public SocialTicketService tickets() {
        return tickets;
    }

    public SocialDialogStore dialogs() {
        return dialogs;
    }

    /** Точка входа для обоих провайдеров. */
    public Reply handle(String provider, long userId, String payload, long now) {
        SocialCallback.Action action = SocialCallback.parse(payload);
        SocialInteractionConfig cfg = config;
        if (!action.known() || !cfg.enabled() || !cfg.callbacks()) {
            return Reply.empty();
        }
        String providerKey = safeProvider(provider);
        String key = providerKey + ":" + userId;
        if (!allowCooldown(key, action.kind(), now, cfg.cooldownMillis())) {
            return new Reply("", "⏳ Слишком часто, попробуйте позже", false, List.of());
        }
        String player = executor.playerName(providerKey, userId);
        String auditPlayer = player.isEmpty() ? key : player;
        audit.accept(new AuditRow("social.interaction." + action.kind(), auditPlayer,
                providerKey, "arg=" + action.arg()));

        return switch (action.kind()) {
            case "help" -> new Reply(helpText(), "", false, menuButtons(cfg));
            case "cancel" -> {
                dialogs.end(key);
                yield new Reply("❌ Действие отменено.", "Отменено", false, List.of());
            }
            case "link_wizard" -> linkWizard(cfg, key, now);
            case "request" -> request(cfg, key, action.arg(), now);
            case "confirm" -> confirm(cfg, providerKey, key, action.arg(), userId, now);
            case "ticket" -> ticket(cfg, providerKey, key, userId, now);
            case "poll_new" -> pollNew(cfg, now);
            case "poll" -> pollVote(cfg, key, action.arg(), now);
            default -> Reply.empty();
        };
    }

    /* ------------------------------- сценарии ------------------------------ */

    private Reply linkWizard(SocialInteractionConfig cfg, String key, long now) {
        if (cfg.dialogs()) {
            dialogs.begin(key, "link_wizard", "", cfg.dialogTtlMillis(), now);
        }
        return new Reply("""
                🔗 Привязка аккаунта
                1. Зайдите на сервер — в чате появится одноразовый код.
                2. Отправьте его сюда одним сообщением.
                3. Ограничения снимутся без релога.
                🔒 Пароль и код 2FA боту не отправляйте.""", "", false,
                List.of(button("❌ Отмена", "cancel", "", "negative")));
    }

    private Reply request(SocialInteractionConfig cfg, String key, String arg, long now) {
        if (!cfg.dialogs() || !dangerous(arg)) {
            return new Reply("⚠ Это действие недоступно.", "Недоступно", false, List.of());
        }
        dialogs.begin(key, "confirm", arg, cfg.dialogTtlMillis(), now);
        return new Reply("⚠ Подтвердите: " + label(arg) + "\nНажмите «Да» в течение "
                + cfg.dialogTtlSeconds() + " с.", "Нужно подтверждение", false,
                List.of(button("✅ Да", "confirm", arg, "negative"),
                        button("❌ Отмена", "cancel", "", "secondary")));
    }

    private Reply confirm(SocialInteractionConfig cfg, String provider, String key, String arg,
                          long userId, long now) {
        if (!cfg.dialogs() || !dangerous(arg)) {
            return new Reply("⚠ Подтверждение недоступно.", "Недоступно", false, List.of());
        }
        SocialDialogStore.State state = dialogs.peek(key, now);
        if (state == null || !state.matches("confirm") || !state.arg().equals(arg)) {
            return new Reply("⚠ Подтверждение не найдено или устарело.", "Устарело", false, List.of());
        }
        dialogs.end(key);
        String result = executor.run(provider, arg, "", userId, now);
        return new Reply(result, "Готово", false, List.of());
    }

    private Reply ticket(SocialInteractionConfig cfg, String provider, String key,
                         long userId, long now) {
        if (!cfg.tickets()) {
            return new Reply("⚠ Тикеты выключены.", "Недоступно", false, List.of());
        }
        String player = executor.playerName(provider, userId);
        var ticket = tickets.create(player.isEmpty() ? key : player,
                "Запрос из сообщества", now, cfg.cooldownMillis());
        if (ticket.isEmpty()) {
            return new Reply("⏳ Запрос уже отправлен, подождите.", "Подождите", false, List.of());
        }
        String result = executor.run(provider, "ticket", ticket.get().id(), userId, now);
        return new Reply(result, "Заявка отправлена", false, List.of());
    }

    private Reply pollNew(SocialInteractionConfig cfg, long now) {
        if (!cfg.polls()) {
            return new Reply("⚠ Опросы выключены.", "Недоступно", false, List.of());
        }
        String id = polls.open(cfg.pollQuestion(), cfg.pollDurationSeconds(), now);
        if (id.isEmpty()) return Reply.empty();
        return new Reply("🗳 " + cfg.pollQuestion() + "\n✅ 0 · ❌ 0", "Опрос создан", false,
                pollButtons(id));
    }

    private Reply pollVote(SocialInteractionConfig cfg, String key, String arg, long now) {
        if (!cfg.polls()) {
            return new Reply("⚠ Опросы выключены.", "Недоступно", false, List.of());
        }
        int sep = arg.indexOf(':');
        String id = sep < 0 ? "" : arg.substring(0, sep);
        boolean yes = sep >= 0 && "yes".equalsIgnoreCase(arg.substring(sep + 1));
        boolean accepted = polls.vote(id, key, yes, now);
        String text = polls.activeText(id, now).orElse("🗳 Голосование закрыто.");
        return new Reply(text, accepted ? "🗳 Голос учтён" : "⏳ Голос не принят", true,
                accepted ? pollButtons(id) : List.of());
    }

    /* -------------------------------- helpers ------------------------------- */

    private boolean allowCooldown(String key, String kind, long now, long cooldownMillis) {
        if (cooldownMillis <= 0L) return true;
        String ck = key + ":" + kind;
        Long previous = cooldowns.get(ck);
        if (previous != null && now - previous < cooldownMillis) return false;
        cooldowns.put(ck, now);
        if (cooldowns.size() > 4096) {
            cooldowns.entrySet().removeIf(e -> now - e.getValue() > Math.max(cooldownMillis, 3_600_000L));
        }
        return true;
    }

    private static boolean dangerous(String arg) {
        return switch (arg) {
            case "logout", "freeze", "unfreeze", "unlink", "ticket" -> true;
            default -> false;
        };
    }

    private static String label(String arg) {
        return switch (arg) {
            case "logout" -> "сбросить все сессии";
            case "freeze" -> "заморозить вход";
            case "unfreeze" -> "разморозить вход";
            case "unlink" -> "отвязать аккаунт";
            case "ticket" -> "позвать администратора";
            default -> arg;
        };
    }

    /** Кнопки стартового меню; используются и как точка входа в интерактив. */
    public static List<SocialCallback.Button> menuButtons(SocialInteractionConfig cfg) {
        java.util.ArrayList<SocialCallback.Button> out = new java.util.ArrayList<>();
        out.add(button("🔗 Привязать", "link_wizard", "", "primary"));
        out.add(button("🆘 Администратор", "ticket", "", "secondary"));
        out.add(button("🚪 Сессии", "request", "logout", "secondary"));
        out.add(button("🧊 Заморозить", "request", "freeze", "negative"));
        if (cfg.polls()) out.add(button("🗳 Опрос", "poll_new", "", "secondary"));
        return List.copyOf(out);
    }

    private static List<SocialCallback.Button> pollButtons(String id) {
        return List.of(button("✅ За", "poll", id + ":yes", "positive"),
                button("❌ Против", "poll", id + ":no", "negative"));
    }

    private static SocialCallback.Button button(String label, String kind, String arg, String color) {
        return new SocialCallback.Button(label, kind, arg, color);
    }

    private static String safeProvider(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return value.equals("vk") || value.equals("max") ? value : "unknown";
    }

    private static String helpText() {
        return """
                👋 Интерактивное меню CHERDAK
                • 🔗 Привязать — мастер привязки аккаунта
                • 🆘 Администратор — отправить заявку staff
                • 🚪 Сессии / 🧊 Заморозить — с подтверждением
                • 🗳 Опрос — голосование в беседе
                🔒 Пароль и код 2FA через бота не меняются.""";
    }
}
