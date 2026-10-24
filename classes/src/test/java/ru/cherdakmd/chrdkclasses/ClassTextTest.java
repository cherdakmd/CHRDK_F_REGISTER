package ru.cherdakmd.chrdkclasses;

import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.Messages;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Показ результатов операций (1.4.164): иды заменяются именами, мусор остаётся.
 *
 * <p>Сервис возвращает {@code class} → {@code warrior}, чтобы его можно было
 * проверять без сервера. Игрок же должен видеть «Воин», а не «warrior».
 * Ошибка в идe (опечатка в команде) не должна превращаться в пустую строку.</p>
 */
class ClassTextTest {

    private static final Logger LOGGER = Logger.getLogger("ClassTextTest");

    private static Messages messages() {
        String yaml = """
                classes:
                  class.warrior.name: "Воин"
                  class.cleric.name: "Жрец"
                  talent.warrior-stoyka-1.name: "Стойкость I"
                  skill.defense.name: "Оборона"
                """;
        return Messages.from(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)),
                null, LOGGER);
    }

    @Test
    void knownIdsBecomeNames() {
        ClassResult chosen = ClassResult.ok("class-chosen", "class", "warrior");
        assertEquals(List.of("class", "Воин"), ClassText.localise(messages(), chosen));

        ClassResult learned = ClassResult.ok("talent-learned", "talent", "warrior-stoyka-1",
                "points", "3");
        assertEquals(List.of("talent", "Стойкость I", "points", "3"),
                ClassText.localise(messages(), learned));

        ClassResult skill = ClassResult.fail("talent-skill", "skill", "defense",
                "level", "20", "have", "10");
        assertEquals(List.of("skill", "Оборона", "level", "20", "have", "10"),
                ClassText.localise(messages(), skill));
    }

    @Test
    void unknownIdsStayAsTyped() {
        ClassResult unknown = ClassResult.fail("class-unknown", "class", "паладин");
        assertEquals(List.of("class", "паладин"), ClassText.localise(messages(), unknown));

        ClassResult caseOnly = ClassResult.ok("class-chosen", "class", "WARRIOR");
        assertEquals(List.of("class", "Воин"), ClassText.localise(messages(), caseOnly),
                "ид не зависит от регистра");
    }

    @Test
    void nonIdArgumentsAreUntouched() {
        ClassResult respec = ClassResult.ok("respec-done", "talents", "4", "points", "9");
        assertEquals(List.of("talents", "4", "points", "9"),
                ClassText.localise(messages(), respec));

        ClassResult cooldown = ClassResult.fail("class-cooldown", "time", "2 ч 5 мин");
        assertEquals(List.of("time", "2 ч 5 мин"), ClassText.localise(messages(), cooldown));
    }

    @Test
    void missingMessagesDoNotBreakAnything() {
        List<String> args = ClassText.localise(null, ClassResult.ok("x", "class", "warrior"));
        assertEquals(List.of("class", "warrior"), args);
        assertFalse(args.isEmpty());

        ClassResult empty = ClassResult.ok("admin-reset");
        assertEquals(List.of(), ClassText.localise(messages(), empty));
        assertEquals(List.of(), ClassText.localise(messages(), null));
    }

    @Test
    void oddArgumentsAreIgnored() {
        ClassResult odd = ClassResult.ok("x", "class");
        assertEquals(List.of("class"), ClassText.localise(messages(), odd));
    }
}
