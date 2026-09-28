package com.sawwik.pingshield;

import com.sawwik.pingshield.integration.CoreProtectHook;
import com.sawwik.pingshield.integration.LuckPermsHook;
import com.sawwik.pingshield.integration.ViaHook;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Ядро PingShield: измерение пинга, заморозка и снятие защиты.
 *
 * <h2>Производительность (главное)</h2>
 * <ul>
 *   <li><b>Один проход по онлайну за проверку.</b> На каждого игрока: {@code getPing()} (чтение int),
 *       арифметика EWMA/jitter и один {@code CHM.get}. Никаких списков, стримов и аллокаций.</li>
 *   <li><b>Состояние — без синхронизации.</b> {@link PingState} принадлежит единственному потоку
 *       (глобальному циклу), поэтому там нет ни {@code volatile}, ни локов, ни {@code Atomic}.</li>
 *   <li><b>Тяжёлые действия только по событию.</b> Заморозка, телепорт, индикатор, сообщения
 *       выполняются при старте/снятии защиты и затем раз в {@code perf.enforce-interval-ticks}.</li>
 *   <li><b>Права кэшируются</b> на игрока (пересчёт раз в 30 с или после reload) — пермишены
 *       LuckPerms не дёргаются каждый тик.</li>
 *   <li><b>Стоимость измеряется вживую</b>: {@code /pingshield perf} показывает среднее/пиковое
 *       время цикла и синтетический замер математики на одного игрока.</li>
 * </ul>
 *
 * <h2>Плохой провайдер (скачущий пинг)</h2>
 * <ul>
 *   <li>решение принимается по <b>сглаженному</b> пингу (EWMA), а не по мгновенному замеру;</li>
 *   <li>вход и выход требуют <b>серии подтверждений</b> ({@code enter/exit-confirm-samples});</li>
 *   <li>действует <b>минимальное время удержания</b> ({@code min-protect-seconds}) — заморозка
 *       не «мигает»;</li>
 *   <li>при большом <b>jitter</b> порог входа поднимается, чтобы не морозить за обычные скачки;</li>
 *   <li>после лимита активаций срабатывает {@code abuse.chronic-policy} (в т.ч. «щит без заморозки»).</li>
 * </ul>
 *
 * <h2>Folia</h2>
 * Цикл работает в глобальном регионе, все действия над игроком — в его регионе через
 * {@code EntityScheduler} ({@link #runOn}). Прямых обращений к чужому региону нет.
 */
public final class ProtectionManager {

    /** Причина снятия защиты. */
    public enum EndReason {
        PING_OK, TIMEOUT, MANUAL, QUIT, DISABLED, DEATH, GAMEMODE,
        /** Снята из-за «пингового шторма» подсети (storm.policy = PAUSE_SUBNET). */
        STORM
    }

    private static final String BYPASS_PERMISSION = "pingshield.bypass";
    private static final String NOTIFY_PERMISSION = "pingshield.notify";
    private static final String NPC_METADATA = "NPC";
    /** Насколько «старый» снимок состояния сущности ещё считается валидным. */
    private static final long SNAPSHOT_MAX_AGE_MS = 6_000L;

    private static final long THRESHOLD_CACHE_MS = 30_000L;

    private final PingShieldPlugin plugin;
    private final PingShieldConfig cfg;
    private final AuditLog audit;
    private final CoreProtectHook coreProtect;
    private final ViaHook via;
    private final LuckPermsHook luckPerms;

    /** Защищённые игроки (читают команды и слушатели из регионов). */
    private final Map<UUID, Protection> active = new ConcurrentHashMap<>();
    /** Состояние пинга: пишет/читает только поток цикла проверки. */
    private final Map<UUID, PingState> states = new ConcurrentHashMap<>();

    /** Данные, к которым обращаются регионы из событий — поэтому отдельные конкурентные карты. */
    private final Map<UUID, Long> fallGraceUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Long> selfTeleportUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Long> combatUntil = new ConcurrentHashMap<>();
    /** До какого времени у игрока учитывается «недавний урон» (детект искусственного лага). */
    private final Map<UUID, Long> recentDamageUntil = new ConcurrentHashMap<>();

    private ScheduledTask tickTask;
    private volatile long configGeneration;
    private volatile boolean lagSuspended;
    private int healthyCycles;
    private int pruneCounter;

    /** null = ещё не определяли, true = игроки заходят через прокси (Velocity/Bungee). */
    private volatile Boolean proxyDetected;
    /** Когда последний раз уведомляли о пинговом шторме. */
    private long stormNoticeAt;

    // --- метрики стоимости (для /pingshield perf) ---
    private volatile long lastTickNanos;
    private volatile double avgTickNanos;
    private volatile long maxTickNanos;
    private volatile int lastScanned;
    private volatile long totalTicks;
    private volatile int releaseChecks; // сколько плановых снятий защиты проверено

    public ProtectionManager(PingShieldPlugin plugin, PingShieldConfig cfg) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.audit = new AuditLog(plugin, cfg.auditEnabled, cfg.auditFileName);
        this.coreProtect = new CoreProtectHook(plugin, cfg);
        this.via = new ViaHook(plugin, cfg);
        this.luckPerms = new LuckPermsHook(plugin, cfg);
    }

    /** Инициализация интеграций (CoreProtect, ViaVersion) — вызывается плагином при включении. */
    public void initIntegrations() {
        if (cfg.cpEnabled) {
            coreProtect.init();
        }
        via.init();
        luckPerms.init();
    }

    public LuckPermsHook luckPerms() {
        return luckPerms;
    }

    public CoreProtectHook coreProtect() {
        return coreProtect;
    }

    public ViaHook via() {
        return via;
    }

    // ================================================================== жизненный цикл

    public void start() {
        stopTask();
        long period = Math.max(1, cfg.checkIntervalTicks);
        tickTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> tick(), 20L, period);
    }

    public void stop() {
        stopTask();
        releaseAll(EndReason.DISABLED);
    }

    public void restart() {
        configGeneration++;
        stopTask();
        start();
    }

    private void stopTask() {
        if (tickTask != null) {
            try {
                tickTask.cancel();
            } catch (Throwable ignored) {
                // задача уже завершена
            }
            tickTask = null;
        }
    }

    // ================================================================== горячий цикл

    private void tick() {
        long start = System.nanoTime();
        try {
            if (!cfg.enabled || !autoProtectionAllowed()) {
                if (!active.isEmpty()) {
                    releaseAll(EndReason.DISABLED);
                }
                return;
            }

            Collection<? extends Player> online = Bukkit.getOnlinePlayers();
            if (online.isEmpty()) {
                states.clear();
                active.clear();
                return;
            }

            long now = System.currentTimeMillis();
            long generation = configGeneration;
            int scanned = 0;
            int highPing = 0;
            int globalEnter = cfg.enterThresholdMs;

            // --- единственный проход: замер, накопление «плохого» пинга, решения по защищённым ---
            for (Player player : online) {
                UUID id = player.getUniqueId();
                PingState state = states.get(id);
                if (state == null) {
                    state = new PingState();
                    state.joinAt = now;
                    states.put(id, state);
                }

                int[] thresholds = thresholdsFor(player, state, now, generation);
                int ping = pingOf(player);
                state.sample(ping, cfg.smoothing, thresholds[0], thresholds[1]);
                state.accumulateBadPing(state.isBadPingNow());
                scanned++;
                if (ping >= globalEnter) {
                    highPing++;
                }

                Protection protection = active.get(id);
                if (protection != null && !protection.isReleased()) {
                    tickProtected(player, state, protection, now);
                } else if (protection != null) {
                    active.remove(id, protection);
                }
            }

            lastScanned = scanned;
            boolean serverLagging = evaluateServerLag(online.size(), highPing, now);
            Set<String> stormSubnets = evaluateStorm(online, now);

            // --- новые кандидаты + политики анти-абуза ---
            if (!serverLagging) {
                for (Player player : online) {
                    UUID id = player.getUniqueId();
                    Protection protection = active.get(id);
                    if (protection != null && !protection.isReleased()) {
                        continue;
                    }
                    PingState state = states.get(id);
                    if (state == null) {
                        continue;
                    }
                    // Состояние сущности читаем только в регионе игрока (требование Folia)
                    ensureSnapshot(player, state, now);

                    if (applyStormPolicy(player, state, stormSubnets, now)) {
                        continue;
                    }
                    if (handleChronicPing(player, state, now)) {
                        continue;
                    }
                    if (!shouldProtect(player, state, now)) {
                        continue;
                    }
                    List<String> reasons = new ArrayList<>(4);
                    int score = suspiciousScore(player, state, now, reasons);
                    if (cfg.suspiciousEnabled && score >= cfg.suspiciousThreshold
                            && !applySuspiciousPolicy(player, state, score, reasons, now)) {
                        continue;
                    }
                    beginProtection(player, state, Protection.Reason.AUTO_PING, now);
                }
            }

            if (++pruneCounter >= 20) {
                pruneCounter = 0;
                pruneTransient(online, now);
            }
        } catch (Throwable throwable) {
            // Упавшая repeating-задача в Paper/Folia больше не перезапустится — глушим всё
            plugin.getLogger().log(Level.SEVERE, "Ошибка в цикле проверки PingShield — продолжаю работу", throwable);
        } finally {
            long elapsed = System.nanoTime() - start;
            lastTickNanos = elapsed;
            if (elapsed > maxTickNanos) {
                maxTickNanos = elapsed;
            }
            avgTickNanos = avgTickNanos <= 0.0D ? elapsed : avgTickNanos * 0.9D + elapsed * 0.1D;
            totalTicks++;
        }
    }

    // ================================================================== снимок состояния (Folia)

    /**
     * Обновляет снимок состояния сущности — но только в регионе игрока и не чаще, чем нужно.
     * Вызывается для кандидатов (у кого сглаженный пинг подошёл к порогу), поэтому стоимость
     * ограничена: обычные игроки вообще не попадают сюда.
     */
    private void ensureSnapshot(Player player, PingState state, long now) {
        double threshold = state.effectiveEnter(cfg.jitterThresholdMs,
                cfg.jitterEnterBonusMs + via.jitterBonus(state.clientProtocol));
        if (state.ewma < threshold - 500.0D || now - state.snapshotAt < 5_000L) {
            return;
        }
        if (!state.markSnapshotQueued()) {
            return;
        }
        runOn(player, () -> {
            try {
                refreshSnapshot(player, state);
            } finally {
                state.clearSnapshotQueued();
            }
        });
    }

    /** Выполняется в регионе игрока: читает сущность и публикует данные для потока цикла. */
    private void refreshSnapshot(Player player, PingState state) {
        state.snapshotAt = System.currentTimeMillis();
        state.worldName = player.getWorld().getName();
        try {
            double max = player.getMaxHealth();
            state.healthPercent = max <= 0.0D
                    ? 100
                    : (int) Math.round(player.getHealth() / max * 100.0D);
        } catch (Throwable throwable) {
            state.healthPercent = 100;
        }
        state.protectable = computeProtectable(player);
        state.bypass = hasBypass(player);
        if (state.clientProtocol <= 0) {
            state.clientProtocol = via.protocolOf(player);
        }
        if (state.subnet == null) {
            state.subnet = subnetOf(player);
        }
    }

    /** Выполняется в регионе игрока: применима ли защита к нему здесь и сейчас. */
    private boolean computeProtectable(Player player) {
        if (player.isDead() || player.getHealth() <= 0.0D) {
            return false;
        }
        if (isNpc(player)) {
            return false;
        }
        if (!cfg.isWorldProtected(player.getWorld().getName())) {
            return false;
        }
        GameMode mode = player.getGameMode();
        if (mode == GameMode.SPECTATOR && !cfg.affectSpectator) {
            return false;
        }
        return mode != GameMode.CREATIVE || cfg.affectCreative;
    }

    /** Подсеть игрока: IPv4 → /24, IPv6 → /64. Используется для детекта пингового шторма. */
    private String subnetOf(Player player) {
        try {
            if (player.getAddress() == null) {
                return null;
            }
            InetAddress address = player.getAddress().getAddress();
            if (address == null) {
                return null;
            }
            byte[] bytes = address.getAddress();
            if (bytes.length == 4) {
                return (bytes[0] & 0xFF) + "." + (bytes[1] & 0xFF) + "." + (bytes[2] & 0xFF) + ".0/24";
            }
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < 8; i += 2) {
                if (i > 0) {
                    builder.append(':');
                }
                builder.append(Integer.toHexString(((bytes[i] & 0xFF) << 8) | (bytes[i + 1] & 0xFF)));
            }
            return builder.append("::/64").toString();
        } catch (Throwable throwable) {
            return null;
        }
    }

    // ================================================================== пинговый шторм

    /**
     * Ищет «пинговый шторм»: несколько игроков из одной подсети лагают одновременно.
     * Это признак проблемы у провайдера (или сетевой атаки), а не лага отдельного игрока.
     *
     * @return подсети, в которых сейчас шторм
     */
    private Set<String> evaluateStorm(Collection<? extends Player> online, long now) {
        if (!cfg.stormEnabled || online.size() < cfg.stormMinPlayersSameSubnet) {
            return Set.of();
        }
        Map<String, Integer> highBySubnet = new HashMap<>();
        for (Player player : online) {
            PingState state = states.get(player.getUniqueId());
            if (state == null || state.subnet == null || cfg.stormIgnoreSubnets.contains(state.subnet)) {
                continue;
            }
            int enter = state.enter > 0 ? state.enter : cfg.enterThresholdMs;
            if (state.raw >= enter) {
                highBySubnet.merge(state.subnet, 1, Integer::sum);
            }
        }
        if (highBySubnet.isEmpty()) {
            return Set.of();
        }
        Set<String> storming = new HashSet<>();
        for (Map.Entry<String, Integer> entry : highBySubnet.entrySet()) {
            if (entry.getValue() >= cfg.stormMinPlayersSameSubnet) {
                storming.add(entry.getKey());
            }
        }
        if (!storming.isEmpty()) {
            notifyStorm(storming, highBySubnet, now);
        }
        return storming;
    }

    private void notifyStorm(Set<String> storming, Map<String, Integer> counts, long now) {
        if (now - stormNoticeAt < cfg.stormAlertCooldownSeconds * 1000L) {
            return;
        }
        stormNoticeAt = now;
        StringBuilder details = new StringBuilder();
        for (String subnet : storming) {
            if (details.length() > 0) {
                details.append(", ");
            }
            details.append(subnet).append(" (").append(counts.getOrDefault(subnet, 0)).append(")");
        }
        audit.write("-", "PING_STORM", "подсети: " + details + " | политика=" + cfg.stormPolicy);
        if (cfg.msgStormAlert.isEmpty()) {
            return;
        }
        broadcastStaff(cfg.prefix + cfg.msgStormAlert,
                "subnets", details.toString(),
                "count", storming.size(),
                "policy", cfg.stormPolicy.name());
    }

    /**
     * Реакция на шторм. По умолчанию (ALERT_ONLY) защита продолжает работать: лагающие игроки
     * действительно нуждаются в защите, а шторм — повод сообщить админам.
     *
     * @return true, если защиту для этого игрока сейчас включать не нужно
     */
    private boolean applyStormPolicy(Player player, PingState state, Set<String> stormSubnets, long now) {
        if (stormSubnets.isEmpty() || cfg.stormPolicy == PingShieldConfig.StormPolicy.ALERT_ONLY) {
            return false;
        }
        boolean affected = cfg.stormPolicy == PingShieldConfig.StormPolicy.PAUSE_ALL
                || (state.subnet != null && stormSubnets.contains(state.subnet));
        if (!affected) {
            return false;
        }
        state.holdUntil = now + cfg.stormHoldSeconds * 1000L;
        Protection protection = active.get(player.getUniqueId());
        if (protection != null && !protection.isReleased()) {
            // снимаем защиту только когда мы действительно в регионе игрока (end() это гарантирует)
            end(player, protection, EndReason.STORM);
        }
        return true;
    }

    // ================================================================== хронический пинг

    /**
     * Политика хронического пинга: суммарно накопленные секунды плохого соединения.
     * Счётчик затухает (разовый спайк не накапливается), сбрасывается после длинного
     * периода хорошего пинга.
     *
     * @return true, если защиту в этом цикле включать не нужно
     */
    private boolean handleChronicPing(Player player, PingState state, long now) {
        if (!cfg.chronicPingEnabled) {
            return false;
        }
        int interval = Math.max(1, cfg.checkIntervalTicks);
        int resetSamples = Math.max(1, cfg.chronicResetAfterGoodSeconds * 20 / interval);

        if (state.isStableGood(resetSamples)) {
            if (state.badSamples > 0 || state.chronicTriggered) {
                // снимаем «щит без заморозки» только если его навязала политика хронического пинга:
                // метку от детекта абуза сбрасывает лишь выход игрока
                boolean chronicShield = cfg.chronicAction == PingShieldConfig.ChronicAction.IMMUNITY_ONLY;
                state.resetChronic();
                if (chronicShield) {
                    state.forcedImmunityOnly = false;
                }
                audit.write(player.getName(), "CHRONIC_PING_RESET", "соединение стабилизировалось");
            }
            return false;
        }

        int badSeconds = state.badPingSeconds(interval);
        if (badSeconds < cfg.chronicPingSeconds) {
            if (cfg.chronicAction == PingShieldConfig.ChronicAction.KICK
                    && badSeconds >= cfg.chronicPingSeconds - cfg.chronicKickWarningSeconds
                    && !state.chronicWarned) {
                state.chronicWarned = true;
                int left = Math.max(1, cfg.chronicPingSeconds - badSeconds);
                runOn(player, () -> Msg.send(player, cfg.prefix + cfg.msgChronicKickWarning,
                        "left", left, "seconds", badSeconds));
                audit.write(player.getName(), "CHRONIC_PING_WARNING",
                        "осталось ~" + left + " с плохого пинга до отключения");
            }
            return false;
        }

        if (state.chronicTriggered) {
            return state.forcedImmunityOnly;
        }
        state.chronicTriggered = true;
        audit.write(player.getName(), "CHRONIC_PING",
                "плохой пинг " + badSeconds + " с за сессию, действие=" + cfg.chronicAction);

        switch (cfg.chronicAction) {
            case NONE -> {
                return false;
            }
            case NOTIFY -> {
                runOn(player, () -> Msg.send(player, cfg.prefix + cfg.msgChronicNotify,
                        "seconds", badSeconds, "total", cfg.chronicPingSeconds));
                return false;
            }
            case IMMUNITY_ONLY -> {
                state.forcedImmunityOnly = true;
                runOn(player, () -> Msg.send(player, cfg.prefix + cfg.msgChronicNotify,
                        "seconds", badSeconds, "total", cfg.chronicPingSeconds));
                return true;
            }
            case KICK -> {
                state.holdUntil = now + 5_000L;
                runOn(player, () -> {
                    coreProtect.logMarker(player, CoreProtectHook.Kind.END,
                            "отключён за хронический пинг: " + badSeconds + " с");
                    player.kick(Msg.render(cfg.prefix + cfg.msgChronicKick,
                            "seconds", badSeconds, "total", cfg.chronicPingSeconds));
                });
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    // ================================================================== детект искусственного лага

    /** Признаки «выпрошенного» лага. Считается только в потоке цикла. */
    private int suspiciousScore(Player player, PingState state, long now, List<String> reasons) {
        int score = 0;
        if (cfg.suspiciousInCombatPoints > 0 && isInCombat(player, now)) {
            score += cfg.suspiciousInCombatPoints;
            reasons.add("активация в бою");
        }
        Long damageUntil = recentDamageUntil.get(player.getUniqueId());
        if (cfg.suspiciousRecentDamagePoints > 0 && damageUntil != null && now < damageUntil) {
            score += cfg.suspiciousRecentDamagePoints;
            long ago = Math.max(0L, cfg.suspiciousRecentDamageSeconds - (damageUntil - now) / 1000L);
            reasons.add("урон " + ago + " с назад");
        }
        if (cfg.suspiciousLowHealthPoints > 0 && state.healthPercent <= cfg.suspiciousLowHealthPercent) {
            score += cfg.suspiciousLowHealthPoints;
            reasons.add("здоровье " + state.healthPercent + "%");
        }
        if (cfg.suspiciousGoodPingPoints > 0 && state.baseline > 0.0D
                && state.baseline < cfg.suspiciousGoodPingMs) {
            score += cfg.suspiciousGoodPingPoints;
            reasons.add("обычный пинг " + (long) state.baseline + " ms");
        }
        if (cfg.suspiciousFrequentPoints > 0 && cfg.abuseMaxActivationsPerHour > 0) {
            int activations = state.activationsLastHour(now);
            if (activations >= Math.max(1, cfg.abuseMaxActivationsPerHour / 2)) {
                score += cfg.suspiciousFrequentPoints;
                reasons.add("часто активируется (" + activations + " за час)");
            }
        }
        return score;
    }

    /**
     * Применяет политику к подозрительной активации.
     *
     * @return true, если защиту всё же можно включить
     */
    private boolean applySuspiciousPolicy(Player player, PingState state, int score,
                                          List<String> reasons, long now) {
        String details = "баллы=" + score + " (порог " + cfg.suspiciousThreshold + "), признаки: "
                + String.join(", ", reasons);
        audit.write(player.getName(), "ANTI_ABUSE_SUSPICIOUS", details + " | политика=" + cfg.suspiciousPolicy);

        boolean notify = now - state.suspiciousNoticeAt >= cfg.suspiciousAlertCooldownSeconds * 1000L;
        switch (cfg.suspiciousPolicy) {
            case LOG -> {
                return true;
            }
            case NOTIFY -> {
                if (notify) {
                    state.suspiciousNoticeAt = now;
                    broadcastStaff(cfg.prefix + cfg.msgSuspiciousAlert,
                            "player", player.getName(), "score", score,
                            "details", String.join(", ", reasons), "ping", state.raw);
                }
                return true;
            }
            case NO_FREEZE -> {
                state.forcedImmunityOnly = true;
                if (notify) {
                    state.suspiciousNoticeAt = now;
                    broadcastStaff(cfg.prefix + cfg.msgSuspiciousAlert,
                            "player", player.getName(), "score", score,
                            "details", String.join(", ", reasons), "ping", state.raw);
                }
                return true;
            }
            case BLOCK -> {
                state.holdUntil = now + cfg.suspiciousBlockCooldownSeconds * 1000L;
                if (notify) {
                    state.suspiciousNoticeAt = now;
                    broadcastStaff(cfg.prefix + cfg.msgSuspiciousAlert,
                            "player", player.getName(), "score", score,
                            "details", String.join(", ", reasons), "ping", state.raw);
                }
                return false;
            }
            default -> {
                return true;
            }
        }
    }

    /** Отмечает «недавний урон» игрока. Вызывается из слушателя (регион игрока). */
    public void markDamage(Player player) {
        if (cfg.suspiciousRecentDamagePoints <= 0 || player == null) {
            return;
        }
        recentDamageUntil.put(player.getUniqueId(),
                System.currentTimeMillis() + cfg.suspiciousRecentDamageSeconds * 1000L);
    }

    /** Выполняется в потоке цикла: решает, снимать ли защиту, и планирует действия. */
    private void tickProtected(Player player, PingState state, Protection protection, long now) {
        protection.setLastPing(state.raw);

        if (!protection.isManual()) {
            long elapsedMs = now - protection.getStartedAt();
            boolean minDwellPassed = elapsedMs >= cfg.minProtectSeconds * 1000L;
            boolean pingOk = state.readyToExit(cfg.exitConfirmSamples) && state.raw > 0;
            boolean timedOut = cfg.maxProtectionSeconds > 0 && elapsedMs >= cfg.maxProtectionSeconds * 1000L;

            if (timedOut || (pingOk && minDwellPassed)) {
                EndReason reason = timedOut ? EndReason.TIMEOUT : EndReason.PING_OK;
                releaseChecks++;
                // Решение подтверждается в регионе игрока: надо проверить, не в лаве ли он
                runOn(player, () -> {
                    if (protection.isReleased()) {
                        return;
                    }
                    if (!protection.isImmunityOnly() && holdInDanger(player, protection, now)) {
                        return; // пинг в норме, но отпускать сейчас — верная смерть
                    }
                    end(player, protection, reason);
                });
                return;
            }
        }

        long enforceIntervalMs = Math.max(1, cfg.enforceIntervalTicks) * 50L;
        if (now - protection.getLastEnforcedAt() >= enforceIntervalMs && protection.markEnforceQueued()) {
            runOn(player, () -> {
                try {
                    enforce(player, protection, System.currentTimeMillis());
                } finally {
                    protection.clearEnforceQueued();
                }
            });
        }
    }

    /** Выполняется в регионе игрока: заморозка (если нужно), возврат на якорь, индикатор. */
    private void enforce(Player player, Protection protection, long now) {
        if (!player.isOnline() || protection.isReleased()) {
            return;
        }
        protection.setLastEnforcedAt(now);

        Location anchor = protection.getAnchor();
        if (anchor == null) {
            anchor = player.getLocation().clone();
            protection.setAnchor(anchor);
        }

        if (!protection.isImmunityOnly()) {
            applyFreeze(player, protection);
            if (cfg.teleportBack && !isInAnchor(player.getLocation(), anchor)
                    && now - protection.getLastTeleportBackAt() >= 400L) {
                protection.setLastTeleportBackAt(now);
                teleportBack(player, anchor, protection);
            }
        }

        updateIndicator(player, protection, now);
    }

    // ================================================================== пороги и условия

    /**
     * Персональные пороги (права) с кэшем: {@code hasPermission} не должен вызываться каждый тик,
     * а тем более через LuckPerms.
     */
    private int[] thresholdsFor(Player player, PingState state, long now, long generation) {
        boolean stale = state.thresholdsGeneration != generation
                || state.enter < 0
                || now - state.thresholdsCachedAt >= THRESHOLD_CACHE_MS;
        if (stale) {
            int enter = cfg.enterThresholdMs;

            // 1. LuckPerms: meta pingshield-threshold → право pingshield.threshold.<мс>
            //    (значения считаются с учётом групп и контекстов LuckPerms)
            LuckPermsHook.Resolution resolution = luckPerms.resolve(player);
            if (resolution.threshold() != null) {
                enter = resolution.threshold();
            } else if (!cfg.thresholdOverrides.isEmpty()) {
                // 2. Права из конфига (thresholds.overrides) — как раньше
                for (Map.Entry<String, Integer> entry : cfg.thresholdOverrides.entrySet()) {
                    Integer value = entry.getValue();
                    if (value == null || value < 100) {
                        continue;
                    }
                    if (hasPermission(player, entry.getKey())) {
                        enter = value;
                        break;
                    }
                }
            }
            // Клиенты, чьи пакеты транслирует Via (например, 1.21.4 на ядре 26.1.2), дают
            // повышенный джиттер — порог для них поднимается, чтобы не морозить за обычный скачок.
            enter += via.thresholdBonus(state.clientProtocol);
            state.enter = enter;
            state.exit = Math.min(cfg.exitThresholdMs, Math.max(50, enter - 250));
            state.thresholdsCachedAt = now;
            state.thresholdsGeneration = generation;
        }
        return new int[]{state.enter, state.exit};
    }

    /**
     * Персональные пороги игрока для команд. Читает кэш состояния (обычное чтение int —
     * безопасно), а если состояния ещё нет, считает по конфигу и правам.
     */
    public int[] effectiveThresholds(Player player) {
        PingState state = states.get(player.getUniqueId());
        if (state != null && state.enter > 0) {
            return new int[]{state.enter, state.exit};
        }
        int enter = cfg.enterThresholdMs;
        if (!cfg.thresholdOverrides.isEmpty()) {
            for (Map.Entry<String, Integer> entry : cfg.thresholdOverrides.entrySet()) {
                Integer value = entry.getValue();
                if (value != null && value >= 100 && hasPermission(player, entry.getKey())) {
                    enter = value;
                    break;
                }
            }
        }
        return new int[]{enter, Math.min(cfg.exitThresholdMs, Math.max(50, enter - 250))};
    }

    private boolean shouldProtect(Player player, PingState state, long now) {
        double threshold = state.effectiveEnter(
                cfg.jitterThresholdMs,
                cfg.jitterEnterBonusMs + via.jitterBonus(state.clientProtocol));
        if (state.ewma < threshold) {
            return false;
        }
        if (!state.readyToEnter(cfg.enterConfirmSamples)) {
            return false;
        }
        if (now - state.joinAt < cfg.joinGraceSeconds * 1000L) {
            return false;
        }
        if (now < state.cooldownUntil || now < state.holdUntil) {
            return false;
        }
        // Снимок делается в регионе игрока: в потоке цикла сущность не читаем (требование Folia).
        // Первый цикл после приближения к порогу уходит на запрос снимка — решение на следующем.
        if (state.snapshotAt <= 0L || now - state.snapshotAt > SNAPSHOT_MAX_AGE_MS) {
            ensureSnapshot(player, state, now);
            return false;
        }
        if (!state.protectable || !cfg.isWorldProtected(state.worldName)) {
            return false;
        }
        if (state.bypass) {
            return false;
        }
        // Античит-режим для PvP: в бою защита не включается вообще
        if (cfg.combatPreventProtection && isInCombat(player, now)) {
            return false;
        }
        // Хронически лагающий игрок: политика из abuse.chronic-policy
        if (cfg.abuseMaxActivationsPerHour > 0
                && state.activationsLastHour(now) >= cfg.abuseMaxActivationsPerHour
                && cfg.chronicPolicy != PingShieldConfig.ChronicPolicy.PROTECT) {
            notifyLimitReached(player, state, now);
            return cfg.chronicPolicy == PingShieldConfig.ChronicPolicy.IMMUNITY_ONLY;
        }
        return true;
    }

    // ================================================================== вход / выход

    public boolean beginProtection(Player player, Protection.Reason reason, long now) {
        if (player == null || !player.isOnline()) {
            return false;
        }
        Protection existing = active.get(player.getUniqueId());
        if (existing != null && !existing.isReleased()) {
            return false;
        }
        PingState state = states.get(player.getUniqueId());
        return beginProtection(player, state, reason, now);
    }

    private boolean beginProtection(Player player, PingState state, Protection.Reason reason, long now) {
        PingState safeState = state;
        if (safeState == null) {
            // Вызов из команды: состояние создаём руками (поток записи — регион игрока,
            // но объект ещё никому не виден, поэтому гонки нет).
            PingState fresh = new PingState();
            fresh.joinAt = now;
            states.put(player.getUniqueId(), fresh);
            safeState = fresh;
        }

        final PingState stateRef = safeState; // для lambda (эффективно финальная ссылка)
        int enter = stateRef.enter > 0 ? stateRef.enter : cfg.enterThresholdMs;
        Protection protection = new Protection(player.getUniqueId(), player.getName(), reason, null, now, enter);
        protection.setLastPing(pingOf(player));

        // Персональный режим заморозки из LuckPerms (например, immunity_only для донатов с плохим каналом)
        LuckPermsHook.Resolution resolution = luckPerms.resolve(player);
        if (resolution.mode() == LuckPermsHook.ModeOverride.IMMUNITY_ONLY) {
            protection.setImmunityOnly(true);
        } else if (resolution.mode() != LuckPermsHook.ModeOverride.DEFAULT) {
            protection.setFreezeModeOverride(toFreezeMode(resolution.mode()));
        }

        // Политики анти-абуза (chronic-ping=immunity-only, suspicious=no-freeze):
        // защита включается, но без заморозки — преимущества в бою такое «лекарство» не даёт
        if (stateRef.forcedImmunityOnly) {
            protection.setImmunityOnly(true);
        }

        if (reason == Protection.Reason.AUTO_PING) {
            stateRef.recordActivation(now);
            if (cfg.abuseMaxActivationsPerHour > 0
                    && stateRef.activationsLastHour(now) > cfg.abuseMaxActivationsPerHour
                    && cfg.chronicPolicy == PingShieldConfig.ChronicPolicy.IMMUNITY_ONLY) {
                protection.setImmunityOnly(true);
            }
        }

        active.put(player.getUniqueId(), protection);
        stateRef.cooldownUntil = 0L;
        stateRef.dangerSince = -1L;

        runOn(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (!protection.isImmunityOnly()) {
                protection.captureSnapshot(player);
                applyFreeze(player, protection);
            }
            protection.setAnchor(player.getLocation().clone());
            sendProtectStart(player, protection);
            coreProtect.logMarker(player,
                    protection.isImmunityOnly() ? CoreProtectHook.Kind.IMMUNITY : CoreProtectHook.Kind.START,
                    "защита от лагов ВКЛ: пинг " + protection.getLastPing()
                            + " ms (порог " + protection.getThresholdUsed() + " ms)"
                            + (protection.isImmunityOnly() ? ", режим: щит без заморозки" : ""));
            coreProtect.logInteraction(player.getName(), player.getLocation());
            audit.write(player.getName(),
                    reason == Protection.Reason.MANUAL ? "MANUAL_PROTECT"
                            : (protection.isImmunityOnly() ? "PROTECT_START_IMMUNITY_ONLY" : "PROTECT_START"),
                    "ping=" + protection.getLastPing() + "ms ewma=" + (long) stateRef.ewma
                            + "ms jitter=" + (long) stateRef.jitter
                            + "ms threshold=" + protection.getThresholdUsed()
                            + "ms (" + luckPerms.resolve(player).describeThreshold() + ")"
                            + " client=" + via.describe(stateRef.clientProtocol)
                            + " world=" + player.getWorld().getName());
        });
        return true;
    }

    /** Снимает защиту. Безопасно из любого потока: действия выполняются в регионе игрока. */
    public void end(Player player, Protection protection, EndReason reason) {
        if (!active.remove(protection.getUuid(), protection)) {
            return;
        }
        protection.markReleased();

        long now = System.currentTimeMillis();
        long cooldownMs = switch (reason) {
            case TIMEOUT -> cfg.timeoutCooldownSeconds * 1000L;
            case PING_OK, MANUAL, DEATH, GAMEMODE -> cfg.abuseCooldownAfterEndSeconds * 1000L;
            default -> 0L;
        };
        if (cooldownMs > 0L) {
            PingState state = states.get(protection.getUuid());
            if (state != null) {
                state.cooldownUntil = now + cooldownMs;
                state.aboveCount = 0;   // пороги должны подтвердиться заново
                state.belowCount = 0;
            }
            fallGraceUntil.put(protection.getUuid(), now + cfg.fallDamageGraceMs);
        }

        Player target = (player != null && player.isOnline()) ? player : Bukkit.getPlayer(protection.getUuid());
        if (target != null && target.isOnline()) {
            runOn(target, () -> {
                releaseFreeze(target, protection);
                sendProtectEnd(target, protection, reason);
            });
        }
        audit.write(protection.getName(), "PROTECT_END",
                "reason=" + reason + " elapsed=" + protection.getElapsedSeconds() + "s peakPing="
                        + protection.getPeakPing() + "ms teleportsBack=" + protection.getTeleportBackCount());
        if (target != null && target.isOnline()) {
            runOn(target, () -> {
                coreProtect.logMarker(target, CoreProtectHook.Kind.END, "защита от лагов ВЫКЛ: " + reason
                        + ", длительность " + protection.getElapsedSeconds() + " с");
                coreProtect.logInteraction(target.getName(), target.getLocation());
            });
        }
        // Проверяем, не «обнесли» ли игрока, пока он стоял замороженным
        checkSurroundings(protection);
    }

    /**
     * Читает логи CoreProtect вокруг «якоря» за время защиты и, если блоки трогали чужие игроки,
     * пишет это в audit.log и уведомляет персонал. Классический гриф по лагающим: пока игрок
     * висел, под ним копают или его застраивают.
     */
    private void checkSurroundings(Protection protection) {
        if (!cfg.cpEnabled || !cfg.cpCheckOnRelease || !coreProtect.isAvailable()) {
            return;
        }
        Location anchor = protection.getAnchor();
        if (anchor == null) {
            return;
        }
        int seconds = (int) Math.min(Integer.MAX_VALUE, Math.max(30L, protection.getElapsedSeconds() + 30L));
        String selfName = protection.getName();
        coreProtect.scanAsync(anchor, cfg.cpRadius, seconds, result -> {
            List<String> foreign = coreProtect.foreignUsers(result, selfName);
            if (foreign.isEmpty()) {
                audit.write(selfName, "CORE_PROTECT_CHECK",
                        "изменений вокруг от других игроков нет (" + coreProtect.summaryText(result, selfName) + ")");
                return;
            }
            String details = coreProtect.describeForeign(result, selfName);
            int minutes = Math.max(1, seconds / 60 + 1);
            String hint = coreProtect.lookupHint(foreign.get(0), minutes, cfg.cpRadius);
            audit.write(selfName, "CORE_PROTECT_FOREIGN_CHANGES",
                    "пока игрок был защищён, вокруг меняли блоки: " + details
                            + " | проверка: " + hint + " | " + coreProtect.summaryText(result, selfName));
            // Маркер в логе CoreProtect: теперь это событие ищется и через /co lookup
            Player protectedPlayer = Bukkit.getPlayer(protection.getUuid());
            if (protectedPlayer != null && protectedPlayer.isOnline()) {
                runOn(protectedPlayer, () -> coreProtect.logMarker(protectedPlayer, CoreProtectHook.Kind.CP,
                        "рядом меняли блоки: " + details));
            }

            if (!cfg.cpAlertForeign || cfg.msgCpForeignChanges.isEmpty()) {
                return;
            }
            String message = cfg.prefix + cfg.msgCpForeignChanges;
            // Кликабельная кнопка: сразу открывает нативный поиск CoreProtect
            String lookupCommand = foreign.isEmpty()
                    ? coreProtect.blockSearch(selfName, minutes, cfg.cpRadius)
                    : coreProtect.blockSearch(foreign.get(0), minutes, cfg.cpRadius);
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (!hasNotifyPermission(online)) {
                    continue;
                }
                Player staff = online;
                runOn(staff, () -> {
                    Msg.send(staff, message,
                            "player", selfName,
                            "users", details,
                            "hint", hint,
                            "total", result.total());
                    if (!cfg.btnBlocks.isEmpty()) {
                        staff.sendMessage(Msg.button(cfg.btnBlocks, lookupCommand, cfg.btnHint,
                                "user", foreign.isEmpty() ? selfName : foreign.get(0),
                                "minutes", minutes));
                    }
                });
            }
        });
    }

    public boolean endManually(Player player) {
        Protection protection = get(player);
        if (protection == null) {
            return false;
        }
        end(player, protection, EndReason.MANUAL);
        return true;
    }

    public void releaseAll(EndReason reason) {
        for (Protection protection : new ArrayList<>(active.values())) {
            try {
                end(Bukkit.getPlayer(protection.getUuid()), protection, reason);
            } catch (Throwable throwable) {
                plugin.getLogger().log(Level.FINE,
                        "Не удалось корректно снять защиту с " + protection.getName(), throwable);
            }
        }
        active.clear();
    }

    /**
     * Удержание в опасном месте: пинг в норме, но игрок в лаве/огне/пустоте.
     * Отпустить сейчас — смерть без шанса среагировать. Держим до
     * {@code release.safety.extend-in-danger-max-seconds}, затем всё равно отпускаем.
     */
    private boolean holdInDanger(Player player, Protection protection, long now) {
        if (!cfg.extendInDanger || cfg.dangerBlocks.isEmpty()) {
            return false;
        }
        if (!isInDanger(player)) {
            protection.setDangerSince(-1L);
            return false;
        }
        long since = protection.getDangerSince();
        if (since < 0L) {
            since = now;
            protection.setDangerSince(since);
            audit.write(player.getName(), "DANGER_HOLD", "опасное место в момент снятия защиты");
        }
        long budgetMs = cfg.extendInDangerMaxSeconds * 1000L;
        if (now - since < budgetMs) {
            if (now - protection.getLastDangerNoticeAt() > 2500L) {
                protection.setLastDangerNoticeAt(now);
                long left = Math.max(0L, (budgetMs - (now - since)) / 1000L);
                Msg.send(player, cfg.prefix + cfg.msgProtectDangerHold, "left", left);
                coreProtect.logMarker(player, CoreProtectHook.Kind.HOLD,
                        "удержание: опасное место, осталось " + left + " с");
            }
            applyReleaseEffects(player);
            return true;
        }
        return false;
    }

    // ================================================================== заморозка

    /**
     * Выполняется в регионе игрока. Замораживает БЕЗ смены режима игры и без креатива:
     * неуязвимость даётся флагом сущности, удержание — отменой движения + возвратом на якорь.
     */
    private void applyFreeze(Player player, Protection protection) {
        if (!protection.isSnapshotTaken()) {
            protection.captureSnapshot(player);
        }

        // Настоящее «бессмертие» (не GM1, не креатив): флаг сущности блокирует урон на уровне
        // сервера для любого источника, кроме обходящих неуязвимость (пустота, /kill) — их
        // список задаётся в damage.incoming.exceptions.
        if (cfg.invulnerableFlag && !player.isInvulnerable()) {
            try {
                player.setInvulnerable(true);
            } catch (Throwable ignored) {
                // не критично: остаётся отмена событий урона
            }
        }

        if (cfg.noCollision) {
            try {
                player.setCollidable(false); // чтобы мобы/игроки не выталкивали с места
            } catch (Throwable ignored) {
                // метод может отсутствовать на нестандартных форках
            }
        }

        // Пока игрок «висит», высота падения не должна накапливаться
        if (player.getFallDistance() > 0.0F) {
            player.setFallDistance(0.0F);
        }
        if (cfg.extinguishOnFreeze && player.getFireTicks() > 0) {
            player.setFireTicks(0);
        }

        if (player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        switch (protection.effectiveFreezeMode(cfg.freezeMode)) {
            case PASSIVE -> {
                // Никакого полёта и никакого GM1: остаёмся в своём режиме игры.
                stopMovementInput(player);
                if (cfg.ejectFromVehicles && player.isInsideVehicle()) {
                    player.leaveVehicle();
                }
                if (player.isGliding()) {
                    player.setGliding(false);
                }
            }
            case TELEPORT -> {
                // Самый «тихий» режим: ничего не меняем, кроме отмены движения и якорей.
                if (cfg.ejectFromVehicles && player.isInsideVehicle()) {
                    player.leaveVehicle();
                }
                if (player.isGliding()) {
                    player.setGliding(false);
                }
            }
            case FLY -> {
                // Единственный режим, где используется полёт (и только он может выглядеть как GM1).
                if (cfg.ejectFromVehicles && player.isInsideVehicle()) {
                    player.leaveVehicle();
                }
                if (player.isGliding()) {
                    player.setGliding(false);
                }
                if (Math.abs(player.getWalkSpeed()) > 0.0001F) {
                    player.setWalkSpeed(0.0F);
                }
                if (Math.abs(player.getFlySpeed()) > 0.0001F) {
                    player.setFlySpeed(0.0F);
                }
                // Порядок важен: включить полёт без права полёта нельзя (IllegalArgumentException)
                if (!player.getAllowFlight()) {
                    player.setAllowFlight(true);
                }
                if (!player.isFlying()) {
                    player.setFlying(true);
                }
            }
        }

        if (cfg.potionLock && cfg.freezeMode != PingShieldConfig.FreezeMode.TELEPORT) {
            // Дополнительный слой фиксации: если другой плагин сбросит walk-speed, Slowness 250 держит.
            applyEffect(player, protection, PotionEffectType.SLOWNESS, 60, 250);
        }
    }

    private PingShieldConfig.FreezeMode toFreezeMode(LuckPermsHook.ModeOverride override) {
        return switch (override) {
            case PASSIVE -> PingShieldConfig.FreezeMode.PASSIVE;
            case TELEPORT -> PingShieldConfig.FreezeMode.TELEPORT;
            case FLY -> PingShieldConfig.FreezeMode.FLY;
            default -> cfg.freezeMode;
        };
    }

    /** Обнуляет «ввод движения» игрока. Полёт не трогаем — только скорости. */
    private void stopMovementInput(Player player) {
        if (Math.abs(player.getWalkSpeed()) > 0.0001F) {
            player.setWalkSpeed(0.0F); // исходные значения уже сохранены в снимке
        }
        if (player.isFlying() && Math.abs(player.getFlySpeed()) > 0.0001F) {
            player.setFlySpeed(0.0F);
        }
    }

    private void applyEffect(Player player, Protection protection, PotionEffectType type,
                             int durationTicks, int amplifier) {
        if (type == null) {
            return;
        }
        try {
            PotionEffect current = player.getPotionEffect(type);
            boolean ours = current != null && current.getAmplifier() >= amplifier;
            if (!ours) {
                protection.getAppliedEffects().add(type); // снимаем только то, что поставили сами
            }
            player.addPotionEffect(new PotionEffect(type, durationTicks, amplifier, true, false, false));
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.FINE, "Не удалось применить эффект " + type, throwable);
        }
    }

    /** Выполняется в регионе игрока: возврат ровно в то состояние, что было до защиты. */
    private void releaseFreeze(Player player, Protection protection) {
        try {
            if (!protection.isImmunityOnly()) {
                if (cfg.invulnerableFlag) {
                    player.setInvulnerable(protection.hadInvulnerable());
                }
                PingShieldConfig.FreezeMode mode = protection.effectiveFreezeMode(cfg.freezeMode);
                if (player.getGameMode() != GameMode.SPECTATOR) {
                    if (mode == PingShieldConfig.FreezeMode.FLY) {
                        if (protection.hadAllowFlight() && protection.hadFlying()) {
                            player.setAllowFlight(true);
                            player.setFlying(true);
                        } else {
                            player.setFlying(false);
                            player.setAllowFlight(protection.hadAllowFlight());
                        }
                    }
                    if (mode != PingShieldConfig.FreezeMode.TELEPORT) {
                        player.setFlySpeed(protection.getPrevFlySpeed());
                        player.setWalkSpeed(protection.getPrevWalkSpeed());
                    }
                }
                if (cfg.noCollision) {
                    player.setCollidable(protection.hadCollidable());
                }
                // Справедливость: за время заморозки игрок не должен «съесть» весь запас еды
                if (cfg.restoreFood) {
                    player.setFoodLevel(protection.hadFoodLevel());
                    player.setSaturation(protection.hadSaturation());
                }
            }
            for (PotionEffectType type : new ArrayList<>(protection.getAppliedEffects())) {
                player.removePotionEffect(type);
            }
            protection.getAppliedEffects().clear();

            hideIndicator(player, protection);
            applyReleaseEffects(player);
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.WARNING, "Не удалось корректно разморозить " + player.getName()
                    + " — проверьте конфликты с другими плагинами", throwable);
        }
    }

    /**
     * Эффекты «мягкой посадки»: сразу после разморозки игрок может оказаться в воздухе,
     * в огне или под водой. Fire Resistance и Slow Falling дают шанс среагировать.
     */
    private void applyReleaseEffects(Player player) {
        if (cfg.releaseEffects.isEmpty() || cfg.releaseEffectsDurationTicks <= 0) {
            return;
        }
        for (PotionEffectType type : cfg.releaseEffects) {
            try {
                player.addPotionEffect(new PotionEffect(type, cfg.releaseEffectsDurationTicks, 0, true, false, false));
            } catch (Throwable ignored) {
                // эффект недоступен — не критично
            }
        }
    }

    // ================================================================== возврат на якорь

    private boolean isInAnchor(Location current, Location anchor) {
        if (anchor == null || anchor.getWorld() == null) {
            return true;
        }
        if (current.getWorld() != anchor.getWorld()) {
            return false;
        }
        double dx = current.getX() - anchor.getX();
        double dz = current.getZ() - anchor.getZ();
        double dy = cfg.freezeAllowVertical ? 0.0D : current.getY() - anchor.getY();
        double tolerance = cfg.teleportBackTolerance;
        return (dx * dx + dy * dy + dz * dz) <= tolerance * tolerance;
    }

    /** Выполняется в регионе игрока. */
    private void teleportBack(Player player, Location anchor, Protection protection) {
        Location target = anchor.clone();
        if (cfg.freezeAllowVertical) {
            target.setY(player.getLocation().getY());
        }
        markSelfTeleport(player.getUniqueId());
        try {
            player.teleportAsync(target);
            protection.incrementTeleportBackCount();
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.FINE, "teleportAsync не удался для " + player.getName(), throwable);
        }
    }

    // ================================================================== индикатор

    private void updateIndicator(Player player, Protection protection, long now) {
        PingShieldConfig.Indicator indicator = cfg.indicator;

        if (indicator == PingShieldConfig.Indicator.ACTIONBAR || indicator == PingShieldConfig.Indicator.BOTH) {
            if (now - protection.getLastActionbarAt() >= cfg.actionbarIntervalSeconds * 1000L) {
                protection.setLastActionbarAt(now);
                Msg.actionBar(player, cfg.msgProtectActionbar,
                        "player", player.getName(),
                        "ping", protection.getLastPing(),
                        "elapsed", protection.getElapsedSeconds(),
                        "enter", protection.getThresholdUsed(),
                        "max", cfg.maxProtectionSeconds,
                        "world", player.getWorld().getName());
            }
        }

        if (indicator == PingShieldConfig.Indicator.BOSS_BAR || indicator == PingShieldConfig.Indicator.BOTH) {
            if (now - protection.getLastBossBarUpdateAt() >= 500L) {
                protection.setLastBossBarUpdateAt(now);
                BossBar bar = protection.getBossBar();
                net.kyori.adventure.text.Component name = Msg.render(cfg.msgBossBar,
                        "player", player.getName(),
                        "ping", protection.getLastPing(),
                        "elapsed", protection.getElapsedSeconds(),
                        "enter", protection.getThresholdUsed(),
                        "max", cfg.maxProtectionSeconds);
                if (bar == null) {
                    bar = BossBar.bossBar(name, progress(protection), BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
                    protection.setBossBar(bar);
                    player.showBossBar(bar);
                } else {
                    bar.name(name);
                    bar.progress(progress(protection));
                }
            }
        }
    }

    private float progress(Protection protection) {
        if (cfg.maxProtectionSeconds <= 0) {
            return 1.0F;
        }
        float used = (float) protection.getElapsedSeconds() / (float) cfg.maxProtectionSeconds;
        return Math.max(0.0F, Math.min(1.0F, 1.0F - used));
    }

    private void hideIndicator(Player player, Protection protection) {
        BossBar bar = protection.getBossBar();
        if (bar != null) {
            try {
                player.hideBossBar(bar);
            } catch (Throwable ignored) {
                // полоса могла не отображаться
            }
            protection.setBossBar(null);
        }
    }

    // ================================================================== опасные места

    /** Выполняется в регионе игрока. */
    public boolean isInDanger(Player player) {
        try {
            Location feet = player.getLocation();
            if (feet.getY() <= feet.getWorld().getMinHeight() + 2.0D) {
                return true; // падение в пустоту
            }
            if (cfg.dangerBlocks.contains(feet.getBlock().getType())
                    || cfg.dangerBlocks.contains(player.getEyeLocation().getBlock().getType())) {
                return true;
            }
            if (player.getFireTicks() > 0) {
                return true;
            }
            if (player.getRemainingAir() < player.getMaximumAir() * 0.5F
                    && player.getEyeLocation().getBlock().isLiquid()) {
                return true; // кончается воздух под водой
            }
            return player.getFallDistance() > 8.0F;
        } catch (Throwable throwable) {
            return false;
        }
    }

    // ================================================================== бой и лимиты

    public void tagCombat(Player player) {
        if (cfg.combatTagSeconds <= 0) {
            return;
        }
        combatUntil.put(player.getUniqueId(), System.currentTimeMillis() + cfg.combatTagSeconds * 1000L);
    }

    public boolean isInCombat(Player player, long now) {
        Long until = combatUntil.get(player.getUniqueId());
        return until != null && now < until;
    }

    public boolean isInCombat(Player player) {
        return isInCombat(player, System.currentTimeMillis());
    }

    private void notifyLimitReached(Player player, PingState state, long now) {
        if (cfg.msgProtectLimit.isEmpty() || now - state.limitNoticeAt < 10_000L) {
            return; // не спамим
        }
        state.limitNoticeAt = now;
        String key = cfg.chronicPolicy == PingShieldConfig.ChronicPolicy.IMMUNITY_ONLY
                ? "IMMUNITY_ONLY"
                : "LIMITED";
        // Сообщение отправляем в регион игрока: глобальный поток не имеет права
        // обращаться к игроку напрямую (Folia).
        runOn(player, () -> Msg.send(player, cfg.prefix + cfg.msgProtectLimit,
                "limit", cfg.abuseMaxActivationsPerHour,
                "policy", key,
                "player", player.getName(),
                "ping", pingOf(player)));
    }

    // ================================================================== прокси

    /**
     * За прокси (Velocity/Bungee) {@code getPing()} измеряет не путь до игрока, а путь
     * до прокси. Поэтому автозащита по умолчанию отключается, если замечен локальный вход.
     */
    public boolean autoProtectionAllowed() {
        if (cfg.proxyMode == PingShieldConfig.ProxyMode.DISABLE) {
            return false;
        }
        if (cfg.proxyMode == PingShieldConfig.ProxyMode.AUTO) {
            return proxyDetected == null || !proxyDetected;
        }
        return true;
    }

    public boolean proxyDetected() {
        return proxyDetected != null && proxyDetected;
    }

    private void detectProxy(Player player) {
        if (proxyDetected != null) {
            return;
        }
        InetAddress address = null;
        try {
            if (player.getAddress() != null) {
                address = player.getAddress().getAddress();
            }
        } catch (Throwable ignored) {
            // адрес может быть недоступен
        }
        if (address == null) {
            return;
        }
        boolean loopback = address.isLoopbackAddress() || address.isAnyLocalAddress();
        proxyDetected = loopback;
        if (loopback) {
            plugin.getLogger().warning("PingShield: игрок " + player.getName() + " пришёл с локального адреса "
                    + address.getHostAddress() + " — похоже, сервер за прокси (Velocity/Bungee). getPing() "
                    + "показывает пинг до прокси, а не до игрока, поэтому автозащита отключена. "
                    + "Режим: proxy.mode: AUTO|ENABLE|DISABLE.");
        } else if (address.isSiteLocalAddress() || address.isLinkLocalAddress()) {
            plugin.getLogger().info("PingShield: игрок " + player.getName() + " подключён из локальной сети ("
                    + address.getHostAddress() + ") — для домашних серверов это нормально, защита работает.");
        }
    }

    // ================================================================== серверный лаг

    private boolean evaluateServerLag(int onlineCount, int highPingCount, long now) {
        if (!cfg.lagGuardEnabled) {
            lagSuspended = false;
            return false;
        }

        boolean lagging = false;
        double tps = tps();
        if (cfg.lagGuardMinTps > 0.0D && tps > 0.0D && tps < cfg.lagGuardMinTps) {
            lagging = true;
        }
        if (!lagging && onlineCount >= cfg.lagGuardMinPlayers && onlineCount > 0) {
            lagging = ((double) highPingCount / (double) onlineCount) >= cfg.lagGuardRatio;
        }

        if (lagging) {
            healthyCycles = 0;
            setLagSuspended(true);
            return true;
        }
        if (lagSuspended) {
            healthyCycles++;
            if (healthyCycles < cfg.lagGuardResumeCycles) {
                return true;
            }
        }
        setLagSuspended(false);
        return false;
    }

    @SuppressWarnings("deprecation")
    private double tps() {
        try {
            double[] tps = Bukkit.getServer().getTPS();
            return (tps != null && tps.length > 0) ? tps[0] : 20.0D;
        } catch (Throwable throwable) {
            return 20.0D;
        }
    }

    private void setLagSuspended(boolean value) {
        if (lagSuspended == value) {
            return;
        }
        lagSuspended = value;
        if (value) {
            plugin.getLogger().warning("PingShield: лагает СЕРВЕР (низкий TPS или высокий пинг у большинства "
                    + "игроков) — автозащита приостановлена, чтобы весь онлайн не встал.");
        } else {
            plugin.getLogger().info("PingShield: сервер стабилен, автозащита снова активна.");
        }
    }

    public boolean isLagSuspended() {
        return lagSuspended;
    }

    // ================================================================== события

    public void handleJoin(Player player) {
        detectProxy(player);
        UUID id = player.getUniqueId();
        PingState state = new PingState();
        state.joinAt = System.currentTimeMillis();
        // Протокол клиента читаем здесь: событие входа выполняется в регионе игрока
        state.clientProtocol = via.protocolOf(player);
        states.put(id, state);
        fallGraceUntil.remove(id);
        active.remove(id);
    }

    public void handleQuit(Player player) {
        UUID id = player.getUniqueId();
        states.remove(id);
        selfTeleportUntil.remove(id);
        combatUntil.remove(id);
        recentDamageUntil.remove(id);
        Protection protection = active.remove(id);
        if (protection != null) {
            protection.markReleased(); // восстанавливать состояние некому: игрок вышел
            audit.write(player.getName(), "PROTECT_END",
                    "reason=QUIT elapsed=" + protection.getElapsedSeconds() + "s");
        }
    }

    public void handleDeath(Player player) {
        Protection protection = get(player);
        if (protection != null) {
            end(player, protection, EndReason.DEATH);
        }
    }

    public void handleRespawn(Player player) {
        Protection protection = get(player);
        if (protection != null) {
            // после смерти полёт сбрасывается сервером — снимаем защиту, чтобы не осталась заморозка
            end(player, protection, EndReason.DEATH);
        }
    }

    public void handleGameModeChange(Player player, GameMode newMode) {
        Protection protection = get(player);
        if (protection == null) {
            return;
        }
        if (newMode == GameMode.SPECTATOR && !cfg.affectSpectator) {
            end(player, protection, EndReason.GAMEMODE);
            return;
        }
        // Смена режима меняет право полёта — перезамораживаем немедленно
        runOn(player, () -> enforce(player, protection, System.currentTimeMillis()));
    }

    // ================================================================== доступ извне

    public Protection get(Player player) {
        if (player == null) {
            return null;
        }
        Protection protection = active.get(player.getUniqueId());
        return (protection == null || protection.isReleased()) ? null : protection;
    }

    public Protection get(UUID uuid) {
        Protection protection = active.get(uuid);
        return (protection == null || protection.isReleased()) ? null : protection;
    }

    public boolean isProtected(Player player) {
        return get(player) != null;
    }

    /** Игрок заморожен (не просто «щит без заморозки»). */
    public boolean isFrozen(Player player) {
        Protection protection = get(player);
        return protection != null && !protection.isImmunityOnly();
    }

    public int protectedCount() {
        return active.size();
    }

    public List<Protection> snapshot() {
        return new ArrayList<>(active.values());
    }

    public PingState state(UUID uuid) {
        return states.get(uuid);
    }

    public boolean isInFallGrace(Player player) {
        Long until = fallGraceUntil.get(player.getUniqueId());
        return until != null && System.currentTimeMillis() < until;
    }

    public boolean isCooldown(Player player) {
        PingState state = states.get(player.getUniqueId());
        return state != null && System.currentTimeMillis() < state.cooldownUntil;
    }

    public void markSelfTeleport(UUID uuid) {
        selfTeleportUntil.put(uuid, System.currentTimeMillis() + 3000L);
    }

    public boolean isSelfTeleport(UUID uuid) {
        Long until = selfTeleportUntil.get(uuid);
        return until != null && System.currentTimeMillis() < until;
    }

    public void refreshAnchor(Player player, Location location) {
        Protection protection = get(player);
        if (protection != null && location != null) {
            protection.setAnchor(location.clone());
        }
    }

    public boolean isNpc(Player player) {
        try {
            return player.hasMetadata(NPC_METADATA);
        } catch (Throwable throwable) {
            return false;
        }
    }

    public boolean hasBypass(Player player) {
        return hasPermission(player, BYPASS_PERMISSION);
    }

    public boolean hasNotifyPermission(Player player) {
        return hasPermission(player, NOTIFY_PERMISSION);
    }

    private boolean hasPermission(Player player, String permission) {
        try {
            return player.hasPermission(permission);
        } catch (Throwable throwable) {
            return false;
        }
    }

    public int pingOf(Player player) {
        try {
            return Math.max(0, player.getPing());
        } catch (Throwable throwable) {
            return 0;
        }
    }

    // ================================================================== метрики

    public long lastTickNanos() {
        return lastTickNanos;
    }

    public double avgTickNanos() {
        return avgTickNanos;
    }

    public long maxTickNanos() {
        return maxTickNanos;
    }

    public int lastScanned() {
        return lastScanned;
    }

    public long totalTicks() {
        return totalTicks;
    }

    public int releaseChecks() {
        return releaseChecks;
    }

    /**
     * Синтетический замер: сколько наносекунд занимает математика одного замера
     * (EWMA + jitter + счётчики) — то, что цикл делает на каждого игрока каждую проверку.
     * Нужен, чтобы отвечать на «а какая нагрузка?» цифрами, а не обещаниями.
     */
    public double benchmarkSampleNanos(int iterations) {
        PingState state = new PingState();
        int[] pings = {120, 3400, 5000, 90, 250, 4100, 300, 700};
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            state.sample(pings[i & 7], cfg.smoothing, 3000, 2000);
        }
        long elapsed = System.nanoTime() - start;
        return iterations <= 0 ? 0.0D : (double) elapsed / (double) iterations;
    }

    // ================================================================== сообщения

    private void sendProtectStart(Player player, Protection protection) {
        int ping = protection.getLastPing();
        String key = protection.isImmunityOnly() && !cfg.msgProtectImmunity.isEmpty()
                ? cfg.msgProtectImmunity
                : cfg.msgProtectStart;
        if (cfg.msgChat) {
            Msg.send(player, cfg.prefix + key,
                    "player", player.getName(), "ping", ping,
                    "enter", protection.getThresholdUsed(),
                    "max", cfg.maxProtectionSeconds,
                    "world", player.getWorld().getName());
        }
        if (cfg.indicator == PingShieldConfig.Indicator.ACTIONBAR || cfg.indicator == PingShieldConfig.Indicator.BOTH) {
            protection.setLastActionbarAt(System.currentTimeMillis());
            Msg.actionBar(player, cfg.msgProtectActionbar,
                    "player", player.getName(), "ping", ping,
                    "elapsed", 0,
                    "enter", protection.getThresholdUsed(),
                    "max", cfg.maxProtectionSeconds,
                    "world", player.getWorld().getName());
        }
        if (cfg.indicator == PingShieldConfig.Indicator.BOSS_BAR || cfg.indicator == PingShieldConfig.Indicator.BOTH) {
            protection.setLastBossBarUpdateAt(0L);
            updateIndicator(player, protection, System.currentTimeMillis());
        }
        playSound(player, Sound.BLOCK_NOTE_BLOCK_BASS);
        notifyStaff(player, protection);
    }

    private void sendProtectEnd(Player player, Protection protection, EndReason reason) {
        int ping = protection.getLastPing();
        if (cfg.msgChat) {
            if (reason == EndReason.TIMEOUT) {
                Msg.send(player, cfg.prefix + cfg.msgProtectTimeout,
                        "player", player.getName(), "ping", ping,
                        "max", cfg.maxProtectionSeconds,
                        "world", player.getWorld().getName());
            } else if (reason == EndReason.STORM && !cfg.msgStormPause.isEmpty()) {
                // Причина снятия здесь не «пинг в норме», а решение администратора сервера
                // (storm.policy = PAUSE_SUBNET/PAUSE_ALL) — игроку нужно это объяснить.
                Msg.send(player, cfg.prefix + cfg.msgStormPause,
                        "player", player.getName(), "ping", ping,
                        "elapsed", protection.getElapsedSeconds(),
                        "hold", cfg.stormHoldSeconds,
                        "world", player.getWorld().getName());
            } else {
                Msg.send(player, cfg.prefix + cfg.msgProtectEnd,
                        "player", player.getName(), "ping", ping,
                        "enter", protection.getThresholdUsed(),
                        "elapsed", protection.getElapsedSeconds(),
                        "world", player.getWorld().getName());
            }
        }
        playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING);
    }

    /**
     * Рассылка сообщения всем, у кого есть право {@code pingshield.notify}, с подстановками.
     * Выполняется в потоке цикла, поэтому каждому получателю — задача в его регионе.
     */
    private void broadcastStaff(String message, Object... placeholders) {
        if (message == null || message.isEmpty()) {
            return;
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!hasNotifyPermission(online)) {
                continue;
            }
            Player staff = online;
            runOn(staff, () -> Msg.send(staff, message, placeholders));
        }
    }

    private void notifyStaff(Player player, Protection protection) {
        if (cfg.msgNotifyStaff == null || cfg.msgNotifyStaff.isEmpty()) {
            return;
        }
        String message = cfg.prefix + cfg.msgNotifyStaff;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getUniqueId().equals(player.getUniqueId()) || !hasNotifyPermission(online)) {
                continue;
            }
            Player staff = online;
            runOn(staff, () -> Msg.send(staff, message,
                    "player", player.getName(),
                    "ping", protection.getLastPing(),
                    "world", player.getWorld().getName()));
        }
    }

    private void playSound(Player player, Sound sound) {
        try {
            player.playSound(player.getLocation(), sound, 1.0F, 1.2F);
        } catch (Throwable ignored) {
            // звук не критичен
        }
    }

    /**
     * Выполняет действие над игроком в его регионе (Folia-safe).
     * Мы в нужном регионе → сразу, иначе ставим через EntityScheduler.
     */
    public void runOn(Player player, Runnable action) {
        if (player == null || !player.isOnline()) {
            return;
        }
        try {
            if (Bukkit.isOwnedByCurrentRegion(player)) {
                action.run();
                return;
            }
            player.getScheduler().run(plugin, task -> {
                if (player.isOnline()) {
                    action.run();
                }
            }, null);
        } catch (Throwable throwable) {
            try {
                action.run(); // плагин выключается — best-effort
            } catch (Throwable ignored) {
                // сервер останавливается
            }
        }
    }

    private void pruneTransient(Collection<? extends Player> online, long now) {
        Set<UUID> ids = new java.util.HashSet<>(Math.max(16, online.size() * 2));
        for (Player player : online) {
            ids.add(player.getUniqueId());
        }
        states.keySet().removeIf(uuid -> !ids.contains(uuid));
        active.keySet().removeIf(uuid -> !ids.contains(uuid));
        fallGraceUntil.entrySet().removeIf(entry -> entry.getValue() < now);
        selfTeleportUntil.entrySet().removeIf(entry -> entry.getValue() < now);
        combatUntil.entrySet().removeIf(entry -> entry.getValue() < now);
        recentDamageUntil.entrySet().removeIf(entry -> entry.getValue() < now);
    }
}
