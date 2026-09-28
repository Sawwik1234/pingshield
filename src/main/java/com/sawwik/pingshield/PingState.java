package com.sawwik.pingshield;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Состояние пинга одного игрока.
 *
 * <h2>Потоковая модель — важно!</h2>
 * Поля разделены на две группы:
 * <ul>
 *   <li><b>Математика пинга</b> (EWMA, jitter, счётчики серий, активации) — обычные поля без
 *       синхронизации: их читает и пишет <b>только</b> поток глобального цикла проверки.
 *       Это и есть оптимизация: горячий путь без локов и атомиков.</li>
 *   <li><b>{@code volatile}-снимок состояния сущности</b> ({@code protectable}, {@code worldName},
 *       {@code healthPercent}, {@code subnet}, {@code snapshotAt}) — его обновляет поток региона
 *       игрока (там разрешено читать сущность), а читает поток цикла. Так плагин <b>никогда</b>
 *       не трогает сущность из чужого региона — это требование Folia.</li>
 * </ul>
 */
final class PingState {

    private static final int ACTIVATION_WINDOW = 64;

    /** Скорость «базового» (очень медленного) сглаживания: типичный пинг игрока за сессию. */
    private static final double BASELINE_SMOOTHING = 0.02D;

    private final long[] activations = new long[ACTIVATION_WINDOW];
    private int activationHead;
    private int activationSize;

    // ------------------------------------------------------------------ математика (поток цикла)

    /** Сглаженный пинг, -1 = ещё нет данных. */
    double ewma = -1.0D;
    /** Очень медленное сглаживание — «типичный» пинг этой сессии. */
    double baseline = -1.0D;
    /** Сглаженное абсолютное отклонение (jitter). */
    double jitter;
    /** Последний сырой замер. */
    int raw;
    /** Сколько замеров подряд пинг выше порога входа. */
    int aboveCount;
    /** Сколько замеров подряд пинг ниже порога выхода. */
    int belowCount;

    /** Накопленные замеры «плохого» пинга (для политики хронического пинга). */
    int badSamples;
    /** Хронический пинг уже отработан в этом эпизоде? */
    boolean chronicTriggered;
    /** Предупреждение перед киком уже отправлено? */
    boolean chronicWarned;
    /** Пока now < этого времени защита не включается (шторм подсети / блокировка за абуз). */
    long holdUntil;
    /** Принудительный «щит без заморозки» (политика NO_FREEZE или хронический пинг). */
    boolean forcedImmunityOnly;

    /** Время входа на сервер. */
    long joinAt;
    /** До этого времени защита не включается (пауза после снятия/лимита). */
    long cooldownUntil;
    /** Когда последний раз предупреждали об исчерпанном лимите защит. */
    long limitNoticeAt;
    /** Когда игрок попал в опасное место при снятии защиты, -1 = не в опасности. */
    long dangerSince = -1L;
    /** Когда последний раз писали игроку про удержание в опасном месте. */
    long lastDangerNoticeAt;
    /** Когда последний раз уведомляли персонал о подозрительной активации. */
    long suspiciousNoticeAt;

    // Кэш персональных порогов (права перепроверяются редко).
    long thresholdsGeneration = -1L;
    long thresholdsCachedAt;
    int enter = -1;
    int exit = -1;

    // ------------------------------------------------------------------ снимок от потока региона

    /** Протокол клиента (Paper NetworkClient#getProtocolVersion), -1 = неизвестно. */
    volatile int clientProtocol = -1;
    /** Подсеть игрока для детекта шторма: {@code a.b.c.0/24} или {@code ipv6/64}, null = неизвестно. */
    volatile String subnet;
    /** Имя мира (читается в регионе, чтобы цикл не трогал сущность). */
    volatile String worldName = "?";
    /** Защита вообще применима к игроку здесь и сейчас (мир, режим игры, жив, не NPC). */
    volatile boolean protectable = true;

    /**
     * Есть ли у игрока право {@code pingshield.bypass}. Читается в потоке цикла, поэтому
     * заполняется снимком (проверка прав сущности — операция региона, не цикла).
     */
    volatile boolean bypass;
    /** Здоровье в процентах на момент снимка. */
    volatile int healthPercent = 100;
    /** Когда снимок обновлялся (мс). */
    volatile long snapshotAt;
    /** Задача обновления снимка уже стоит в очереди региона. */
    private final AtomicBoolean snapshotQueued = new AtomicBoolean(false);
    /** Задача пересчёта персональных порогов уже стоит в очереди региона. */
    private final AtomicBoolean thresholdsQueued = new AtomicBoolean(false);

