package com.sawwik.pingshield;

import java.util.Locale;

/**
 * Детектор <b>общего сетевого скачка</b>: ситуация, когда пинг вырос сразу у всех игроков
 * (упал магистральный канал, проблемы у хостера, сетевая атака на аплинк).
 *
 * <h2>Зачем это нужно</h2>
 * Индивидуальная защита считает, что «лаг = проблема игрока». При общем скачке это неверно:
 * <ul>
 *   <li>если у всех пинг 60 → 220 ms, замораживать никого не надо — играть всё ещё можно;</li>
 *   <li>если пинг 60 → 4000 ms у всего онлайна, заморозка 500 человек — это 500 задач удержания,
 *       телепортов назад и босс-баров, то есть удар по TPS ровно тогда, когда сервер и так
 *       «на грани» (канал лёг, пакеты копятся, клиенты досылают движение);</li>
 *   <li>счётчик хронического пинга и детект «искусственного лага» не должны копить
 *       чужие секунды: виноват провайдер, а не игрок.</li>
 * </ul>
 *
 * <h2>Как распознаётся (без ложных срабатываний)</h2>
 * <ol>
 *   <li><b>Гистограмма вместо сортировки.</b> Пинг каждого игрока за цикл раскладывается в
 *       корзины по 25 ms (121 корзина на 0…3000 ms + хвост). Медиана и p90 берутся из
 *       гистограммы за O(1) на игрока и O(корзин) на цикл: никаких списков, сортировок и
 *       аллокаций — 500 игроков стоят примерно столько же, сколько 5.</li>
 *   <li><b>Медиана важнее среднего.</b> Один игрок со скачущим каналом (или AFK на мобильном)
 *       сдвигает среднее, но не медиану: скачок «у всех» — это сдвиг медианы, а не хвоста.</li>
 *   <li><b>Четыре условия сразу</b> (все настраиваются): медиана выросла на
 *       {@code median-delta-ms}, поднялась выше {@code min-median-ms}, выросла не меньше чем в
 *       {@code min-ratio} раз, и «выше нормы» стало {@code affected-share} игроков.
 *       Один лагающий — не скачок; скачок — это когда «поднялось у большинства».</li>
 *   <li><b>Подтверждение и гистерезис.</b> Нужно {@code confirm-cycles} циклов подряд, чтобы
 *       признать скачок, и {@code recovery-cycles} спокойных циклов, чтобы его закрыть:
 *       дребезг на границе не приводит к миганию режима.</li>
 *   <li><b>База не «убегает» за скачком.</b> Пока скачок активен, база (медленное EWMA медианы)
 *       не обновляется. Когда скачок закончился, база <i>принимает</i> новый уровень: если
 *       провайдер сменил маршрут и пинг остался выше навсегда — это новая норма, а не скачок,
 *       и защита дальше работает относительно неё.</li>
 * </ol>
 *
 * <p>Класс намеренно не знает ни про Bukkit, ни про игроков: на вход — миллисекунды, на выходе —
 * решение. Поэтому он полностью покрыт юнит-тестами ({@code NetworkMonitorTest}).</p>
 */
final class NetworkMonitor {

    /** Ширина корзины гистограммы, мс. */
    private static final int BUCKET_MS = 25;
    /** Корзин на 0…3000 мс; последняя — «больше 3000». */
    private static final int BUCKET_COUNT = 121;
    private static final int OVERFLOW = BUCKET_COUNT - 1;

    /** Итог цикла: что изменилось — чтобы вызывающий код мог один раз уведомить персонал. */
    enum Transition {
        /** Ничего не изменилось. */
        NONE,
        /** Скачок начался в этом цикле. */
        STARTED,
        /** Скачок закончился в этом цикле. */
        ENDED
    }

    private final int[] histogram = new int[BUCKET_COUNT];
    /** Граница «выше нормы» относительно базы (задаётся из конфига перед обходом). */
    private double spikeDeltaFloor = 100.0D;
    private int validSamples;
    private int affectedSamples;

