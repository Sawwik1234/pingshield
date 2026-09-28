package com.sawwik.pingshield;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты разбора конфига.
 *
 * <p>Главный из них — «штатный config.yml читается и даёт безопасные значения по умолчанию»:
 * он ловит и опечатки в YAML, и расхождение между именами ключей в файле и в коде
 * (самая частая причина «настройка не работает»).</p>
 */
class PingShieldConfigTest {

    /** Загружает config.yml из ресурсов плагина — тот самый файл, что получают админы. */
    private static YamlConfiguration shipped() {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStream stream = PingShieldConfigTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(stream, "config.yml должен быть в ресурсах плагина");
            yaml.loadFromString(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new AssertionError("config.yml не читается: " + exception, exception);
        }
        return yaml;
    }

    private static PingShieldConfig loadFrom(String yaml) {
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.loadFromString(yaml);
        } catch (Exception exception) {
            throw new AssertionError("тестовый YAML не читается: " + exception, exception);
        }
        PingShieldConfig config = new PingShieldConfig();
        config.load(configuration);
        return config;
    }

    @Test
    @DisplayName("Штатный config.yml разбирается без ошибок")
    void shippedConfigLoads() {
        PingShieldConfig config = new PingShieldConfig();
        assertDoesNotThrow(() -> config.load(shipped()));

        assertEquals(3000, config.enterThresholdMs);
        assertEquals(2000, config.exitThresholdMs);
        assertFalse(config.msgProtectStart.isEmpty(), "сообщение «вы временно защищены» должно быть задано");
        assertTrue(config.enabled, "плагин включён в штатном конфиге");
    }

    @Test
    @DisplayName("Штатные значения анти-абуза безопасны для честных игроков")
    void shippedDefaultsAreSafe() {
        PingShieldConfig config = new PingShieldConfig();
        config.load(shipped());

        assertEquals(PingShieldConfig.SuspiciousPolicy.LOG, config.suspiciousPolicy,
                "детект абуза сперва только пишет в audit.log");
        assertTrue(config.suspiciousEnabled);
        assertEquals(3, config.suspiciousThreshold);

        assertEquals(PingShieldConfig.ChronicAction.NOTIFY, config.chronicAction,
                "игрок с хронически плохим каналом по умолчанию получает только уведомление");
        assertEquals(600, config.chronicPingSeconds);
        assertEquals(120, config.chronicResetAfterGoodSeconds);

        assertEquals(PingShieldConfig.StormPolicy.ALERT_ONLY, config.stormPolicy,
                "шторм по умолчанию лишь уведомляет персонал, защита не отключается");
        assertEquals(3, config.stormMinPlayersSameSubnet);
        assertEquals(120, config.stormHoldSeconds);
        assertTrue(config.stormIgnoreSubnets.isEmpty());
    }

    @Test
    @DisplayName("элитры, воздух и песок: штатные значения безопасны")
    void flyingKickDefaults() {
        PingShieldConfig config = new PingShieldConfig();
        config.load(shipped());
        assertTrue(config.keepGliding, "freeze.keep-gliding: заморозка не должна сворачивать элитры — "
                + "иначе сервер кикает игрока в воздухе через 4 секунды");
        assertTrue(config.preventFlyingKick, "freeze.prevent-flying-kick: страховка включена по умолчанию");
        assertTrue(config.keepAir, "freeze.keep-air: замороженный под водой игрок не должен тонуть");
        assertTrue(config.escapeSuffocation, "release.safety.escape-suffocation: засыпанного песком выпускаем");
        assertEquals(8, config.escapeSearchBlocks, "release.safety.escape-search-blocks");
    }

    @Test
    @DisplayName("Пять новых сообщений присутствуют в штатном config.yml")
    void shippedMessagesExist() {
        PingShieldConfig config = new PingShieldConfig();
        config.load(shipped());

        assertFalse(config.msgSuspiciousAlert.isEmpty(), "messages.suspicious-alert");
        assertFalse(config.msgChronicNotify.isEmpty(), "messages.chronic-ping-notify");
        assertFalse(config.msgChronicKickWarning.isEmpty(), "messages.chronic-ping-kick-warning");
        assertFalse(config.msgChronicKick.isEmpty(), "messages.chronic-ping-kick");
        assertFalse(config.msgStormAlert.isEmpty(), "messages.storm-alert");
        assertFalse(config.msgStormPause.isEmpty(), "messages.storm-pause");
    }

    @Test
    @DisplayName("Штатный конфиг не порождает предупреждений об анти-абузе и шторме")
    void shippedConfigHasNoAbuseWarnings() {
        PingShieldConfig config = new PingShieldConfig();
        config.load(shipped());
        List<String> warnings = config.validate();

        List<String> suspicious = warnings.stream()
                .filter(warning -> warning.contains("abuse.") || warning.contains("storm."))
                .toList();
        assertTrue(suspicious.isEmpty(), "штатный конфиг должен быть согласованным, но: " + suspicious);
    }

    @Test
    @DisplayName("Пустой конфиг даёт консервативные значения по умолчанию")
    void emptyConfigDefaults() {
        PingShieldConfig config = loadFrom("");

        assertEquals(PingShieldConfig.SuspiciousPolicy.LOG, config.suspiciousPolicy);
        assertEquals(PingShieldConfig.ChronicAction.NOTIFY, config.chronicAction);
        assertEquals(PingShieldConfig.StormPolicy.ALERT_ONLY, config.stormPolicy);
        assertEquals(3, config.suspiciousThreshold);
        assertEquals(600, config.chronicPingSeconds);
        assertEquals(3, config.stormMinPlayersSameSubnet);
        assertEquals(30, config.chronicKickWarningSeconds);
        assertEquals(300, config.stormAlertCooldownSeconds);
    }

    @Test
    @DisplayName("Политики разбираются без учёта регистра, неизвестное значение → безопасный откат")
    void policyParsing() {
        PingShieldConfig config = loadFrom("""
                abuse:
                  suspicious:
                    policy: no_freeze
                  chronic-ping:
                    action: immunity_only
                storm:
                  policy: pause_subnet
                """);
        assertEquals(PingShieldConfig.SuspiciousPolicy.NO_FREEZE, config.suspiciousPolicy);
        assertEquals(PingShieldConfig.ChronicAction.IMMUNITY_ONLY, config.chronicAction);
        assertEquals(PingShieldConfig.StormPolicy.PAUSE_SUBNET, config.stormPolicy);

        PingShieldConfig broken = loadFrom("""
                abuse:
                  suspicious:
                    policy: что-то_своё
                  chronic-ping:
                    action: телепорт
                storm:
                  policy: 42
                """);
        assertEquals(PingShieldConfig.SuspiciousPolicy.LOG, broken.suspiciousPolicy, "откат к LOG");
        assertEquals(PingShieldConfig.ChronicAction.NOTIFY, broken.chronicAction, "откат к NOTIFY");
        assertEquals(PingShieldConfig.StormPolicy.ALERT_ONLY, broken.stormPolicy, "откат к ALERT_ONLY");
    }

    @Test
    @DisplayName("validate() ругается на бессмысленные настройки анти-абуза")
    void suspiciousValidation() {
        PingShieldConfig config = loadFrom("""
                abuse:
                  suspicious:
                    threshold: 99
                    policy: BLOCK
                    points:
                      good-average-ping-ms: 100
                """);
        List<String> warnings = config.validate();

        assertTrue(warnings.stream().anyMatch(w -> w.contains("abuse.suspicious.threshold")),
                "порог выше суммы баллов — недостижимое условие, нужен warning");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("policy = BLOCK")),
                "BLOCK — жёсткая политика, о ней надо предупредить");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("good-average-ping-ms")),
                "порог «хорошего» пинга в 100 мс слишком мал");
    }

    @Test
    @DisplayName("validate() чинит и объясняет границы хронического пинга и шторма")
    void chronicAndStormValidation() {
        PingShieldConfig config = loadFrom("""
                abuse:
                  chronic-ping:
                    bad-ping-seconds: 10
                    action: KICK
                    kick-warning-seconds: 1
                storm:
                  min-players-same-subnet: 1
                  policy: PAUSE_SUBNET
                  hold-seconds: 3
                """);
        List<String> warnings = config.validate();

        assertTrue(warnings.stream().anyMatch(w -> w.contains("abuse.chronic-ping.bad-ping-seconds")),
                "меньше минуты — это не «хронический» пинг");
        assertEquals(5, config.chronicKickWarningSeconds, "предупреждение перед киком минимум 5 с");
        assertEquals(2, config.stormMinPlayersSameSubnet, "шторм минимум из двух игроков");
        assertEquals(10, config.stormHoldSeconds, "пауза шторма минимум 10 с");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("storm.policy = PAUSE_SUBNET")),
                "PAUSE_SUBNET при пороге 2 отключает защиту почти всегда — нужно предупреждение");
    }

    @Test
    @DisplayName("Штатный конфиг: базовые пороги и штраф за шумный канал на месте")
    void shippedThresholds() {
        PingShieldConfig config = new PingShieldConfig();
        config.load(shipped());

        assertEquals(3000, config.enterThresholdMs);
        assertEquals(2000, config.exitThresholdMs);
        assertTrue(config.exitThresholdMs < config.enterThresholdMs,
                "порог выхода обязан быть ниже порога входа, иначе защита будет мигать");
        assertEquals(20, config.checkIntervalTicks);
        assertTrue(config.jitterEnterBonusMs > 0, "шумный канал должен получать надбавку к порогу");
    }
}
