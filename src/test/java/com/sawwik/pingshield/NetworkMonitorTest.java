package com.sawwik.pingshield;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты детектора общего сетевого скачка.
 *
 * <p>Ключевые сценарии — ровно те, из-за которых такой детектор и нужен:</p>
 * <ul>
 *   <li>пинг вырос у всех (60 → 220 ms): это скачок сети, а не личный лаг 500 человек;</li>
 *   <li>лагает один игрок: это НЕ скачок (медиана не двигается);</li>
 *   <li>провайдер навсегда поднял пинг: через max-spike-seconds это становится новой нормой;</li>
 *   <li>мало игроков онлайн: статистика недостоверна, решения нет.</li>
 * </ul>
 *
 * <p>Помним про квантование гистограммы: корзина 25 ms, медиана возвращается как середина
 * корзины, поэтому 60 ms читается как 62.5 ms, а 220 ms — как 212.5 ms.</p>
 */
class NetworkMonitorTest {

    /** Конфиг с настройками детектора по умолчанию (как в штатном config.yml). */
    private static PingShieldConfig config() {
        PingShieldConfig cfg = new PingShieldConfig();
        cfg.checkIntervalTicks = 20;             // цикл раз в секунду
        cfg.networkSpikeEnabled = true;
        cfg.networkSpikeMinPlayers = 20;
        cfg.networkSpikeBaselineSeconds = 60;
        cfg.networkSpikeDeltaMs = 120;
        cfg.networkSpikeMinMedianMs = 180;
        cfg.networkSpikeMinRatio = 1.4D;
        cfg.networkSpikeAffectedShare = 0.6D;
        cfg.networkSpikeConfirmCycles = 3;
        cfg.networkSpikeRecoveryCycles = 10;
        cfg.networkSpikeMaxSeconds = 900;
        return cfg;
    }

    /** Один цикл: очистка → замеры → подведение итогов. */
    private static NetworkMonitor.Transition cycle(NetworkMonitor monitor, PingShieldConfig cfg,
                                                   long now, int... pings) {
        monitor.beginCycle(cfg.networkSpikeDeltaMs);
        for (int ping : pings) {
            monitor.sample(ping);
        }
        return monitor.evaluate(now, cfg);
    }

    private static int[] uniform(int count, int ping) {
        int[] pings = new int[count];
        Arrays.fill(pings, ping);
        return pings;
    }

    /** Прогревает базу: 40 игроков со стабильным пингом. */
    private static void warmUp(NetworkMonitor monitor, PingShieldConfig cfg, long startAt, int ping) {
        long now = startAt;
        for (int i = 0; i < 10; i++) {
            cycle(monitor, cfg, now += 1000L, uniform(40, ping));
        }
    }

    @Test
    @DisplayName("Скачок у всех: 60 → 220 ms распознан, надбавка к порогу ≈ величина скачка")
    void detectsServerWideJump() {
        PingShieldConfig cfg = config();
        NetworkMonitor monitor = new NetworkMonitor();
        long now = 1_700_000_000_000L;

        warmUp(monitor, cfg, now, 60);
        now += 10_000L;
        assertFalse(monitor.spiking(), "ровный канал — скачка нет");
        assertEquals(62.5D, monitor.baselineMs(), 1.0D, "норма запомнена");

        // Первые два цикла — только подтверждение, скачок ещё не признан
        assertEquals(NetworkMonitor.Transition.NONE, cycle(monitor, cfg, now += 1000L, uniform(40, 220)));
        assertEquals(NetworkMonitor.Transition.NONE, cycle(monitor, cfg, now += 1000L, uniform(40, 220)));
        assertEquals(NetworkMonitor.Transition.STARTED, cycle(monitor, cfg, now += 1000L, uniform(40, 220)));

        assertTrue(monitor.spiking());
        assertEquals(212.5D, monitor.medianMs(), 1.0D);
        assertEquals(1.0D, monitor.affectedShare(), 0.001D, "поднялось у 100 % игроков");
        assertEquals(150, monitor.thresholdBonus(1500), 1,
                "надбавка = 212.5 − 62.5 ≈ 150 ms: порог 3000 временно становится 3150");
        assertEquals(40, monitor.countedPlayers());
    }

