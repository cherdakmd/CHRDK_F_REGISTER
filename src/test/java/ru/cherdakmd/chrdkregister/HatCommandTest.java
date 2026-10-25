package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Доступ к {@code /hat} (1.4.167).
 *
 * <p>Сама проверка «блок или нет» — один вызов {@code Material.isBlock()},
 * который без сервера инициализирует реестр и падает в тестах, поэтому здесь
 * проверяется то, что действительно можно проверить без Bukkit: ворота
 * ступени и право-обход.</p>
 */
class HatCommandTest {

    @Test
    void onlyTopTierOrPermissionGetsHat() {
        assertFalse(HatCommand.allowed(0, 5, false), "без ранга /hat закрыта");
        assertFalse(HatCommand.allowed(4, 5, false), "Меценату ещё рано");
        assertTrue(HatCommand.allowed(5, 5, false), "Покровитель — да");
        assertTrue(HatCommand.allowed(5, 1, false), "порог из конфига уважается");
        assertFalse(HatCommand.allowed(1, 3, false));
    }

    @Test
    void permissionBypassesTheLadder() {
        assertTrue(HatCommand.allowed(0, 5, true), "администратору не нужен ранг");
        assertTrue(HatCommand.allowed(1, 5, true));
    }
}