    /** Медиана и p90 последнего цикла, мс. */
    private double median;
    private double p90;
    /** Медленное EWMA медианы — «нормальный» уровень сети. -1 = ещё нет данных. */
    private double baseline = -1.0D;

    /** Текущий сдвиг медианы относительно базы и доля игроков «выше нормы». */
    private double delta;
    private double share;
    /** Сколько игроков попало в статистику в последнем цикле. */
    private int counted;

    private int calmCycles;
    private int spikeCycles;
    private boolean spiking;
    private long spikeSince;
    private long lastSpikeAt;
    private int spikesTotal;

    /**
     * Начинает новый цикл: чистит гистограмму и запоминает границу «выше нормы».
     * Стоимость — 121 обнуление int, то есть доли микросекунды даже на 500 игроков.
     *
     * @param deltaFloorMs насколько пинг должен быть выше базы, чтобы считаться «поднявшимся»
     */
    void beginCycle(int deltaFloorMs) {
        if (validSamples > 0) {
            java.util.Arrays.fill(histogram, 0);
        }
        validSamples = 0;
        affectedSamples = 0;
        spikeDeltaFloor = Math.max(1, deltaFloorMs);
    }

    /**
     * Один замер. Пингу {@code <= 0} не верим: так Paper отдаёт значение у NPC и у клиентов,
     * чей канал ещё не измерен.
     *
     * @param ping {@code Player#getPing()} в миллисекундах
     */
    void sample(int ping) {
        if (ping <= 0) {
            return;
        }
        int bucket = Math.min(ping / BUCKET_MS, OVERFLOW);
        histogram[bucket]++;
        validSamples++;
        // «Выше нормы» считаем относительно базы: порог для игрока тут не нужен — скачок
        // определяется сдвигом всей картины, а не тем, кто уже перешёл свой порог.
        if (baseline > 0.0D && ping >= baseline + spikeDeltaFloor) {
            affectedSamples++;
        }
    }

    /**
     * Считает медиану/p90 по гистограмме и обновляет состояние скачка.
     *
     * @param now текущее время, мс
     * @param cfg настройки детектора (network-spike.*)
     * @return что изменилось в этом цикле
     */
    Transition evaluate(long now, PingShieldConfig cfg) {
        if (validSamples == 0) {
            // Никого с валидным пингом: статистика недостоверна, состояние не меняем,
            // но если скачок был — считаем это спокойным циклом (сервер опустел).
            counted = 0;
            if (spiking) {
                calmCycles++;
                if (calmCycles >= Math.max(1, cfg.networkSpikeRecoveryCycles)) {
                    closeSpike(now);
                    return Transition.ENDED;
                }
            }
            return Transition.NONE;
        }

        counted = validSamples;
        median = quantile(0.5D);
        p90 = quantile(0.9D);
        share = (double) affectedSamples / (double) validSamples;

        if (baseline < 0.0D) {
            baseline = median;      // первое измерение задаёт базу
            delta = 0.0D;
            calmCycles = 1;
            return Transition.NONE;
        }

        if (spiking) {
            delta = median - baseline;
            // Аномалия затянулась (провайдер сменил маршрут) — принимаем новый уровень
            // как норму: дальше защита оценивает игроков относительно него.
            if (cfg.networkSpikeMaxSeconds > 0
                    && now - spikeSince >= cfg.networkSpikeMaxSeconds * 1000L) {
                closeSpike(now);
                return Transition.ENDED;
            }
            if (delta <= cfg.networkSpikeDeltaMs * 0.5D || median < cfg.networkSpikeMinMedianMs * 0.5D) {
                calmCycles++;
                if (calmCycles >= Math.max(1, cfg.networkSpikeRecoveryCycles)) {
                    closeSpike(now);
                    return Transition.ENDED;
                }
            } else {
                calmCycles = 0;
            }
            return Transition.NONE;
        }

        boolean enoughPlayers = validSamples >= Math.max(2, cfg.networkSpikeMinPlayers);
        boolean bigEnough = median >= cfg.networkSpikeMinMedianMs;
        double ratio = baseline > 0.0D ? median / baseline : 1.0D;
        boolean jumped = median - baseline >= cfg.networkSpikeDeltaMs;
        boolean massAffected = share >= cfg.networkSpikeAffectedShare;

        if (enoughPlayers && bigEnough && jumped && massAffected && ratio >= cfg.networkSpikeMinRatio) {
            spikeCycles++;
            delta = median - baseline;
            if (spikeCycles >= Math.max(1, cfg.networkSpikeConfirmCycles)) {
                spikeCycles = 0;
                calmCycles = 0;
                spiking = true;
                spikeSince = now;
                lastSpikeAt = now;
                spikesTotal++;
                return Transition.STARTED;
            }
            return Transition.NONE;
        }

        spikeCycles = 0;
        delta = median - baseline;
        // Спокойный цикл: база медленно подтягивается к текущей медиане.
        double alpha = clampAlpha((double) Math.max(1, cfg.checkIntervalTicks) / 20.0D
                / Math.max(10, cfg.networkSpikeBaselineSeconds));
        baseline += (median - baseline) * alpha;
        return Transition.NONE;
    }

