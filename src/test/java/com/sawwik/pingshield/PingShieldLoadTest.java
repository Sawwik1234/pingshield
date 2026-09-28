package com.sawwik.pingshield;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Нагрузочный стенд: воспроизводит горячий путь цикла PingShield на большом онлайне
 * и показывает, сколько наносекунд он стоит.
 *
 * <p>Запуск: {@code ./gradlew load} (по умолчанию 600 игроков),
 * можно задать своё: {@code ./gradlew load -Pplayers=1500 -Pcycles=120}.</p>
 *
 * <p>Что именно измеряется — те же операции, что в
 * {@link ProtectionManager#tick()} для каждого игрока:</p>
 * <ol>
 *   <li>{@code ConcurrentHashMap.get} состояния (у нас есть UUID игрока);</li>
 *   <li>{@link PingState#sample(int, double, int, int)} — сглаживание, jitter, серии;</li>
 *   <li>{@link PingState#accumulateBadPing(boolean)} — счётчик хронического пинга;</li>
 *   <li>{@link NetworkMonitor#sample(int)} — корзина гистограммы для медианы сети;</li>
 * </ol>
 * <p>Плюс раз за цикл: {@link NetworkMonitor#beginCycle(int)} и
 * {@link NetworkMonitor#evaluate(long, PingShieldConfig)}.</p>
 *
 * <p><b>Чего стенд не измеряет:</b> вызовы Bukkit API ({@code Bukkit.getOnlinePlayers()},
 * {@code Player#getPing()}) и работу с защищёнными игроками (заморозка, индикатор, сообщения) —
 * они выполняются в потоке региона и видны в {@code /pingshield perf} на живом сервере.
 * Здесь измеряется только то, что плагин делает для КАЖДОГО игрока каждый цикл.</p>
 */
public final class PingShieldLoadTest {

    private static final long TICK_NANOS = 50_000_000L; // 50 мс — один тик сервера

    public static void main(String[] args) {
        int players = intProperty("players", 600);
        int cycles = intProperty("cycles", 60);
        PingShieldConfig cfg = new PingShieldConfig();
        cfg.checkIntervalTicks = 20; // цикл раз в секунду

        System.out.printf("Нагрузочный стенд PingShield: %,d игроков, %,d циклов (период — 1 с)%n%n",
                players, cycles);

        Map<UUID, PingState> states = new ConcurrentHashMap<>(Math.max(16, players * 2));
        List<UUID> ids = new ArrayList<>(players);
        List<PingState> ordered = new ArrayList<>(players);
        for (int i = 0; i < players; i++) {
            UUID id = new UUID(0x5A5A5A5AL, i);
            PingState state = new PingState();
            state.enter = 3000;
            state.exit = 2000;
            states.put(id, state);
            ids.add(id);
            ordered.add(state);
        }

        NetworkMonitor monitor = new NetworkMonitor();
        int[] pings = new int[players];
        for (int i = 0; i < players; i++) {
            pings[i] = 40 + (i % 60); // обычный разброс 40…100 ms
        }

        // ---- прогрев (JIT) ----
        for (int i = 0; i < 20; i++) {
            oneCycle(states, ids, ordered, monitor, cfg, pings, 20, false);
        }

        // ---- сценарий 1: нормальная сеть ----
        Result calm = measure(states, ids, ordered, monitor, cfg, pings, cycles);
        print("Обычная сеть (40…100 ms)", calm, players);

        // ---- сценарий 2: общий скачок сети: у всех +180 ms, часть переходит порог ----
        for (int i = 0; i < players; i++) {
            pings[i] = 220 + (i % 80);
        }
        Result spike = measure(states, ids, ordered, monitor, cfg, pings, cycles);
        print("Общий скачок сети (220…300 ms)", spike, players);
        System.out.printf("     детектор скачка: %s%n", monitor.describe());

        // ---- сценарий 3: часть игроков реально лагает (защита включается) ----
        for (int i = 0; i < players; i++) {
            pings[i] = (i % 50 == 0) ? 4200 : 60; // 2 % игроков в «жёстком» лаге
        }
        Result mixed = measure(states, ids, ordered, monitor, cfg, pings, cycles);
        print("2 % игроков в жёстком лаге (4200 ms)", mixed, players);

        // ---- память на состояние ----
        System.out.printf("%nПамять:%n");
        int stateBytes = stateBytes();
        System.out.printf("  состояние игрока %d байт (по layout полей) → %,d игроков ≈ %.0f КБ%n",
                stateBytes, players, stateBytes * (double) players / 1024.0D);
        System.out.printf("  для сравнения: 5000 игроков ≈ %.1f МБ — состояние не зависит от истории%n",
                stateBytes * 5000.0D / (1024 * 1024));
        System.out.printf("  гистограмма сети: int[121] = ~500 байт, одна на весь сервер%n");
        System.out.printf("  замер живой кучи на %,d состояниях: %s%n", 50_000, heapProbe(50_000));

        // ---- вклад в тик ----
        double worstMicros = Math.max(calm.microsPerCycle(),
                Math.max(spike.microsPerCycle(), mixed.microsPerCycle()));
        System.out.printf("%nИтог для %,d игроков:%n", players);
        System.out.printf("  самый тяжёлый сценарий: %.1f мкс на цикл (%.2f %% одного тика из 50 мс)%n",
                worstMicros, worstMicros * 1000.0D / TICK_NANOS * 100.0D);
        System.out.printf("  за минуту работы: %.2f мс суммарно%n", worstMicros * cycles / 1000.0D);
        System.out.printf("  на игрока: %.0f нс за цикл%n", worstMicros * 1000.0D / players);
    }

    // ------------------------------------------------------------------ измеритель

    private record Result(double nanosPerCycle, double nanosPerPlayer) {
        double microsPerCycle() {
            return nanosPerCycle / 1000.0D;
        }
    }

    /**
     * @param forceCandidate доля игроков, для которых дополнительно выполняются проверки
     *                      «кандидата» (как в плагине: права из кэша + список причин)
     */
    private static Result measure(Map<UUID, PingState> states, List<UUID> ids, List<PingState> ordered,
                                  NetworkMonitor monitor, PingShieldConfig cfg, int[] pings,
                                  int cycles) {
        long best = Long.MAX_VALUE;
        for (int round = 0; round < 3; round++) {
            long start = System.nanoTime();
            for (int c = 0; c < cycles; c++) {
                oneCycle(states, ids, ordered, monitor, cfg, pings, c, true);
            }
            long elapsed = System.nanoTime() - start;
            best = Math.min(best, elapsed / cycles);
        }
        return new Result(best, (double) best / Math.max(1, pings.length));
    }

    private static void oneCycle(Map<UUID, PingState> states, List<UUID> ids, List<PingState> ordered,
                                 NetworkMonitor monitor, PingShieldConfig cfg, int[] pings,
                                 long cycle, boolean withCandidates) {
        monitor.beginCycle(cfg.networkSpikeDeltaMs);
        long now = 1_700_000_000_000L + cycle * 1000L;

        for (int i = 0; i < pings.length; i++) {
            UUID id = ids.get(i);
            PingState state = states.get(id);           // как в горячем цикле: один get по UUID
            if (state == null) {
                continue;
            }
            int ping = pings[i];
            state.sample(ping, cfg.smoothing, state.enter, state.exit);
            monitor.sample(ping);
            state.accumulateBadPing(state.isBadPingNow());

            // Кандидаты: в плагине сюда попадают только те, у кого сглаженный пинг у порога
            // (обычно единицы процентов онлайна). Здесь — по флагу, чтобы измерить и этот путь.
            if (withCandidates && i % 64 == 0) {
                List<String> reasons = new ArrayList<>(4);
                if (state.baseline > 0 && state.baseline < 400) {
                    reasons.add("обычный пинг");
                }
                double threshold = state.effectiveEnter(cfg.jitterThresholdMs, cfg.jitterEnterBonusMs);
                if (state.ewma >= threshold) {
                    reasons.add("кандидат");
                }
                if (reasons.isEmpty()) {
                    // пустой список — как в реальном коде: причины собираются только у подозрительных
                }
            }
        }

        monitor.evaluate(now, cfg);
    }

    private static void print(String title, Result result, int players) {
        System.out.printf("%s%n", title);
        System.out.printf("  на цикл: %.1f мкс | на игрока: %.0f нс | за 60 циклов: %.2f мс%n",
                result.microsPerCycle(), result.nanosPerPlayer(),
                result.nanosPerCycle() * 60 / 1_000_000.0D);
        System.out.printf("  доля тика (50 мс): %.3f %% %n",
                result.nanosPerCycle() / TICK_NANOS * 100.0D);
    }

    /**
     * Размер одного {@link PingState} по составу полей: заголовок объекта, 11 int, 5 boolean,
     * 3 double, 11 long, 3 ссылки (сжатые oops = 4 байта), массив {@code long[64]} для окна
     * активаций и {@link java.util.concurrent.atomic.AtomicBoolean}, выравнивание до 8 байт.
     * Считается формулой, а не «на глаз» — цифру можно проверить по исходнику класса.
     */
    private static int stateBytes() {
        int bytes = 16;                       // заголовок объекта
        bytes += 11 * 4 + 5 * 1;              // int + boolean
        bytes += 3 * 8 + 11 * 8;              // double + long
        bytes += 3 * 4;                       // ссылки (compressed oops)
        bytes += 16 + 64 * 8;                 // long[] activations: заголовок + 64 элемента
        bytes += 16;                          // AtomicBoolean
        return (bytes + 7) & ~7;              // выравнивание кучи
    }

    /**
     * Живой контроль: сколько кучи реально занимают N состояний. Замер приблизительный
     * (влияют TLAB и сборщик), поэтому в выводе он подписан как контрольный, а не как истина.
     */
    private static String heapProbe(int count) {
        Runtime runtime = Runtime.getRuntime();
        try {
            PingState[] keep = new PingState[count];
            for (int i = 0; i < count; i++) {
                keep[i] = new PingState();
            }
            System.gc();
            long used = runtime.totalMemory() - runtime.freeMemory();
            long perState = used / count;
            keep = null;
            System.gc();
            return "≈ " + perState + " байт на состояние (с учётом шума GC)";
        } catch (OutOfMemoryError error) {
            return "не удалось замерить (мало памяти у стенда)";
        }
    }

    private static int intProperty(String name, int fallback) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(1, Integer.parseInt(value.trim()));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private PingShieldLoadTest() {
    }
}
