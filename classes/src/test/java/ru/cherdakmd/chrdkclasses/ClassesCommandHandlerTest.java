package ru.cherdakmd.chrdkclasses;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Разбор номера умения в командах {@code /ability} и {@code /class ability} (1.4.175).
 *
 * <p>Аргумент необязательный, и мусор в нём не должен мешать бою: игрок жмёт
 * горячую клавишу, а не читает справку. Поэтому всё, что не похоже на «второе»,
 * означает первое умение.</p>
 */
class ClassesCommandHandlerTest {

    @Test
    void firstAbilityIsTheDefault() {
        assertEquals(ClassAbility.FIRST, ClassesCommandHandler.abilitySlot(null));
        assertEquals(ClassAbility.FIRST, ClassesCommandHandler.abilitySlot(new String[0]));
        assertEquals(ClassAbility.FIRST, ClassesCommandHandler.abilitySlot(new String[]{""}));
        assertEquals(ClassAbility.FIRST, ClassesCommandHandler.abilitySlot(new String[]{"1"}));
        assertEquals(ClassAbility.FIRST, ClassesCommandHandler.abilitySlot(new String[]{"fire"}));
    }

    @Test
    void secondAbilityIsRecognised() {
        assertEquals(ClassAbility.SECOND, ClassesCommandHandler.abilitySlot(new String[]{"2"}));
        assertEquals(ClassAbility.SECOND,
                ClassesCommandHandler.abilitySlot(new String[]{" 2 "}));
        assertEquals(ClassAbility.SECOND,
                ClassesCommandHandler.abilitySlot(new String[]{"second"}));
        assertEquals(ClassAbility.SECOND,
                ClassesCommandHandler.abilitySlot(new String[]{"vtoroe"}));
    }
}