    private void closeSpike(long now) {
        spiking = false;
        calmCycles = 0;
        spikeCycles = 0;
        lastSpikeAt = now;
        // Принимаем новый уровень как норму: если пинг остался выше из-за смены маршрута,
        // это уже не «скачок», и защита должна оценивать игроков относительно новой нормы.
        baseline = median;
        delta = 0.0D;
    }

    private static double clampAlpha(double alpha) {
        if (alpha <= 0.0D || Double.isNaN(alpha)) {
            return 0.02D;
        }
        return Math.min(0.5D, Math.max(0.005D, alpha));
    }

    /** Квантиль по гистограмме: индекс корзины, где накопилось нужное число замеров. */
    private double quantile(double q) {
        int target = (int) Math.ceil(validSamples * q);
        int cumulative = 0;
        for (int i = 0; i < BUCKET_COUNT; i++) {
            cumulative += histogram[i];
            if (cumulative >= target) {
                if (i == OVERFLOW) {
                    // Хвост: возвращаем оценку «не меньше 3000» — точность там не нужна
                    return 3000.0D;
                }
                return i * (double) BUCKET_MS + BUCKET_MS / 2.0D;
            }
        }
        return 0.0D;
    }

    /** Надбавка к порогу входа, чтобы общий скачок не считался личным лагом. */
    int thresholdBonus(int maxBonusMs) {
        if (!spiking || maxBonusMs <= 0) {
            return 0;
        }
        int bonus = (int) Math.round(delta);
        return Math.max(0, Math.min(maxBonusMs, bonus));
    }

    boolean spiking() {
        return spiking;
    }

    double medianMs() {
        return median;
    }

    double p90Ms() {
        return p90;
    }

    double baselineMs() {
        return baseline;
    }

    double deltaMs() {
        return delta;
    }

    double affectedShare() {
        return share;
    }

    int countedPlayers() {
        return counted;
    }

    int spikesTotal() {
        return spikesTotal;
    }

    long spikeSince() {
        return spikeSince;
    }

    long lastSpikeAt() {
        return lastSpikeAt;
    }

    /** Строка для /pingshield net и консоли: без форматирования цветом, только факты. */
    String describe() {
        return String.format(Locale.ROOT,
                "median=%.0fms p90=%.0fms baseline=%.0fms delta=%+.0fms affected=%.0f%% players=%d state=%s total=%d",
                median, p90, baseline, spiking ? delta : 0.0D, share * 100.0D, counted,
                spiking ? "SPIKE" : "calm", spikesTotal);
    }
}
