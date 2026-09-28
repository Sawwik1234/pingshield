package com.sawwik.pingshield;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты математики пинга: сглаживание, jitter, гистерезис, память о хроническом лаге
 * и окно активаций. Всё это — «мозг» плагина, и все ошибки здесь выглядят как жалобы
 * «меня заморозило без причины» или «защита не сработала».
 */
class PingStateTest {

    private static final int ENTER = 3000;
    private static final int EXIT = 2000;

    private PingState state() {
        return new PingState();
    }

    @Test
    @DisplayName("Первый замер задаёт ewma, baseline и счётчики серий")
    void firstSampleInitialises() {
        PingState state = state();
        state.sample(120, 0.3D, ENTER, EXIT);

        assertEquals(120, state.raw);
        assertEquals(120.0D, state.ewma, 0.001D);
        assertEquals(120.0D, state.baseline, 0.001D);
        assertEquals(0.0D, state.jitter, 0.001D);
        assertEquals(0, state.aboveCount, "порог входа не достигнут");
        assertEquals(1, state.belowCount, "пинг ниже порога выхода");
        assertFalse(state.isBadPingNow(), "120 ms — не плохой пинг");
    }

    @Test
    @DisplayName("Сглаживание подтягивает ewma к высокому пингу и набирает серию подтверждений")
    void smoothingAndHysteresis() {
        PingState state = state();
        // Персональный порог выставляет код плагина (кэш прав); sample() его только использует.
        state.enter = ENTER;
        state.exit = EXIT;
        state.sample(120, 0.3D, ENTER, EXIT); // соединение стартует в норме
        for (int i = 0; i < 10; i++) {
            state.sample(4000, 0.3D, ENTER, EXIT);
        }

        assertTrue(state.ewma > 3000.0D, "после 10 замеров по 4000 мс ewma должен перейти порог входа");
        assertTrue(state.ewma < 4000.0D,
                "сглаживание не должно прыгать на полный замер: 120 → 10×(0.3) ≈ 3940, а не 4000");
        assertEquals(10, state.aboveCount);
        assertTrue(state.readyToEnter(2), "двух подтверждений достаточно");
        assertTrue(state.isBadPingNow(), "сырой пинг выше порога входа");

        // Пинг вернулся в норму: серия «выше порога» рвётся сразу, серия «ниже выхода» растёт.
        state.sample(300, 0.3D, ENTER, EXIT);
        assertEquals(0, state.aboveCount);
        assertEquals(1, state.belowCount);
        assertFalse(state.readyToExit(3), "одного хорошего замера мало — это защита от мигания канала");
        state.sample(300, 0.3D, ENTER, EXIT);
        state.sample(300, 0.3D, ENTER, EXIT);
        assertTrue(state.readyToExit(3));

        // Пинг между порогами (гистерезис): не «выше входа» и не «ниже выхода».
        state.sample(2500, 0.3D, ENTER, EXIT);
        assertEquals(0, state.aboveCount);
        assertEquals(0, state.belowCount, "2400–3000 мс — серая зона, обе серии сбрасываются");
    }

    @Test
    @DisplayName("Jitter повышает порог входа: у скачущего канала защита включается позже")
    void jitterRaisesEffectiveThreshold() {
        PingState calm = state();
        PingState noisy = state();
        calm.enter = ENTER;
        noisy.enter = ENTER;
        for (int i = 0; i < 20; i++) {
            calm.sample(3200, 0.3D, ENTER, EXIT);
            noisy.sample(i % 2 == 0 ? 100 : 6000, 0.3D, ENTER, EXIT);
        }

        assertTrue(calm.jitter < 400.0D, "ровный канал: jitter маленький");
        assertTrue(noisy.jitter > 400.0D, "скачущий канал: jitter большой");
        assertEquals(ENTER, calm.effectiveEnter(400, 1000), "для ровного канала порог не меняется");
        assertEquals(ENTER + 1000, noisy.effectiveEnter(400, 1000),
                "для скачущего канала порог поднимается на jitter-бонус");
    }

