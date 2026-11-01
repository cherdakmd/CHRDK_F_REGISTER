package ru.cherdakmd.chrdkregister;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.WeightNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * Авто-настройка LuckPerms по схеме CHRDK ({@code /pass luckperms setup|status}).
 *
 * <p>Схема из трёх групп (создаётся идемпотентно — повторный запуск только достраивает):
 * {@code default} — база для всех (права {@code chrdk*.use} всех аддонов), {@code pass} —
 * наследует default и собирает кастомные права проходки
 * ({@code integrations.pass-group-permissions}), выдаётся автоматически через
 * {@link LuckPermsPassSync} при активной проходке; {@code admin} — наследует default
 * и получает все {@code chrdk*.admin} + модераторские права чата.</p>
 */
public final class LuckPermsBootstrap {

    /** Базовые права игрока — все аддоны семейства (.use из их plugin.yml). Держать синхронно при новых аддонах. */
    private static final List<String> DEFAULT_PERMS = List.of(
            "chrdkregister.menu",
            "chrdkregister.automessage.receive",
            "chrdkartifacts.use",
            "chrdkbp.use",
            "chrdkclaims.use",
            "chrdkgoals.use",
            "chrdkcosmetics.use",
            "chrdkdaily.use",
            "chrdklottery.use",
            "chrdkauction.use",
            "chrdkshop.use",
            "chrdkenchants.use",
            "chrdkranks.use",
            "chrdkref.use",
            "chrdktele.use",
            "chrdktierforge.use",
            "chrdkvk.use"
    );

    /** Админские права семейства + модераторские права чата. */
    private static final List<String> ADMIN_PERMS = List.of(
            "chrdkregister.admin",
            "chrdkregister.chat.color",
            "chrdkregister.chat.bypass",
            "chrdkregister.chat.socialspy",
            "chrdkregister.chat.clear",
            "chrdkregister.chat.broadcast",
            "chrdkregister.chat.mute",
            "chrdkregister.chat.history",
            "chrdkartifacts.admin",
            "chrdkbp.admin",
            "chrdkclaims.admin",
            "chrdkgoals.admin",
            "chrdkdaily.admin",
            "chrdklottery.admin",
            "chrdkauction.admin",
            "chrdkshop.admin",
            "chrdkenchants.admin",
            "chrdkranks.admin",
            "chrdkref.admin",
            "chrdktele.admin",
            "chrdktierforge.admin",
            "chrdkvk.admin"
    );

    /** Описание одной группы схемы: имя, вес, родитель, список нод-прав. */
    public record GroupSpec(String name, int weight, String parent, List<String> permissions) { }

    /** Итог setup: по группам — создана (true) или уже была; и сколько прав выставлено/идемпотентно подтверждено. */
    public record SetupResult(List<String> groups, int createdCount, int nodeCount) { }

    /** Строка статуса одной группы. */
    public record GroupStatus(String name, boolean exists, int weight, int permissionNodes) { }

    private final ExternalHooks hooks;
    private final Supplier<PluginConfig> config;

    public LuckPermsBootstrap(ExternalHooks hooks, Supplier<PluginConfig> config) {
        this.hooks = java.util.Objects.requireNonNull(hooks, "Initialize ExternalHooks before LuckPermsBootstrap");
        this.config = config;
    }

    /** Облегчённый конструктор для тестов: без LuckPerms-хука; setup/status не вызываются. */
    LuckPermsBootstrap(Supplier<PluginConfig> config) {
        this.hooks = null;
        this.config = config;
    }

    /** Есть ли LuckPerms (провайдер зарегистрирован). */
    public boolean available() {
        return hooks != null && hooks.luckPerms().isPresent();
    }

    /**
     * Команды, не нужные обычному игроку: в default выставляются ЯВНО выключенными
     * (value=false) — защита и от wildcard-выдач других плагинов, — а в admin те же
     * узлы возвращаются включёнными (вес admin 100 перекрывает default 0).
     */
    private static final List<String> COMMAND_PROTECT = List.of(
            "bukkit.command.plugins",
            "bukkit.command.version",
            "bukkit.command.about",
            "bukkit.command.timings",
            "bukkit.command.reload",
            "bukkit.command.restart",
            "minecraft.command.op",
            "minecraft.command.deop",
            "minecraft.command.stop",
            "minecraft.command.whitelist",
            "minecraft.command.ban",
            "minecraft.command.ban-ip",
            "minecraft.command.pardon",
            "minecraft.command.pardon-ip",
            "minecraft.command.kick"
    );

