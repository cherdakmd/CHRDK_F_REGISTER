package ru.cherdakmd.chrdkclasses;

import ru.cherdakmd.chrdkskills.tree.PerkTreeDefinition;
import ru.cherdakmd.chrdkskills.tree.PerkTreeMath;
import ru.cherdakmd.chrdkskills.tree.PerkTreeRegistry;
import ru.cherdakmd.chrdkskills.tree.PerkTreeState;
import ru.cherdakmd.chrdkskills.tree.TreeDomain;

import java.util.Locale;

/**
 * Чистая логика плейсхолдеров дерева: без Bukkit и PlaceholderAPI, поэтому
 * покрывается обычным JVM-тестом. {@link ClassesPlaceholders} лишь достаёт
 * состояние из {@link PerkTreeService} и передаёт его сюда.
 *
 * <p>Ключи: {@code %chrdkclasses_tree_<поле>%} для первичного домена класса и
 * {@code %chrdkclasses_tree_<домен>_<поле>%} для явного домена. Поля:
 * {@code domain, level, powder, tokens, tokens_spent, nodes, ability,
 * available, tier, tiers, next_tier}. Неизвестное поле и недоступное дерево дают пустую строку, чтобы
 * условия в TAB и скорборде не срабатывали ложно; исключение —
 * {@code available}, который явно возвращает {@code false}.</p>
 */
final class PerkTreePlaceholders {

    private static final String PREFIX = "tree_";

    record Parsed(TreeDomain domain, String field) {
    }

    private PerkTreePlaceholders() {
    }

    /** {@code null} — ключ не относится к дереву перков. */
    static Parsed parse(String key) {
        if (key == null) {
            return null;
        }
        String normalized = key.toLowerCase(Locale.ROOT);
        if (normalized.equals("tree")) {
            return new Parsed(null, "level");
        }
        if (!normalized.startsWith(PREFIX)) {
            return null;
        }
        String tail = normalized.substring(PREFIX.length());
        int separator = tail.indexOf('_');
        if (separator > 0) {
            String candidate = tail.substring(0, separator);
            for (TreeDomain value : TreeDomain.values()) {
                if (value.id().equals(candidate)) {
                    return new Parsed(value, tail.substring(separator + 1));
                }
            }
        }
        return new Parsed(null, tail);
    }

    static String resolve(PerkTreeState state, TreeDomain domain, String field) {
        if (state == null || domain == null || field == null) {
            return field != null && field.equals("available") ? "false" : "";
        }
        return switch (field) {
            case "domain" -> domain.id();
            case "level" -> Integer.toString(state.level());
            case "powder" -> Long.toString(state.powder());
            case "tokens" -> Integer.toString(state.tokensAvailable());
            case "tokens_spent" -> Integer.toString(state.tokensSpent());
            case "nodes" -> Integer.toString(state.levels().size());
            case "ability" -> state.selectedAbility() == null ? "" : state.selectedAbility();
            case "available" -> "true";
            case "tier" -> Integer.toString(PerkTreeMath.availableTier(
                    state.level(), tiers(domain)));
            case "tiers" -> Integer.toString(tiers(domain));
            case "next_tier" -> Integer.toString(nextTier(state.level(), tiers(domain)));
            default -> "";
        };
    }

    /** Число тиров зарегистрированного дерева; для резервных — потолок каркаса. */
    private static int tiers(TreeDomain domain) {
        return PerkTreeRegistry.of(domain).map(PerkTreeDefinition::tiers).orElse(10);
    }

    private static int nextTier(int level, int tiers) {
        int available = PerkTreeMath.availableTier(level, tiers);
        return available >= tiers ? 0
                : PerkTreeMath.LEVELS_PER_TOKEN - (level % PerkTreeMath.LEVELS_PER_TOKEN);
    }
}