    @Test
    @DisplayName("Разовый спайк не портит «типичный пинг» (baseline медленный)")
    void baselineSurvivesSingleSpike() {
        PingState state = state();
        for (int i = 0; i < 50; i++) {
            state.sample(100, 0.3D, ENTER, EXIT);
        }
        state.sample(5000, 0.3D, ENTER, EXIT);

        assertEquals(5000, state.raw);
        assertTrue(state.baseline < 400.0D,
                "после одного спайка baseline должен остаться ниже 400 мс — иначе детект абуза "
                        + "начнёт считать честного игрока «всегда лагающим»");
    }

    @Test
    @DisplayName("Хронический пинг: накопление, затухание и сброс")
    void chronicAccumulator() {
        PingState state = state();
        state.enter = ENTER;

        for (int i = 0; i < 600; i++) {
            state.accumulateBadPing(true);
        }
        assertEquals(600, state.badSamples);
        assertEquals(600, state.badPingSeconds(20), "период 20 тиков = 1 с на замер");

        for (int i = 0; i < 100; i++) {
            state.accumulateBadPing(false);
        }
        assertEquals(500, state.badSamples, "одиночный хороший замер гасит счётчик на единицу");
        assertEquals(250, state.badPingSeconds(10), "период 10 тиков = 0.5 с на замер");

        state.chronicTriggered = true;
        state.chronicWarned = true;
        state.resetChronic();
        assertEquals(0, state.badSamples);
        assertFalse(state.chronicTriggered);
        assertFalse(state.chronicWarned);
    }

    @Test
    @DisplayName("Счётчик плохих секунд не уходит в минус и учитывает минимальный период")
    void chronicAccumulatorFloor() {
        PingState state = state();
        state.accumulateBadPing(false);
        state.accumulateBadPing(false);
        assertEquals(0, state.badSamples);
        state.badSamples = 7;
        assertEquals(0, state.badPingSeconds(0), "период 0 подменяется минимальным: 7 * 1 / 20 = 0");
        assertEquals(7, state.badPingSeconds(20));
    }

    @Test
    @DisplayName("isStableGood срабатывает только после нужной серии хороших замеров")
    void stableGoodNeedsSeries() {
        PingState state = state();
        for (int i = 0; i < 5; i++) {
            state.sample(200, 0.3D, ENTER, EXIT);
        }
        assertFalse(state.isStableGood(60), "5 замеров < 60");
        assertTrue(state.isStableGood(5));
        state.sample(3500, 0.3D, ENTER, EXIT);
        assertFalse(state.isStableGood(1), "серия хороших замеров прервана");
    }

    @Test
    @DisplayName("Окно активаций: за час, с ограничением 64 записи")
    void activationWindow() {
        PingState state = state();
        long now = 1_700_000_000_000L;
        assertEquals(0, state.activationsLastHour(now));

        for (int i = 0; i < 3; i++) {
            state.recordActivation(now - i * 1000L);
        }
        assertEquals(3, state.activationsLastHour(now));

        state.recordActivation(now - 3_600_001L);
        assertEquals(3, state.activationsLastHour(now), "запись старше часа не считается");

        for (int i = 0; i < 70; i++) {
            state.recordActivation(now);
        }
        assertEquals(64, state.activationsLastHour(now), "окно фиксированного размера, без роста памяти");

        state.clearActivations();
        assertEquals(0, state.activationsLastHour(now));
    }

    @Test
    @DisplayName("Снимок состояния запрашивается ровно один раз до ответа региона")
    void snapshotQueuedIsSingleFlight() {
        PingState state = state();
        assertTrue(state.markSnapshotQueued());
        assertFalse(state.markSnapshotQueued(), "повторный запрос, пока прошлый не выполнен, не нужен");
        state.clearSnapshotQueued();
        assertTrue(state.markSnapshotQueued());
        state.clearSnapshotQueued();
    }

    @Test
    @DisplayName("Пороги по умолчанию: неизвестный пинг не считается плохим")
    void unknownStateIsNotBad() {
        PingState state = state();
        assertEquals(-1, state.enter);
        assertFalse(state.isBadPingNow(), "пока персональный порог не выставлен, игрока не трогаем");
        assertFalse(state.readyToEnter(1));
    }
}