    /** Имя группы проходки: настроенное integrations.pass-group, иначе предлагаемое «pass». */
    public String passGroupName() {
        String group = config.get().passGroup();
        return group == null || group.isBlank() ? "pass" : group.trim();
    }

    /** Полная схема из конфига (имена/права подстраиваются). */
    public List<GroupSpec> scheme() {
        List<GroupSpec> specs = new ArrayList<>();
        specs.add(new GroupSpec("default", 0, null, DEFAULT_PERMS));
        specs.add(new GroupSpec(passGroupName(), 10, "default",
                List.copyOf(config.get().passGroupPermissions())));
        specs.add(new GroupSpec("admin", 100, "default", ADMIN_PERMS));
        // Группы товаров каталога: вес выше проходки, но ниже админа. Права товара
        // («+8 домов» и подобные) выдаются вместе с группой на срок покупки.
        for (ShopProduct product : config.get().shop().products()) {
            if (!product.hasGroup()) {
                continue;
            }
            String name = product.group().toLowerCase(Locale.ROOT);
            boolean taken = name.equals("default") || name.equals("admin")
                    || name.equals(passGroupName().toLowerCase(Locale.ROOT))
                    || specs.stream().anyMatch(spec -> spec.name().equalsIgnoreCase(name));
            if (taken) {
                continue;
            }
            specs.add(new GroupSpec(name, 20, "default", product.permissions()));
        }
        return List.copyOf(specs);
    }

    /** Создать/достроить все группы схемы (асинхронно). */
    public CompletableFuture<SetupResult> setup() {
        LuckPerms api = hooks.luckPerms().orElseThrow(
                () -> new IllegalStateException("LuckPerms не зарегистрирован"));
        List<String> groups = new ArrayList<>();
        int[] created = {0};
        int[] nodes = {0};
        // Правила самих плагинов берём у установленных плагинов (без хардкода):
        // для default — все chrdk*.use, для admin — все chrdk*.admin
        java.util.Set<String> discovered = discoverChrdkPerms();
        java.util.List<String> usePerms = mergeUnique(DEFAULT_PERMS, splitDiscovered(discovered, ".use"));
        java.util.List<String> adminPerms = mergeUnique(ADMIN_PERMS, splitDiscovered(discovered, ".admin"));
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (GroupSpec spec : scheme()) {
            java.util.List<String> resolved = switch (spec.name()) {
                case "default" -> usePerms;
                case "admin" -> adminPerms;
                default -> spec.permissions();
            };
            GroupSpec effective = new GroupSpec(spec.name(), spec.weight(), spec.parent(), resolved);
            chain = chain.thenCompose(ignored -> applySpec(api, effective, groups, created, nodes));
        }
        return chain.thenApply(ignored -> new SetupResult(List.copyOf(groups), created[0], nodes[0]));
    }

    /** Все зарегистрированные на сервере узлы chrdk* (из plugin.yml аддонов) — lower-case, без дублей. */
    static java.util.Set<String> discoverChrdkPerms() {
        java.util.Set<String> result = new java.util.TreeSet<>();
        try {
            org.bukkit.Bukkit.getPluginManager().getPermissions().forEach(permission -> {
                String key = permission.getName() == null ? "" : permission.getName().toLowerCase(Locale.ROOT);
                if (key.startsWith("chrdk")) {
                    result.add(key);
                }
            });
        } catch (Throwable ignored) {
            // тестовое окружение без Bukkit — вернём пустое
        }
        return result;
    }

    /** Выборка chrdk*-узлов из набора по суффиксу (.use/.admin), lower-case. */
    static java.util.List<String> splitDiscovered(java.util.Set<String> keys, String suffix) {
        java.util.List<String> result = new ArrayList<>();
        for (String key : keys) {
            String normalized = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
            if (normalized.startsWith("chrdk") && normalized.endsWith(suffix)
                    && !RetiredAddons.permission(normalized)) {
                result.add(normalized);
            }
        }
        return result;
    }

    /** Объединение базового списка с обнаруженным (без дублей, регистронезависимо). */
    static java.util.List<String> mergeUnique(java.util.List<String> base, java.util.Collection<String> extra) {
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>(base);
        for (String key : extra) {
            if (key != null && !key.isBlank()) {
                merged.add(key.trim().toLowerCase(Locale.ROOT));
            }
        }
        return List.copyOf(merged);
    }