    /**
     * Один замер. Стоимость: несколько операций с плавающей точкой — ничего больше.
     *
     * @param ping      сырой пинг из {@code Player#getPing()}
     * @param smoothing вес нового замера (0.1 — плавно, 1.0 — без сглаживания)
     * @param enter     порог входа для этого игрока
     * @param exit      порог выхода для этого игрока
     */
    void sample(int ping, double smoothing, int enter, int exit) {
        this.raw = ping;
        if (ewma < 0.0D) {
            ewma = ping;
            baseline = ping;
            jitter = 0.0D;
        } else {
            double delta = Math.abs(ping - ewma);
            ewma += (ping - ewma) * smoothing;
            jitter += (delta - jitter) * smoothing;
            baseline += (ping - baseline) * BASELINE_SMOOTHING;
        }
        aboveCount = (ping >= enter) ? aboveCount + 1 : 0;
        belowCount = (ping <= exit) ? belowCount + 1 : 0;
    }

    void recordActivation(long now) {
        activations[activationHead] = now;
        activationHead = (activationHead + 1) % ACTIVATION_WINDOW;
        if (activationSize < ACTIVATION_WINDOW) {
            activationSize++;
        }
    }

    int activationsLastHour(long now) {
        if (activationSize == 0) {
            return 0;
        }
        long cutoff = now - 3_600_000L;
        int count = 0;
        for (int i = 0; i < activationSize; i++) {
            if (activations[i] >= cutoff) {
                count++;
            }
        }
        return count;
    }

    void clearActivations() {
        activationHead = 0;
        activationSize = 0;
    }

    /** Игрок «плохо лагает» прямо сейчас (по сырому пингу и порогу этого игрока). */
    boolean isBadPingNow() {
        return enter > 0 && raw >= enter;
    }

    /** Пинг стабильно хороший: серия ниже порога выхода длиннее, чем нужно для сброса счётчиков. */
    boolean isStableGood(int requiredSamples) {
        return belowCount >= requiredSamples;
    }

    /**
     * Отмечает активацию как «плохую» для политики хронического пинга.
     * Затухание 1 за замер: разовый спайк не накапливается, а длительный лаг — да.
     */
    void accumulateBadPing(boolean bad) {
        if (bad) {
            badSamples++;
        } else if (badSamples > 0) {
            badSamples--;
        }
    }

    /** Секунды «плохого» пинга (с учётом затухания) при текущем периоде проверки. */
    int badPingSeconds(int checkIntervalTicks) {
        return (int) ((long) badSamples * Math.max(1, checkIntervalTicks) / 20L);
    }

    void resetChronic() {
        badSamples = 0;
        chronicTriggered = false;
        chronicWarned = false;
    }

    /** Пора ли включать защиту: сглаженный пинг выше порога И серия подтверждений набрана. */
    boolean readyToEnter(int confirmSamples) {
        return aboveCount >= confirmSamples;
    }

    /** Пора ли снимать: пинг держится ниже порога выхода достаточно долго. */
    boolean readyToExit(int confirmSamples) {
        return belowCount >= confirmSamples;
    }

    /** Порог входа с поправкой на шумный канал: скачущий пинг — не повод замораживать. */
    double effectiveEnter(int jitterThreshold, int jitterBonus) {
        return jitter > jitterThreshold ? enter + jitterBonus : enter;
    }

    boolean markSnapshotQueued() {
        return snapshotQueued.compareAndSet(false, true);
    }

    void clearSnapshotQueued() {
        snapshotQueued.set(false);
    }

    /**
     * Пора ли пересчитать персональные пороги. Сам расчёт идёт в регионе игрока
     * (там безопасно читать права и LuckPerms), поэтому в потоке цикла мы только
     * проверяем, что кэш устарел.
     */
    boolean needsThresholdRefresh(long currentGeneration, long now, long cacheMs) {
        return thresholdsGeneration != currentGeneration
                || enter < 0
                || now - thresholdsCachedAt >= cacheMs;
    }

    /** Задача пересчёта порогов уже стоит в очереди региона. */
    boolean markThresholdsQueued() {
        return thresholdsQueued.compareAndSet(false, true);
    }

    void clearThresholdsQueued() {
        thresholdsQueued.set(false);
    }

    @Override
    public String toString() {
        return "PingState{raw=" + raw + ", ewma=" + (long) ewma + ", base=" + (long) baseline
                + ", jitter=" + (long) jitter + ", above=" + aboveCount + ", below=" + belowCount
                + ", bad=" + badSamples + ", subnet=" + subnet + "}";
    }
}