    @Test
    @DisplayName("Один лагающий игрок — это НЕ общий скачок (медиана не двигается)")
    void singleLaggerIsNotASpike() {
        PingShieldConfig cfg = config();
        NetworkMonitor monitor = new NetworkMonitor();
        long now = 1_700_000_000_000L;

        warmUp(monitor, cfg, now, 60);
        int[] mixed = uniform(39, 60);
        int[] withLagger = Arrays.copyOf(mixed, 40);
        withLagger[39] = 5000;

        for (int i = 0; i < 10; i++) {
            assertEquals(NetworkMonitor.Transition.NONE, cycle(monitor, cfg, now += 1000L, withLagger));
        }
        assertFalse(monitor.spiking(), "медиана осталась ~60 ms: защита сработает точечно, по этому игроку");
        assertEquals(62.5D, monitor.medianMs(), 1.0D);
        assertEquals(0, monitor.thresholdBonus(1500));
        assertTrue(monitor.affectedShare() <= 0.05D,
                "в статистику попал ровно один игрок из 40 (2.5 процента) - это не подъём у всех");
        assertFalse(monitor.spiking(), "порог affected-share = 60 процентов не достигнут");
    }

    @Test
    @DisplayName("Меньше min-players онлайн — статистика недостоверна, решения нет")
    void notEnoughPlayers() {
        PingShieldConfig cfg = config();
        NetworkMonitor monitor = new NetworkMonitor();
        long now = 1_700_000_000_000L;

        for (int i = 0; i < 10; i++) {
            cycle(monitor, cfg, now += 1000L, uniform(10, 60));
        }
        // Все 10 «поднялись» до 400 ms, но плечо статистики слишком узкое
        for (int i = 0; i < 5; i++) {
            assertEquals(NetworkMonitor.Transition.NONE, cycle(monitor, cfg, now += 1000L, uniform(10, 400)));
        }
        assertFalse(monitor.spiking(), "10 игроков < min-players = 20");
    }

    @Test
    @DisplayName("Сеть вернулась в норму: скачок закрывается после recovery-cycles")
    void recoversAfterNetworkIsBack() {
        PingShieldConfig cfg = config();
        NetworkMonitor monitor = new NetworkMonitor();
        long now = 1_700_000_000_000L;

        warmUp(monitor, cfg, now, 60);
        for (int i = 0; i < 3; i++) {
            cycle(monitor, cfg, now += 1000L, uniform(40, 220));
        }
        assertTrue(monitor.spiking());
        assertTrue(monitor.thresholdBonus(1500) > 0);

        NetworkMonitor.Transition last = NetworkMonitor.Transition.NONE;
        for (int i = 0; i < 10; i++) {
            last = cycle(monitor, cfg, now += 1000L, uniform(40, 60));
        }
        assertEquals(NetworkMonitor.Transition.ENDED, last);
        assertFalse(monitor.spiking());
        assertEquals(0, monitor.thresholdBonus(1500), "надбавка снимается вместе со скачком");
        assertEquals(1, monitor.spikesTotal());
    }

    @Test
    @DisplayName("Затянувшаяся аномалия становится новой нормой (max-spike-seconds)")
    void longAnomalyBecomesNewNormal() {
        PingShieldConfig cfg = config();
        cfg.networkSpikeMaxSeconds = 60;   // для теста — минута вместо 15 минут
        NetworkMonitor monitor = new NetworkMonitor();
        long now = 1_700_000_000_000L;

        warmUp(monitor, cfg, now, 60);
        for (int i = 0; i < 3; i++) {
            cycle(monitor, cfg, now += 1000L, uniform(40, 300));
        }
        assertTrue(monitor.spiking(), "скачок распознан");

        NetworkMonitor.Transition last = NetworkMonitor.Transition.NONE;
        for (int i = 0; i < 70 && monitor.spiking(); i++) {
            last = cycle(monitor, cfg, now += 1000L, uniform(40, 300));
        }
        assertEquals(NetworkMonitor.Transition.ENDED, last, "через минуту уровень принят как норма");
        assertFalse(monitor.spiking());
        assertEquals(312.5D, monitor.baselineMs(), 1.0D, "база приняла новый уровень");
        assertEquals(0, monitor.thresholdBonus(1500),
                "дальше защита работает относительно новой нормы, а не живёт в режиме скачка");
    }

    @Test
    @DisplayName("Потолок надбавки соблюдается (max-threshold-bonus-ms)")
    void bonusIsCapped() {
        PingShieldConfig cfg = config();
        cfg.networkSpikeMaxBonusMs = 100;
        NetworkMonitor monitor = new NetworkMonitor();
        long now = 1_700_000_000_000L;

        warmUp(monitor, cfg, now, 60);
        for (int i = 0; i < 3; i++) {
            cycle(monitor, cfg, now += 1000L, uniform(40, 2500));
        }
        assertTrue(monitor.spiking());
        assertEquals(100, monitor.thresholdBonus(100), "надбавка упирается в потолок");
        assertTrue(monitor.deltaMs() > 100, "фактический сдвиг при этом больше потолка");
    }