    /** Текущее состояние групп схемы (асинхронно). */
    public CompletableFuture<List<GroupStatus>> status() {
        LuckPerms api = hooks.luckPerms().orElseThrow(
                () -> new IllegalStateException("LuckPerms не зарегистрирован"));
        List<CompletableFuture<GroupStatus>> futures = new ArrayList<>();
        for (GroupSpec spec : scheme()) {
            futures.add(api.getGroupManager().loadGroup(spec.name()).thenApply(opt -> {
                if (opt.isEmpty()) {
                    return new GroupStatus(spec.name(), false, 0, 0);
                }
                var group = opt.get();
                int weight = group.getWeight().orElse(0);
                int perms = (int) group.getNodes().stream()
                        .filter(node -> !node.getKey().startsWith("weight.")
                                && !node.getKey().startsWith("prefix.")
                                && !node.getKey().startsWith("suffix.")
                                && !node.getKey().startsWith("group."))
                        .count();
                return new GroupStatus(spec.name(), true, weight, perms);
            }));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(ignored -> futures.stream().map(CompletableFuture::join).toList());
    }

    /** Создать группу при отсутствии и выставить вес, родителя и права. */
    private CompletableFuture<Void> applySpec(LuckPerms api, GroupSpec spec,
                                              List<String> groups, int[] created, int[] nodes) {
        return api.getGroupManager().loadGroup(spec.name()).thenCompose(opt -> {
            groups.add(spec.name());
            if (opt.isEmpty()) {
                created[0]++;
            }
            CompletableFuture<?> ensure = opt.isPresent()
                    ? CompletableFuture.completedFuture(opt.get())
                    : api.getGroupManager().createAndLoadGroup(spec.name());
            return ensure.thenCompose(group -> api.getGroupManager().modifyGroup(spec.name(), live -> {
                live.data().clear(node -> node.getKey().startsWith("weight."));
                live.data().add(WeightNode.builder(spec.weight()).build());
                nodes[0]++;
                if (spec.parent() != null && !"default".equals(spec.name())) {
                    live.data().add(InheritanceNode.builder(spec.parent()).build());
                    nodes[0]++;
                }
                for (String permission : spec.permissions()) {
                    String key = permission.trim().toLowerCase(Locale.ROOT);
                    if (key.isEmpty()) {
                        continue;
                    }
                    live.data().add(Node.builder(key).build());
                    nodes[0]++;
                }
                // Защита команд: обычный игрок — явный запрет, админ — явное разрешение.
                if (config.get().lpCommandProtect()) {
                    if ("default".equals(spec.name())) {
                        for (String key : commandProtect()) {
                            live.data().add(Node.builder(key).value(false).build());
                            nodes[0]++;
                        }
                    } else if ("admin".equals(spec.name())) {
                        for (String key : commandProtect()) {
                            live.data().add(Node.builder(key).value(true).build());
                            nodes[0]++;
                        }
                    }
                }
            }).exceptionally(error -> {
                throw new CompletionException(new IllegalStateException(
                        "Не удалось настроить группу '" + spec.name() + "': " + error.getMessage(), error));
            }));
        });
    }

    /** Весь deny-лист: встроенный + доп. узлы из integrations.lp-deny-extra. */
    java.util.List<String> commandProtect() {
        java.util.List<String> extra = config.get().lpDenyExtra();
        if (extra.isEmpty()) {
            return COMMAND_PROTECT;
        }
        java.util.List<String> merged = new java.util.ArrayList<>(COMMAND_PROTECT);
        for (String permission : extra) {
            if (permission != null && !permission.isBlank()) {
                merged.add(permission.trim().toLowerCase(Locale.ROOT));
            }
        }
        return merged;
    }

    /** Нужна ли группа проходки подсказка: LuckPerms есть, а группа (настроенная) не создана. */
    public CompletableFuture<Boolean> passGroupMissing() {
        LuckPerms api = hooks.luckPerms().orElse(null);
        if (api == null) {
            return CompletableFuture.completedFuture(false);
        }
        String configured = config.get().passGroup();
        if (configured == null || configured.isBlank()) {
            return CompletableFuture.completedFuture(false); // синк выключен — не приставать
        }
        return api.getGroupManager().loadGroup(configured.trim())
                .thenApply(java.util.Optional::isEmpty)
                .exceptionally(error -> false);
    }
}
