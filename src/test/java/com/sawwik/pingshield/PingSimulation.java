package com.sawwik.pingshield;

import java.util.Random;

/**
 * Симулятор поведения PingShield на «плохих» каналах.
 *
 * <p>Отвечает на вопрос из ревью: «а если у игрока плохой провайдер и пинг скачет всегда?» —
 * цифрами, а не обещаниями. Берётся настоящая математика плагина ({@link PingState}) и
 * сравнивается с наивной реализацией («пинг выше порога → морозим, ниже → отпускаем»).</p>
 *
 * <p>Запуск (после {@code ./gradlew build}):</p>
 * <pre>
 *   java -cp build/classes/java/main tools/PingSimulation.java   // исходник в том же пакете
 * </pre>
 * Счёт идёт по замерам: 1 замер = 1 проверка (по умолчанию раз в секунду), 3600 замеров = 1 час.
 */
public final class PingSimulation {

    private static final int ENTER = 3000;
    private static final int EXIT = 2000;
    private static final double SMOOTHING = 0.3D;
    private static final int ENTER_CONFIRM = 2;
    private static final int EXIT_CONFIRM = 3;
    private static final int MIN_DWELL_SAMPLES = 5;
    private static final int JITTER_THRESHOLD = 400;
    private static final int JITTER_BONUS = 1000;
    private static final int SAMPLES = 3600;

    public static void main(String[] args) {
        System.out.println("=== PingShield: симуляция решений (1 замер = 1 сек, " + SAMPLES + " замеров = 1 час) ===");
        System.out.println("Порог: вход " + ENTER + " ms, выход " + EXIT + " ms, подавление jitter +"
                + JITTER_BONUS + " ms при шуме > " + JITTER_THRESHOLD + " ms\n");

        run("1. Плохой Wi-Fi: база 220 ms, шум и одиночные всплески до 7 s каждые ~30 с",
                providerScenario(new Random(42)));
        run("2. Плохой мобильный канал: база 380 ms, скачки 200-4500 ms",
                mobileScenario(new Random(7)));
        run("3. Реальный обрыв: 200 ms -> 4500 ms на 45 с -> снова 200 ms",
                outageScenario());
        run("4. Хронический лаг: стабильно 3500 ms",
                chronicScenario());
        run("5. Нормальный канал: 40-120 ms",
                healthyScenario(new Random(1)));

        System.out.println("\n=== Стоимость математики на один замер ===");
        PingState state = new PingState();
        int[] pings = {120, 3400, 5000, 90, 250, 4100, 300, 700};
        int iterations = 5_000_000;
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            state.sample(pings[i & 7], SMOOTHING, ENTER, EXIT);
        }
        long elapsed = System.nanoTime() - start;
        System.out.printf("sample(): %.2f нс на вызов (%d вызовов за %.1f ms)%n",
                (double) elapsed / iterations, iterations, elapsed / 1_000_000.0D);
        System.out.println("То есть: 200 игроков раз в секунду = около "
                + String.format("%.1f", 200 * ((double) elapsed / iterations)) + " нс/с на весь сервер.");
    }

    private static void run(String name, int[] pings) {
        Result ours = simulate(pings, true);
        Result naive = simulate(pings, false);
        System.out.println(name);
        System.out.printf("   наивно (по сырому пингу):  заморозок %d, разморозок %d, миганий (короче 10 с) %d, под защитой %.1f%%%n",
                naive.freezes, naive.releases, naive.shortProtections,
                100.0D * naive.protectedSamples / pings.length);
        System.out.printf("   PingShield (сглаживание+подтверждения): заморозок %d, разморозок %d, миганий (короче 10 с) %d, под защитой %.1f%%%n",
                ours.freezes, ours.releases, ours.shortProtections,
                100.0D * ours.protectedSamples / pings.length);
        System.out.println();
    }

    private static Result simulate(int[] pings, boolean smart) {
        PingState state = new PingState();
        Result result = new Result();
        boolean protection = false;
        int protectedSamplesSince = 0;
        int elapsed = 0;

        for (int ping : pings) {
            elapsed++;
            state.sample(ping, SMOOTHING, ENTER, EXIT);

            if (!protection) {
                boolean enter;
                if (smart) {
                    double threshold = state.effectiveEnter(JITTER_THRESHOLD, JITTER_BONUS);
                    enter = state.ewma >= threshold && state.readyToEnter(ENTER_CONFIRM);
                } else {
                    enter = ping >= ENTER;
                }
                if (enter) {
                    protection = true;
                    result.freezes++;
                    protectedSamplesSince = 0;
                }
            } else {
                result.protectedSamples++;
                protectedSamplesSince++;
                boolean release;
                if (smart) {
                    release = state.readyToExit(EXIT_CONFIRM) && protectedSamplesSince >= MIN_DWELL_SAMPLES;
                } else {
                    release = ping < EXIT;
                }
                if (release) {
                    if (protectedSamplesSince < 10) {
                        result.shortProtections++;
                    }
                    protection = false;
                    result.releases++;
                }
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ сценарии

    /**
     * Плохой Wi-Fi/мобильный интернет: база 220 ms, шум ±90 ms и ОДИНОЧНЫЕ всплески
     * до 7 s (1-2 замера) каждые ~30 с. Классическая картина «пинг скачет всегда».
     */
    private static int[] providerScenario(Random random) {
        int[] pings = new int[SAMPLES];
        int nextSpike = 25;
        int spikeLeft = 0;
        int spikePeak = 0;
        for (int i = 0; i < SAMPLES; i++) {
            if (spikeLeft > 0) {
                spikeLeft--;
                pings[i] = spikePeak + random.nextInt(600) - 300;
            } else if (i >= nextSpike) {
                spikePeak = 3200 + random.nextInt(3800);
                spikeLeft = random.nextInt(2); // 1 или 2 замера
                pings[i] = spikePeak;
                nextSpike = i + 25 + random.nextInt(21);
            } else {
                pings[i] = 220 + random.nextInt(180) - 90;
            }
        }
        return pings;
    }

    /** База 380 ms, высокая дисперсия, случайные скачки до 4500 ms. */
    private static int[] mobileScenario(Random random) {
        int[] pings = new int[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            int base = 380 + random.nextInt(400) - 200;
            if (random.nextInt(10) == 0) {
                base = 1500 + random.nextInt(3000);
            }
            pings[i] = base;
        }
        return pings;
    }

    /** Настоящий обрыв: 200 ms, затем ровно 4500 ms на 45 секунд, затем снова 200 ms. */
    private static int[] outageScenario() {
        int[] pings = new int[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            pings[i] = (i > 300 && i < 345) ? 4500 : 200;
        }
        return pings;
    }

    /** Игрок с хронически плохим каналом: всегда 3500 ms. */
    private static int[] chronicScenario() {
        int[] pings = new int[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            pings[i] = 3500;
        }
        return pings;
    }

    /** Нормальный канал: 40-120 ms. */
    private static int[] healthyScenario(Random random) {
        int[] pings = new int[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            pings[i] = 40 + random.nextInt(80);
        }
        return pings;
    }

    private static final class Result {
        int freezes;
        int releases;
        int protectedSamples;
        /** Защиты короче 10 с — именно они и есть «мигание», самое раздражающее игрока. */
        int shortProtections;
    }

    private PingSimulation() {
    }
}