    @Test
    @DisplayName("Медленный дрейф пинга (+1 мс/с) скачком не считается: база успевает подтягиваться")
    void slowDriftIsNotASpike() {
        PingShieldConfig cfg = config();
        NetworkMonitor monitor = new NetworkMonitor();
        long now = 1_700_000_000_000L;

        warmUp(monitor, cfg, now, 50);
        // База — EWMA с α = 1/baseline-seconds. При линейном росте r мс/с она отстаёт на r·baseline
        // мс, то есть при r = 1 и окне 60 с отставание ~60 мс — меньше порога 120 мс.
        int ping = 50;
        for (int i = 0; i < 120; i++) {
            ping += 1;
            NetworkMonitor.Transition transition = cycle(monitor, cfg, now += 1000L, uniform(40, ping));
            assertEquals(NetworkMonitor.Transition.NONE, transition,
                    "цикл " + i + ": пинг " + ping + " ms — это дрейф, а не авария");
        }
        assertFalse(monitor.spiking());
    }

    @Test
    @DisplayName("Быстрая деградация канала (+8 мс/с) распознаётся, но реакция — надбавка к порогу, а не заморозка всех")
    void fastRampRaisesThresholdInsteadOfFreezingEveryone() {
        PingShieldConfig cfg = config();
        NetworkMonitor monitor = new NetworkMonitor();
        long now = 1_700_000_000_000L;

        warmUp(monitor, cfg, now, 50);
        int ping = 50;
        boolean started = false;
        for (int i = 0; i < 40 && !started; i++) {
            ping += 8;
            started = cycle(monitor, cfg, now += 1000L, uniform(40, ping))
                    == NetworkMonitor.Transition.STARTED;
        }
        assertTrue(started, "рост вчетверо быстрее порога чувствительности — это авария канала");
        assertTrue(monitor.spiking());
        int bonus = monitor.thresholdBonus(1500);
        assertTrue(bonus >= 100 && bonus <= 1500,
                "итог: порог сдвигается на " + bonus + " ms вместо массовой заморозки 40 игроков");
    }

    @Test
    @DisplayName("Часть игроков лагает, но не большинство — скачком не считается")
    void minorityAffectedIsNotASpike() {
        PingShieldConfig cfg = config();
        NetworkMonitor monitor = new NetworkMonitor();
        long now = 1_700_000_000_000L;

        warmUp(monitor, cfg, now, 60);
        // 25 из 100 игроков поднялись до 400 ms — это четверть, а не «у всех»
        int[] pings = new int[100];
        Arrays.fill(pings, 0, 75, 60);
        Arrays.fill(pings, 75, 100, 400);

        for (int i = 0; i < 5; i++) {
            assertEquals(NetworkMonitor.Transition.NONE, cycle(monitor, cfg, now += 1000L, pings));
        }
        assertFalse(monitor.spiking(), "affected-share = 0.6 не достигнут (25 %)");
    }

    @Test
    @DisplayName("Гистограмма чистится между циклами: старые замеры не влияют на медиану")
    void histogramResetsBetweenCycles() {
        PingShieldConfig cfg = config();
        NetworkMonitor monitor = new NetworkMonitor();
        long now = 1_700_000_000_000L;

        cycle(monitor, cfg, now, uniform(500, 3000));   // «грязный» цикл
        cycle(monitor, cfg, now + 1000L, uniform(40, 60));
        assertEquals(40, monitor.countedPlayers(), "считаются только замеры текущего цикла");
        assertEquals(62.5D, monitor.medianMs(), 1.0D);
    }

    @Test
    @DisplayName("Пиг 0/отрицательный (NPC и клиенты без канала) игнорируются")
    void invalidPingsIgnored() {
        PingShieldConfig cfg = config();
        NetworkMonitor monitor = new NetworkMonitor();
        cycle(monitor, cfg, 1_700_000_000_000L, 0, -1, 60, 60, 0);
        assertEquals(2, monitor.countedPlayers());
        assertEquals(62.5D, monitor.medianMs(), 1.0D);
    }

    @Test
    @DisplayName("describe() даёт строку для /pingshield net без исключений")
    void describeIsSafe() {
        PingShieldConfig cfg = config();
        NetworkMonitor monitor = new NetworkMonitor();
        assertFalse(monitor.describe().isBlank(), "пустой монитор тоже должен описываться");
        warmUp(monitor, cfg, 1_700_000_000_000L, 60);
        assertTrue(monitor.describe().contains("baseline="));
        assertTrue(monitor.describe().contains("state=calm"));
    }
}
