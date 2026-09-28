package com.sawwik.pingshield;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Регрессия к багу 1.6.3: политика хронического пинга с действием {@code IMMUNITY_ONLY}
 * («щит без заморозки») не создавала защиту вообще.
 *
 * <p>Было так: {@code handleChronicPing()} ставил флаг {@code forcedImmunityOnly = true}
 * и возвращал {@code true}, а означало это «защиту в этом цикле не включать». Повтор в каждом
 * следующем цикле — и игрок с хронически плохим каналом не получал ни заморозки, ни обещанного
 * щита. Теперь решение вынесено в политику ({@link PingShieldConfig.ChronicAction}), а тест
 * фиксирует правило: пропускаем цикл только для {@code KICK}.</p>
 */
class ChronicActionPolicyTest {

    @Test
    @DisplayName("IMMUNITY_ONLY не пропускает цикл: щит без заморозки должен создаваться")
    void immunityOnlyDoesNotSkipCycle() {
        assertFalse(PingShieldConfig.ChronicAction.IMMUNITY_ONLY.skipsProtection(),
                "это и был баг 1.6.3: цикл пропускался, и защита не включалась");
        assertTrue(PingShieldConfig.ChronicAction.IMMUNITY_ONLY.immunityShield(),
                "действие обещает щит без заморозки");
    }

    @Test
    @DisplayName("KICK пропускает цикл: игрока отключаем, щит ему не нужен")
    void kickSkipsCycle() {
        assertTrue(PingShieldConfig.ChronicAction.KICK.skipsProtection());
        assertFalse(PingShieldConfig.ChronicAction.KICK.immunityShield());
    }

    @Test
    @DisplayName("NOTIFY и NONE — обычная работа защиты, без щита-исключения")
    void notifyAndNoneUseDefaultBehaviour() {
        for (PingShieldConfig.ChronicAction action : List.of(
                PingShieldConfig.ChronicAction.NOTIFY, PingShieldConfig.ChronicAction.NONE)) {
            assertFalse(action.skipsProtection(), action + ": защита работает как обычно");
            assertFalse(action.immunityShield(), action + ": щит-исключение не запрашивается");
        }
    }

    @Test
    @DisplayName("жизненный цикл хронического пинга: что делает каждое действие по шагам")
    void chronicLifecycleModel() {
        // Модель решения цикла (совпадает с handleChronicPing + beginProtection):
        //   chronicTriggered=false, плохих секунд мало      → ничего
        //   плохих секунд достаточно, первый раз           → действие срабатывает
        //   дальше в каждом цикле                          → решает политика
        Map<PingShieldConfig.ChronicAction, List<String>> journal =
                new EnumMap<>(PingShieldConfig.ChronicAction.class);

        for (PingShieldConfig.ChronicAction action : PingShieldConfig.ChronicAction.values()) {
            List<String> steps = new ArrayList<>();
            steps.add("до порога: защита как обычно (" + (action.skipsProtection() ? "" : "не ") + "пропуск)");
            if (action.immunityShield()) {
                steps.add("после срабатывания: цикл НЕ пропускается → защита создаётся в режиме «щит без заморозки»");
            } else if (action.skipsProtection()) {
                steps.add("после срабатывания: цикл пропускается → новых защит нет (игрока отключаем)");
            } else {
                steps.add("после срабатывания: обычная защита, в том числе с заморозкой");
            }
            journal.put(action, steps);
        }

        assertTrue(journal.get(PingShieldConfig.ChronicAction.IMMUNITY_ONLY).get(1)
                .contains("щит без заморозки"), "у IMMUNITY_ONLY щит обязан появиться");
        assertTrue(journal.get(PingShieldConfig.ChronicAction.NOTIFY).get(1).contains("заморозкой"),
                "у NOTIFY защита обычная");
    }

    @Test
    @DisplayName("штатный конфиг: действие по умолчанию — NOTIFY (не морозить и не калечить игрока)")
    void shippedDefaultActionIsNotify() {
        PingShieldConfig config = new PingShieldConfig();
        try (InputStream stream = PingShieldConfigTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(stream, "config.yml должен быть в ресурсах плагина");
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            config.load(yaml);
        } catch (Exception exception) {
            throw new AssertionError("конфиг должен читаться: " + exception, exception);
        }
        assertEquals(PingShieldConfig.ChronicAction.NOTIFY, config.chronicAction,
                "хронический пинг по умолчанию только уведомляет — игрок не должен страдать от политики");
        assertFalse(config.chronicAction.skipsProtection());
        assertFalse(config.chronicAction.immunityShield());
    }
}
