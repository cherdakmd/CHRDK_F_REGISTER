package ru.cherdakmd.chrdkskills;

import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.Messages;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тексты единого меню и карточек: регрессия на два бага со скриншотов.
 *
 * <p>1) В чат и action-bar уезжал сырой {@code TextComponentImpl[content=...]},
 * потому что {@code get()} возвращал {@code Component}, а {@code Messages.fill}
 * превращал его в строку. 2) На 100 уровне полоса показывала
 * {@code 305332/0 (100%)}: {@code need == 0}, делить не на что.</p>
 */
class SkillsMenuTextTest {

    private static Messages messages() {
        try {
            String yml = Files.readString(Path.of("src/main/resources/messages.yml"));
            return Messages.from(new ByteArrayInputStream(yml.getBytes(StandardCharsets.UTF_8)),
                    null, Logger.getLogger("SkillsMenuTextTest"));
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Test
    void abilityNameIsRenderedAsMarkupNotComponentToString() {
        Messages messages = messages();
        Messages.Trusted name = SkillAbilities.abilityName(messages, SkillAbility.MINING);
        assertEquals("<gold>Бережная кирка</gold>", name.text());

        String activated = messages.raw("ability-activated",
                "name", name, "seconds", 8, "energy", 75);
        assertTrue(activated.contains("Бережная кирка"), activated);
        assertFalse(activated.contains("TextComponentImpl"), activated);
        assertFalse(activated.contains("content="), activated);

        String hud = messages.raw("ability-hud",
                "energy", 75, "name", name, "seconds", 8);
        assertTrue(hud.contains("Бережная кирка"), hud);
        assertFalse(hud.contains("TextComponentImpl"), hud);
    }

    @Test
    void everyAbilityHasAPlainNameWithoutComponentLeak() {
        Messages messages = messages();
        for (SkillAbility ability : SkillAbility.values()) {
            String name = SkillAbilities.abilityName(messages, ability).text();
            assertFalse(name.isBlank(), "нет названия приёма " + ability.id());
            assertFalse(name.contains("TextComponentImpl"), name);
            assertTrue(messages.has(ability.nameKey()), "нет ключа " + ability.nameKey());
        }
    }

    @Test
    void maxLevelUsesOwnTemplatesWithoutZeroDenominator() {
        Messages messages = messages();
        for (String key : new String[]{
                "menu-skill-progress-max", "detail-xp-max",
                "card-progress-max", "list-line-max"}) {
            assertTrue(messages.has(key), "нет шаблона максимума " + key);
            String template = messages.template(key);
            assertFalse(template.contains("%current%"), "в максимуме не должно быть current: " + key);
            assertFalse(template.contains("%need%"), "в максимуме не должно быть need: " + key);
            assertFalse(template.contains("%into%"), "в максимуме не должно быть into: " + key);
            assertTrue(template.contains("МАКС"), "нужен явный «МАКС»: " + key);
        }
        String rendered = messages.raw("menu-skill-progress-max",
                "bar", SkillCurve.bar(100, 10), "xp", 305332);
        assertFalse(rendered.contains("/0"), rendered);
        assertTrue(rendered.contains("305332"), rendered);
    }

    @Test
    void progressIsMaxedOnlyOnLastLevel() {
        SkillCurve.Params params = SkillCurve.Params.DEFAULT;
        assertFalse(SkillCurve.progress(150, params).maxed());
        assertFalse(SkillCurve.progress(131_174, params).maxed());
        assertTrue(SkillCurve.progress(131_175, params).maxed());
        assertTrue(SkillCurve.progress(436_507, params).maxed());
    }

    @Test
    void tabsAndStepsHaveRussianTexts() {
        Messages messages = messages();
        for (String key : new String[]{
                "menu-title", "menu-tab-skills", "menu-tab-tree", "menu-top",
                "menu-tab-selected", "menu-tab-hint", "menu-tab-domain",
                "menu-tab-domain-hint", "menu-guide-button", "menu-guide",
                "menu-top-hint", "menu-tree-unavailable", "menu-tree-no-tree",
                "menu-steps-title", "menu-skill-steps", "card-steps"}) {
            assertTrue(messages.has(key), "нет текста вкладки/ступеней " + key);
        }
        assertTrue(messages.template("menu-title").contains("Навыки"));
        assertTrue(messages.template("menu-tab-tree").contains("Дерево перков"));
        String guide = messages.template("menu-guide");
        assertTrue(guide.contains("12 ступеней"), "нужно число ступеней");
        assertTrue(guide.contains("одно меню"), "нужно объяснение вкладок");
        assertTrue(guide.contains("%steps%") || guide.contains("10, 15"),
                "нужны пороги ступеней");
        assertTrue(messages.getList("menu-guide").size() >= 6,
                "инструкция должна быть подробной");
        assertTrue(messages.template("menu-tree-unavailable").contains("CHRDK_Classes"));
    }

    @Test
    void allTwelveStepNamesAndDescriptionsExist() {
        Messages messages = messages();
        int count = 0;
        for (SkillType skill : SkillRegistry.all()) {
            for (SkillPerk perk : SkillRegistry.perksOf(skill)) {
                assertTrue(messages.has("perk." + perk.id() + ".name"),
                        "нет названия ступени " + perk.id());
                assertTrue(messages.has("perk." + perk.id() + ".desc"),
                        "нет описания ступени " + perk.id());
                count++;
            }
        }
        assertEquals(SkillRegistry.EXPECTED_SKILLS * SkillRegistry.PERKS_PER_SKILL, count);
    }

    @Test
    void thresholdLineDescribesAllElevenSteps() {
        String line = SkillRegistry.thresholdLine();
        for (int threshold : SkillRegistry.THRESHOLDS) {
            assertTrue(line.contains(Integer.toString(threshold)), line);
        }
        assertTrue(line.contains("100"));
        assertEquals(11, SkillRegistry.THRESHOLDS.size());
    }
}
