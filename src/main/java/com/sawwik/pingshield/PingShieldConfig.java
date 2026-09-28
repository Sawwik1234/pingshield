package com.sawwik.pingshield;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Разобранный конфиг в виде обычных полей: горячий код не трогает YamlConfiguration.
 * {@link #load(FileConfiguration)} вызывается при старте и при {@code /pingshield reload}.
 */
public final class PingShieldConfig {

    /**
     * Как именно удерживать игрока на месте.
     *
     * <p>Важно: ни один режим не меняет режим игры (никакого «GM1»/креатива) —
     * игрок остаётся в своём GameMode, неуязвимость даётся отдельным флагом сущности.</p>
     */
    public enum FreezeMode {
        /**
         * ПАССИВНЫЙ (по умолчанию): игрок остаётся в обычном режиме, без полёта.
         * Движение отменяется, скорость ходьбы обнуляется, позиция удерживается
         * возвратом на якорь. Внешне — «застыл на месте», ничего креативного.
         */
        PASSIVE,
        /**
         * Только возврат: движение отменяется, позиция удерживается телепортами,
         * скорость ходьбы не трогается. Самый «тихий» режим для античитов.
         */
        TELEPORT,
        /**
         * Полёт с нулевой скоростью: игрок гарантированно висит в воздухе.
         * Минус: античиты могут пометить «Fly», и это самый заметный для игрока режим.
         */
        FLY
    }

    /** Куда выводить статус защиты. */
    public enum Indicator {
        ACTIONBAR, BOSS_BAR, BOTH, NONE
    }

    /**
     * Что делать с игроком, который упирается в лимит активаций
     * (abuse.max-activations-per-hour) — то есть лагает постоянно.
     */
    public enum ChronicPolicy {
        /** Защищать всегда, лимит активаций игнорируется. */
        PROTECT,
        /** После лимита защита не включается, игрок получает предупреждение (по умолчанию). */
        LIMITED,
        /**
         * После лимита — «щит без заморозки»: игрок играет как обычно, но урон
         * в обе стороны не проходит. Компромисс для плохого провайдера.
         */
        IMMUNITY_ONLY
    }

    /** Что делать с подозрительной активацией (детект «искусственного лага»). */
    public enum SuspiciousPolicy {
        /** Только запись в audit.log. */
        LOG,
        /** Запись + уведомление персоналу. */
        NOTIFY,
        /** Защиту выдать, но в режиме «щит без заморозки» — преимущества всё равно нет. */
        NO_FREEZE,
        /** Защиту не выдавать вовсе (самый строгий режим для PvP-серверов). */
        BLOCK
    }

    /** Что делать при хроническом пинге (много минут плохого соединения подряд). */
    public enum ChronicAction {
        NONE,
        NOTIFY,
        /**
         * «Щит без заморозки»: защита включается как обычно (когда пинг переходит порог входа),
         * но игрока не морозят — он играет нормально, а урон не проходит ни в одну сторону.
         */
        IMMUNITY_ONLY,
        KICK;

        /**
         * Нужно ли в этом цикле пропустить запуск защиты.
         *
         * <p>Единственный случай — KICK: игрока сейчас отключаем, щит ему не нужен.
         * Для остальных действий цикл продолжается, иначе действие, обещающее защиту
         * (IMMUNITY_ONLY), не создавало бы её вовсе — эта ошибка была в 1.6.3.</p>
         */
        public boolean skipsProtection() {
            return this == KICK;
        }

        /** Обещает ли действие щит без заморозки (защита есть, заморозки нет). */
        public boolean immunityShield() {
            return this == IMMUNITY_ONLY;
        }
    }

    /** Что делать, когда лагает целая подсеть (проблема провайдера или атака). */
    /** Что делать при общем сетевом скачке (пинг поднялся сразу у всех игроков). */
    public enum SpikeMode {
        /** Только уведомить персонал: защиты работают как обычно. */
        ALERT_ONLY,
        /** Поднять порог входа на величину скачка (по умолчанию): личный лаг всё ещё защищается,
         *  а общий подъём сети не превращается в массовую заморозку. */
        RAISE_THRESHOLD,
        /** Приостановить автозащиту на время скачка: не морозить онлайн, когда виноват аплинк. */
        SUSPEND
    }

    public enum StormPolicy {
        /** Защищать как обычно, но уведомить персонал — по умолчанию. */
        ALERT_ONLY,
        /** Не защищать игроков из штормящей подсети и снять с них защиту. */
        PAUSE_SUBNET,
        /** Приостановить автозащиту на всём сервере (как при серверном лаге). */
        PAUSE_ALL
    }

    /** Режим работы за прокси (Velocity/Bungee). */
    public enum ProxyMode {
        /** Если обнаружен прокси — автозащита выключается (по умолчанию). */
        AUTO,
        /** Работать как обычно (пинг будет измеряться до прокси, а не до игрока). */
        ENABLE,
        /** Не проверять пинг вообще. */
        DISABLE
    }

    // --- общее ---
    public boolean enabled = true;

    // --- пинг ---
    public int enterThresholdMs = 3000;
    public int exitThresholdMs = 2000;
    public int checkIntervalTicks = 20;
    public int joinGraceSeconds = 15;
    public int maxProtectionSeconds = 90;
    public int timeoutCooldownSeconds = 30;
    public int fallDamageGraceMs = 3000;

    // --- сглаживание и подтверждения (гасят «мигание» защиты) ---
    public int enterConfirmSamples = 2;
    public int exitConfirmSamples = 3;
    public int minProtectSeconds = 5;
    public double smoothing = 0.3D;
    public int jitterThresholdMs = 400;
    public int jitterEnterBonusMs = 1000;

    /** Порог пинга в зависимости от прав: право -> порог входа (мс). */
    public final Map<String, Integer> thresholdOverrides = new LinkedHashMap<>();

    // --- неуязвимость ---
    /** Ставить ли флаг неуязвимости сущности (настоящее «бессмертие», не креатив). */
    public boolean invulnerableFlag = true;

    // --- заморозка ---
    public FreezeMode freezeMode = FreezeMode.PASSIVE;
    public boolean potionLock = true;
    public boolean noCollision = true;
    public boolean freezeAllowVertical = false;
    /** Не сворачивать элитры при заморозке (см. FlightGuard). */
    public boolean keepGliding = true;
    /** Страховка от серверного кика FLYING_PLAYER/FLYING_VEHICLE, пока игрок под защитой. */
    public boolean preventFlyingKick = true;
    /** Держать запас воздуха полным, пока игрок заморожен (см. EnvironmentGuard). */
    public boolean keepAir = true;
    /** Выталкивать игрока из блока при разморозке, иначе он задохнётся (песок, гравий, обвал). */
    public boolean escapeSuffocation = true;
    /** На сколько блоков вверх искать свободное место при выталкивании из блока. */
    public int escapeSearchBlocks = 8;
    /** Сбрасывать накопленное замерзание (порошковый снег), пока игрок заморожен. */
    public boolean clearFreezeTicks = true;
    /** Продлевать заморозку, пока на игроке опасный эффект (например, Wither) — иначе смерть сразу после снятия. */
    public Set<PotionEffectType> holdEffectTypes = new LinkedHashSet<>();
    /** Сбрасывать таймер простоя, пока игрок заморожен (иначе сервер кикнет за «idling»). */
    public boolean resetIdleTimer = true;
    public boolean teleportBack = true;
    public double teleportBackTolerance = 4.0D;

    // --- урон ---
    public boolean blockIncoming = true;
    public Set<DamageCause> incomingExceptions = EnumSet.noneOf(DamageCause.class);
    public boolean blockOutgoing = true;
    public boolean blockTamedPets = true;

    // --- взаимодействия ---
    public boolean blockInteractions = true;
    public boolean blockSleeping = true;
    public boolean mobsIgnoreProtected = true;
    public boolean ejectFromVehicles = true;
    public boolean blockCommands = false;
    public Set<String> allowedCommands = new LinkedHashSet<>();
    /** Блокировать даже открытие собственного инвентаря: «не может сделать ничего». */
    public boolean blockOwnInventory = true;
    /** Не давать подбирать предметы, пока игрок заморожен. */
    public boolean blockItemPickup = true;
    /** Блокировать любые телепорты (порталы, эндер-жемчуг, сторонние плагины). */
    public boolean blockTeleports = true;

    // --- безопасное снятие защиты ---
    public boolean extinguishOnFreeze = true;
    public boolean restoreFood = true;
    public boolean extendInDanger = true;
    public int extendInDangerMaxSeconds = 15;
    public Set<Material> dangerBlocks = new LinkedHashSet<>();
    public Set<PotionEffectType> releaseEffects = new LinkedHashSet<>();
    public int releaseEffectsDurationTicks = 60;

    // --- бой ---
    public int combatTagSeconds = 8;
    public boolean combatPreventProtection = false;

    // --- анти-абуз ---
    public int abuseCooldownAfterEndSeconds = 15;
    public int abuseMaxActivationsPerHour = 8;
    public ChronicPolicy chronicPolicy = ChronicPolicy.LIMITED;

    // --- детект «искусственного лага» ---
    public boolean suspiciousEnabled = true;
    /** Сумма баллов, при которой активация считается подозрительной. */
    public int suspiciousThreshold = 3;
    public int suspiciousInCombatPoints = 2;
    public int suspiciousRecentDamagePoints = 1;
    public int suspiciousRecentDamageSeconds = 5;
    public int suspiciousLowHealthPoints = 1;
    public int suspiciousLowHealthPercent = 35;
    public int suspiciousGoodPingPoints = 1;
    /** Если «базовый» пинг игрока (медленная EWMA) ниже этого — внезапный лаг подозрителен. */
    public int suspiciousGoodPingMs = 400;
    public int suspiciousFrequentPoints = 1;
    public SuspiciousPolicy suspiciousPolicy = SuspiciousPolicy.LOG;
    public int suspiciousAlertCooldownSeconds = 120;
    public int suspiciousBlockCooldownSeconds = 60;

    // --- хронический пинг ---
    public boolean chronicPingEnabled = true;
    /** Сколько секунд «плохого» пинга (с затуханием) считаем хроническим. */
    public int chronicPingSeconds = 600;
    public ChronicAction chronicAction = ChronicAction.NOTIFY;
    public int chronicKickWarningSeconds = 30;
    /** Сколько секунд хорошего пинга подряд сбрасывают накопленный счётчик. */
    public int chronicResetAfterGoodSeconds = 120;

    // --- пинговый шторм (подсети) ---
    public boolean stormEnabled = true;
    public int stormMinPlayersSameSubnet = 3;
    public int stormHoldSeconds = 120;
    public int stormAlertCooldownSeconds = 300;
    public StormPolicy stormPolicy = StormPolicy.ALERT_ONLY;
    public Set<String> stormIgnoreSubnets = new LinkedHashSet<>();

    // --- общий сетевой скачок (network-spike) ---
    public boolean networkSpikeEnabled = true;
    public int networkSpikeMinPlayers = 20;
    public int networkSpikeBaselineSeconds = 60;
    public int networkSpikeDeltaMs = 120;
    public int networkSpikeMinMedianMs = 180;
    public double networkSpikeMinRatio = 1.4D;
    public double networkSpikeAffectedShare = 0.6D;
    public int networkSpikeConfirmCycles = 3;
    public int networkSpikeRecoveryCycles = 10;
    /** Дольше этого времени скачок считается «новой нормой» канала (база принимает новый уровень). */
    public int networkSpikeMaxSeconds = 900;
    public SpikeMode networkSpikeMode = SpikeMode.RAISE_THRESHOLD;
    public int networkSpikeMaxBonusMs = 1500;
    public int networkSpikeAlertCooldownSeconds = 300;
    public boolean networkSpikeSuppressMetrics = true;

    // --- производительность ---
    /** Как часто перепроверять состояние защищённого игрока (тики). 10 = дважды в секунду. */
    public int enforceIntervalTicks = 10;

    // --- прокси ---
    public ProxyMode proxyMode = ProxyMode.AUTO;

    // --- лимиты ---
    public boolean affectCreative = true;
    public boolean affectSpectator = false;
    public Set<String> disabledWorlds = new LinkedHashSet<>();

    // --- защита от серверных лагов ---
    public boolean lagGuardEnabled = true;
    public double lagGuardRatio = 0.75D;
    public int lagGuardMinPlayers = 4;
    public double lagGuardMinTps = 8.0D;
    public int lagGuardResumeCycles = 3;

    // --- сообщения ---
    public Indicator indicator = Indicator.ACTIONBAR;
    public boolean msgChat = true;
    public int actionbarIntervalSeconds = 3;
    public String prefix = "";
    public String msgProtectStart = "";
    public String msgProtectActionbar = "";
    public String msgBossBar = "";
    public String msgProtectEnd = "";
    public String msgProtectTimeout = "";
    public String msgProtectLimit = "";
    public String msgProtectImmunity = "";
    public String msgProtectDangerHold = "";
    public String msgNotifyStaff = "";
    public String cmdNoPermission = "";
    public String cmdUsage = "";
    public String cmdReloaded = "";
    public String cmdPlayerNotFound = "";
    public String cmdStatusHeader = "";
    public String cmdStatusEntry = "";
    public String cmdStatusEmpty = "";
    public String cmdManualProtect = "";
    public String cmdManualUnprotect = "";
    public String cmdPlayerNotProtected = "";
    public String cmdCheckProtected = "";
    public String cmdCheckClear = "";
    public String cmdInfo = "";
    public String cmdProxyWarning = "";
    public String cmdProfile = "";
    public String cmdPerf = "";
    public String msgCpForeignChanges = "";
    public String cmdCpHeader = "";
    public String cmdCpEmpty = "";
    public String cmdCpEntry = "";
    public String cmdCpHint = "";
    public String cmdCoreProtectUnavailable = "";
    public String cmdViaHeader = "";
    public String cmdViaEntry = "";
    public String btnChatPlayer = "";
    public String btnChatAll = "";
    public String btnChatStart = "";
    public String btnBlocks = "";
    public String btnArea = "";
    public String btnClick = "";
    public String btnContainer = "";
    public String btnHint = "";
    public String cmdSearchHeader = "";
    public String cmdPermsHeader = "";
    public String cmdPermsNode = "";
    public String cmdPermsLuckPerms = "";
    public String cmdPermsUnavailable = "";
    public String msgSuspiciousAlert = "";
    public String msgChronicNotify = "";
    public String msgChronicKickWarning = "";
    public String msgChronicKick = "";
    public String msgStormAlert = "";
    /** Сообщение игроку, когда защиту сняли из-за пингового шторма подсети. */
    public String msgStormPause = "";
    /** Уведомление персоналу: пинг поднялся у всех сразу (общий сетевой скачок). */
    public String msgNetworkSpikeAlert = "";
    /** Уведомление персоналу: сеть вернулась в норму. */
    public String msgNetworkSpikeEnd = "";
    /** Строка /pingshield net — состояние детектора скачка. */
    public String cmdNet = "";

    // --- интеграция: CoreProtect ---
    public boolean cpEnabled = true;
    public boolean cpMarkers = true;
    public boolean cpCheckOnRelease = true;
    public int cpRadius = 3;
    public boolean cpAlertForeign = true;
    public int cpMaxResults = 300;
    public Set<String> cpIgnorePlayers = new LinkedHashSet<>();
    public int cpMinutesForManual = 10;
    /** Тег маркеров в логе CoreProtect — по нему работает фильтр f: в /co lookup. */
    public String cpMarkerTag = "PingShield";
    /** Писать ли запись взаимодействия в точке удержания (ищется через a:click r:N). */
    public boolean cpLogInteraction = false;

    // --- интеграция: LuckPerms ---
    public boolean luckPermsEnabled = true;
    public String lpThresholdMetaKey = "pingshield-threshold";
    public String lpModeMetaKey = "pingshield-freeze-mode";
    public int lpCacheSeconds = 30;

    // --- интеграция: ViaVersion ---
    public boolean viaEnabled = true;
    public int viaExtraThresholdMs = 500;
    public int viaExtraJitterBonusMs = 500;
    public int viaServerProtocol = 0;
    public final Map<Integer, String> viaVersionNames = new LinkedHashMap<>();

    // --- журнал ---
    public boolean auditEnabled = true;
    public String auditFileName = "audit.log";

    private List<String> rawDisabledWorlds = new ArrayList<>();

    // ------------------------------------------------------------------ загрузка

    public void load(FileConfiguration c) {
        enabled = c.getBoolean("enabled", true);

        enterThresholdMs = c.getInt("ping.enter-threshold-ms", 3000);
        exitThresholdMs = c.getInt("ping.exit-threshold-ms", 2000);
        checkIntervalTicks = c.getInt("ping.check-interval-ticks", 20);
        joinGraceSeconds = c.getInt("ping.join-grace-seconds", 15);
        maxProtectionSeconds = c.getInt("ping.max-protection-seconds", 90);
        timeoutCooldownSeconds = c.getInt("ping.timeout-cooldown-seconds", 30);
        fallDamageGraceMs = c.getInt("ping.fall-damage-grace-ms", 3000);
        enterConfirmSamples = c.getInt("ping.enter-confirm-samples", 2);
        exitConfirmSamples = c.getInt("ping.exit-confirm-samples", 3);
        minProtectSeconds = c.getInt("ping.min-protect-seconds", 5);
        smoothing = c.getDouble("ping.smoothing", 0.3D);
        jitterThresholdMs = c.getInt("ping.jitter-threshold-ms", 400);
        jitterEnterBonusMs = c.getInt("ping.jitter-enter-bonus-ms", 1000);
        thresholdOverrides.clear();
        if (c.isConfigurationSection("thresholds.overrides")) {
            for (String key : c.getConfigurationSection("thresholds.overrides").getKeys(false)) {
                thresholdOverrides.put(key, c.getInt("thresholds.overrides." + key, enterThresholdMs));
            }
        }

        invulnerableFlag = c.getBoolean("freeze.invulnerable-flag", true);
        freezeMode = enumValue(FreezeMode.class, c.getString("freeze.mode"), FreezeMode.PASSIVE);
        potionLock = c.getBoolean("freeze.potion-lock", true);
        noCollision = c.getBoolean("freeze.no-collision", true);
        freezeAllowVertical = c.getBoolean("freeze.allow-vertical-movement", false);
        keepGliding = c.getBoolean("freeze.keep-gliding", true);
        preventFlyingKick = c.getBoolean("freeze.prevent-flying-kick", true);
        keepAir = c.getBoolean("freeze.keep-air", true);
        escapeSuffocation = c.getBoolean("release.safety.escape-suffocation", true);
        escapeSearchBlocks = c.getInt("release.safety.escape-search-blocks", 8);
        clearFreezeTicks = c.getBoolean("freeze.clear-freeze-ticks", true);
        resetIdleTimer = c.getBoolean("freeze.reset-idle-timer", true);
        holdEffectTypes = parseEffects(c.getStringList("release.safety.hold-effects"));
        teleportBack = c.getBoolean("freeze.teleport-back.enabled", true);
        teleportBackTolerance = c.getDouble("freeze.teleport-back.tolerance-blocks", 4.0D);

        blockIncoming = c.getBoolean("damage.incoming.block-all", true);
        incomingExceptions = parseDamageCauses(c.getStringList("damage.incoming.exceptions"));
        blockOutgoing = c.getBoolean("damage.outgoing.block", true);
        blockTamedPets = c.getBoolean("damage.outgoing.block-tamed-pets", true);

        blockInteractions = c.getBoolean("interactions.block", true);
        blockSleeping = c.getBoolean("interactions.block-sleeping", true);
        mobsIgnoreProtected = c.getBoolean("interactions.mobs-ignore-protected", true);
        ejectFromVehicles = c.getBoolean("interactions.eject-from-vehicles", true);
        blockCommands = c.getBoolean("interactions.commands.block", false);
        allowedCommands = lowerSet(c.getStringList("interactions.commands.allowed"));
        blockOwnInventory = c.getBoolean("interactions.block-own-inventory", true);
        blockItemPickup = c.getBoolean("interactions.block-item-pickup", true);
        blockTeleports = c.getBoolean("interactions.block-teleports", true);

        extinguishOnFreeze = c.getBoolean("release.safety.extinguish-on-freeze", true);
        restoreFood = c.getBoolean("release.safety.restore-food", true);
        extendInDanger = c.getBoolean("release.safety.extend-in-danger", true);
        extendInDangerMaxSeconds = c.getInt("release.safety.extend-in-danger-max-seconds", 15);
        dangerBlocks = parseMaterials(c.getStringList("release.safety.danger-blocks"));
        releaseEffects = parseEffects(c.getStringList("release.safety.effects"));
        releaseEffectsDurationTicks = c.getInt("release.safety.effects-duration-ticks", 60);

        combatTagSeconds = c.getInt("combat.tag-seconds", 8);
        combatPreventProtection = c.getBoolean("combat.prevent-protection", false);

        abuseCooldownAfterEndSeconds = c.getInt("abuse.cooldown-after-end-seconds", 15);
        abuseMaxActivationsPerHour = c.getInt("abuse.max-activations-per-hour", 8);
        chronicPolicy = enumValue(ChronicPolicy.class, c.getString("abuse.chronic-policy"), ChronicPolicy.LIMITED);

        suspiciousEnabled = c.getBoolean("abuse.suspicious.enabled", true);
        suspiciousThreshold = c.getInt("abuse.suspicious.threshold", 3);
        suspiciousInCombatPoints = c.getInt("abuse.suspicious.points.in-combat", 2);
        suspiciousRecentDamagePoints = c.getInt("abuse.suspicious.points.recent-damage", 1);
        suspiciousRecentDamageSeconds = c.getInt("abuse.suspicious.points.recent-damage-seconds", 5);
        suspiciousLowHealthPoints = c.getInt("abuse.suspicious.points.low-health", 1);
        suspiciousLowHealthPercent = c.getInt("abuse.suspicious.points.low-health-percent", 35);
        suspiciousGoodPingPoints = c.getInt("abuse.suspicious.points.good-average-ping", 1);
        suspiciousGoodPingMs = c.getInt("abuse.suspicious.points.good-average-ping-ms", 400);
        suspiciousFrequentPoints = c.getInt("abuse.suspicious.points.frequent", 1);
        suspiciousPolicy = enumValue(SuspiciousPolicy.class, c.getString("abuse.suspicious.policy"),
                SuspiciousPolicy.LOG);
        suspiciousAlertCooldownSeconds = c.getInt("abuse.suspicious.alert-cooldown-seconds", 120);
        suspiciousBlockCooldownSeconds = c.getInt("abuse.suspicious.block-cooldown-seconds", 60);

        chronicPingEnabled = c.getBoolean("abuse.chronic-ping.enabled", true);
        chronicPingSeconds = c.getInt("abuse.chronic-ping.bad-ping-seconds", 600);
        chronicAction = enumValue(ChronicAction.class, c.getString("abuse.chronic-ping.action"),
                ChronicAction.NOTIFY);
        chronicKickWarningSeconds = c.getInt("abuse.chronic-ping.kick-warning-seconds", 30);
        chronicResetAfterGoodSeconds = c.getInt("abuse.chronic-ping.reset-after-good-seconds", 120);

        stormEnabled = c.getBoolean("storm.enabled", true);
        stormMinPlayersSameSubnet = c.getInt("storm.min-players-same-subnet", 3);
        stormHoldSeconds = c.getInt("storm.hold-seconds", 120);
        stormAlertCooldownSeconds = c.getInt("storm.alert-cooldown-seconds", 300);
        stormPolicy = enumValue(StormPolicy.class, c.getString("storm.policy"), StormPolicy.ALERT_ONLY);
        stormIgnoreSubnets = lowerSet(c.getStringList("storm.ignore-subnets"));

        networkSpikeEnabled = c.getBoolean("network-spike.enabled", true);
        networkSpikeMinPlayers = c.getInt("network-spike.min-players", 20);
        networkSpikeBaselineSeconds = c.getInt("network-spike.baseline-seconds", 60);
        networkSpikeDeltaMs = c.getInt("network-spike.median-delta-ms", 120);
        networkSpikeMinMedianMs = c.getInt("network-spike.min-median-ms", 180);
        networkSpikeMinRatio = c.getDouble("network-spike.min-ratio", 1.4D);
        networkSpikeAffectedShare = c.getDouble("network-spike.affected-share", 0.6D);
        networkSpikeConfirmCycles = c.getInt("network-spike.confirm-cycles", 3);
        networkSpikeRecoveryCycles = c.getInt("network-spike.recovery-cycles", 10);
        networkSpikeMaxSeconds = c.getInt("network-spike.max-spike-seconds", 900);
        networkSpikeMode = enumValue(SpikeMode.class, c.getString("network-spike.mode"), SpikeMode.RAISE_THRESHOLD);
        networkSpikeMaxBonusMs = c.getInt("network-spike.max-threshold-bonus-ms", 1500);
        networkSpikeAlertCooldownSeconds = c.getInt("network-spike.alert-cooldown-seconds", 300);
        networkSpikeSuppressMetrics = c.getBoolean("network-spike.suppress-player-metrics", true);
        enforceIntervalTicks = c.getInt("perf.enforce-interval-ticks", 10);

        proxyMode = enumValue(ProxyMode.class, c.getString("proxy.mode"), ProxyMode.AUTO);

        affectCreative = c.getBoolean("limits.affect-creative", true);
        affectSpectator = c.getBoolean("limits.affect-spectator", false);
        rawDisabledWorlds = new ArrayList<>(c.getStringList("limits.disabled-worlds"));
        disabledWorlds = lowerSet(rawDisabledWorlds);

        lagGuardEnabled = c.getBoolean("server-lag-guard.enabled", true);
        lagGuardRatio = c.getDouble("server-lag-guard.ratio", 0.75D);
        lagGuardMinPlayers = c.getInt("server-lag-guard.min-players", 4);
        lagGuardMinTps = c.getDouble("server-lag-guard.min-tps", 8.0D);
        lagGuardResumeCycles = c.getInt("server-lag-guard.resume-cycles", 3);

        indicator = enumValue(Indicator.class, c.getString("messages.indicator"), Indicator.ACTIONBAR);
        msgChat = c.getBoolean("messages.chat", true);
        actionbarIntervalSeconds = c.getInt("messages.interval-seconds", 3);
        prefix = str(c, "messages.prefix");
        msgProtectStart = str(c, "messages.protect-start");
        msgProtectActionbar = str(c, "messages.protect-actionbar");
        msgBossBar = str(c, "messages.protect-boss-bar");
        msgProtectEnd = str(c, "messages.protect-end");
        msgProtectTimeout = str(c, "messages.protect-timeout");
        msgProtectLimit = str(c, "messages.protect-limit-reached");
        msgProtectImmunity = str(c, "messages.protect-immunity-only");
        msgProtectDangerHold = str(c, "messages.protect-danger-hold");
        msgNotifyStaff = str(c, "messages.notify-staff");
        cmdNoPermission = str(c, "messages.cmd-no-permission");
        cmdUsage = str(c, "messages.cmd-usage");
        cmdReloaded = str(c, "messages.cmd-reloaded");
        cmdPlayerNotFound = str(c, "messages.cmd-player-not-found");
        cmdStatusHeader = str(c, "messages.cmd-status-header");
        cmdStatusEntry = str(c, "messages.cmd-status-entry");
        cmdStatusEmpty = str(c, "messages.cmd-status-empty");
        cmdManualProtect = str(c, "messages.cmd-manual-protect");
        cmdManualUnprotect = str(c, "messages.cmd-manual-unprotect");
        cmdPlayerNotProtected = str(c, "messages.cmd-player-not-protected");
        cmdCheckProtected = str(c, "messages.cmd-check-protected");
        cmdCheckClear = str(c, "messages.cmd-check-clear");
        cmdInfo = str(c, "messages.cmd-info");
        cmdProxyWarning = str(c, "messages.cmd-proxy-warning");
        cmdProfile = str(c, "messages.cmd-profile");
        cmdPerf = str(c, "messages.cmd-perf");
        msgCpForeignChanges = str(c, "messages.coreprotect-foreign-changes");
        cmdCpHeader = str(c, "messages.cmd-coreprotect-header");
        cmdCpEmpty = str(c, "messages.cmd-coreprotect-empty");
        cmdCpEntry = str(c, "messages.cmd-coreprotect-entry");
        cmdCpHint = str(c, "messages.cmd-coreprotect-hint");
        cmdCoreProtectUnavailable = str(c, "messages.cmd-coreprotect-unavailable");
        cmdViaHeader = str(c, "messages.cmd-via-header");
        cmdViaEntry = str(c, "messages.cmd-via-entry");
        btnChatPlayer = str(c, "messages.cp-button-chat-player");
        btnChatAll = str(c, "messages.cp-button-chat-all");
        btnChatStart = str(c, "messages.cp-button-chat-start");
        btnBlocks = str(c, "messages.cp-button-blocks");
        btnArea = str(c, "messages.cp-button-area");
        btnClick = str(c, "messages.cp-button-click");
        btnContainer = str(c, "messages.cp-button-container");
        btnHint = str(c, "messages.cp-button-hint");
        cmdSearchHeader = str(c, "messages.cmd-search-header");
        cmdPermsHeader = str(c, "messages.cmd-perms-header");
        cmdPermsNode = str(c, "messages.cmd-perms-node");
        cmdPermsLuckPerms = str(c, "messages.cmd-perms-luckperms");
        cmdPermsUnavailable = str(c, "messages.cmd-perms-unavailable");
        msgSuspiciousAlert = str(c, "messages.suspicious-alert");
        msgChronicNotify = str(c, "messages.chronic-ping-notify");
        msgChronicKickWarning = str(c, "messages.chronic-ping-kick-warning");
        msgChronicKick = str(c, "messages.chronic-ping-kick");
        msgStormAlert = str(c, "messages.storm-alert");
        msgStormPause = str(c, "messages.storm-pause");
        msgNetworkSpikeAlert = str(c, "messages.network-spike-alert");
        msgNetworkSpikeEnd = str(c, "messages.network-spike-end");
        cmdNet = str(c, "messages.cmd-net");

        cpEnabled = c.getBoolean("integrations.coreprotect.enabled", true);
        cpMarkers = c.getBoolean("integrations.coreprotect.markers", true);
        cpCheckOnRelease = c.getBoolean("integrations.coreprotect.check-on-release", true);
        cpRadius = c.getInt("integrations.coreprotect.radius", 3);
        cpAlertForeign = c.getBoolean("integrations.coreprotect.alert-on-foreign-changes", true);
        cpMaxResults = c.getInt("integrations.coreprotect.max-results", 300);
        cpIgnorePlayers = lowerSet(c.getStringList("integrations.coreprotect.ignore-players"));
        cpMinutesForManual = c.getInt("integrations.coreprotect.manual-lookup-minutes", 10);
        cpMarkerTag = c.getString("integrations.coreprotect.marker-tag", "PingShield");
        cpLogInteraction = c.getBoolean("integrations.coreprotect.log-interaction", false);

        luckPermsEnabled = c.getBoolean("integrations.luckperms.enabled", true);
        lpThresholdMetaKey = c.getString("integrations.luckperms.threshold-meta-key", "pingshield-threshold");
        lpModeMetaKey = c.getString("integrations.luckperms.freeze-mode-meta-key", "pingshield-freeze-mode");
        lpCacheSeconds = c.getInt("integrations.luckperms.cache-seconds", 30);

        viaEnabled = c.getBoolean("integrations.via.enabled", true);
        viaExtraThresholdMs = c.getInt("integrations.via.extra-threshold-ms", 500);
        viaExtraJitterBonusMs = c.getInt("integrations.via.extra-jitter-bonus-ms", 500);
        viaServerProtocol = c.getInt("integrations.via.server-protocol", 0);
        viaVersionNames.clear();
        if (c.isConfigurationSection("integrations.via.version-names")) {
            for (String key : c.getConfigurationSection("integrations.via.version-names").getKeys(false)) {
                try {
                    viaVersionNames.put(Integer.parseInt(key.trim()), String.valueOf(
                            c.get("integrations.via.version-names." + key)));
                } catch (NumberFormatException ignored) {
                    // некорректный номер протокола — пропускаем
                }
            }
        }

        auditEnabled = c.getBoolean("logging.audit-log", true);
        auditFileName = c.getString("logging.file", "audit.log");
    }

    /**
     * Проверяет и (где можно) чинит значения конфига.
     * Возвращает список предупреждений для консоли — конфиг не должен молча ломать логику.
     */
    public List<String> validate() {
        List<String> warnings = new ArrayList<>();

        if (enterThresholdMs < 100) {
            enterThresholdMs = 100;
            warnings.add("ping.enter-threshold-ms слишком мал — установлено 100.");
        }
        if (exitThresholdMs >= enterThresholdMs) {
            int suggested = Math.max(50, (int) (enterThresholdMs * 0.6D));
            warnings.add("ping.exit-threshold-ms (" + exitThresholdMs + ") должен быть МЕНЬШЕ "
                    + "ping.enter-threshold-ms (" + enterThresholdMs + "), иначе защита будет мигать "
                    + "включение/выключение каждую секунду. Исправлено на " + suggested + ".");
            exitThresholdMs = suggested;
        }
        if (checkIntervalTicks < 1) {
            checkIntervalTicks = 1;
            warnings.add("ping.check-interval-ticks < 1 — установлено 1.");
        }
        if (checkIntervalTicks < 10) {
            warnings.add("ping.check-interval-ticks = " + checkIntervalTicks + ": проверка чаще 10 раз в секунду "
                    + "не даёт точности (пинг меняется медленнее), но грузит сервер.");
        }
        if (joinGraceSeconds < 0) {
            joinGraceSeconds = 0;
            warnings.add("ping.join-grace-seconds < 0 — установлено 0.");
        }
        if (maxProtectionSeconds < 0) {
            maxProtectionSeconds = 0;
            warnings.add("ping.max-protection-seconds < 0 — установлено 0 (без лимита, не рекомендуется).");
        }
        if (enterThresholdMs > 10000) {
            warnings.add("ping.enter-threshold-ms = " + enterThresholdMs + " ms: за это время игрок почти "
                    + "наверняка умрёт от мобов. Рабочий диапазон — 1500–5000 ms.");
        }
        if (smoothing > 1.0D) {
            smoothing = 1.0D;
            warnings.add("ping.smoothing > 1.0 — установлено 1.0 (сглаживание выключено).");
        }
        if (smoothing < 0.05D) {
            smoothing = 0.05D;
            warnings.add("ping.smoothing < 0.05 — установлено 0.05: при таком сглаживании реакция на "
                    + "реальный обрыв связи занимает десятки секунд.");
        }
        if (smoothing < 0.15D) {
            warnings.add("ping.smoothing = " + smoothing + " — очень плавно; проверьте, что защита "
                    + "успевает включиться до смерти игрока.");
        }
        if (enterConfirmSamples < 1) {
            enterConfirmSamples = 1;
            warnings.add("ping.enter-confirm-samples < 1 — установлено 1 (защита по первому же замеру).");
        }
        if (exitConfirmSamples < 1) {
            exitConfirmSamples = 1;
            warnings.add("ping.exit-confirm-samples < 1 — установлено 1.");
        }
        if (enterConfirmSamples > 1 && enterConfirmSamples * checkIntervalTicks > 60) {
            warnings.add("ping.enter-confirm-samples = " + enterConfirmSamples + " при периоде "
                    + checkIntervalTicks + " тик(ов): защита включится только через "
                    + (enterConfirmSamples * checkIntervalTicks / 20) + " с — игрок может не дожить.");
        }
        if (minProtectSeconds < 0) {
            minProtectSeconds = 0;
            warnings.add("ping.min-protect-seconds < 0 — установлено 0.");
        }
        if (maxProtectionSeconds > 0 && minProtectSeconds > maxProtectionSeconds) {
            minProtectSeconds = maxProtectionSeconds;
            warnings.add("ping.min-protect-seconds больше ping.max-protection-seconds — уменьшено до " + minProtectSeconds + ".");
        }
        if (jitterThresholdMs < 0) {
            jitterThresholdMs = 0;
            warnings.add("ping.jitter-threshold-ms < 0 — установлено 0.");
        }
        if (jitterEnterBonusMs < 0) {
            jitterEnterBonusMs = 0;
            warnings.add("ping.jitter-enter-bonus-ms < 0 — установлено 0.");
        }
        if (jitterEnterBonusMs > 0 && jitterThresholdMs == 0) {
            warnings.add("ping.jitter-threshold-ms = 0 при ненулевой надбавке: порог будет подниматься "
                    + "почти для всех игроков. Обычно jitter-threshold-ms ставят 300-600 ms.");
        }
        if (enforceIntervalTicks < 1) {
            enforceIntervalTicks = 1;
            warnings.add("perf.enforce-interval-ticks < 1 — установлено 1.");
        }
        if (enforceIntervalTicks > 40) {
            warnings.add("perf.enforce-interval-ticks = " + enforceIntervalTicks + " ("
                    + (enforceIntervalTicks / 20) + " с): позиция замороженного игрока может «уезжать» "
                    + "дальше допустимого до возврата.");
        }
        if (suspiciousThreshold < 1) {
            suspiciousThreshold = 1;
            warnings.add("abuse.suspicious.threshold < 1 — установлено 1 (подозрительной станет почти "
                    + "каждая активация). Разумно 2-4.");
        }
        if (suspiciousEnabled) {
            int maxPoints = Math.max(0, suspiciousInCombatPoints) + Math.max(0, suspiciousRecentDamagePoints)
                    + Math.max(0, suspiciousLowHealthPoints) + Math.max(0, suspiciousGoodPingPoints)
                    + Math.max(0, suspiciousFrequentPoints);
            if (suspiciousThreshold > maxPoints) {
                warnings.add("abuse.suspicious.threshold = " + suspiciousThreshold + " больше суммы всех баллов ("
                        + maxPoints + ") — детект никогда не сработает. Уменьшите порог или увеличьте баллы.");
            }
        }
        if (suspiciousPolicy == SuspiciousPolicy.BLOCK) {
            warnings.add("abuse.suspicious.policy = BLOCK: игроки, попавшие под подозрение, останутся БЕЗ защиты "
                    + "и могут умереть от лагов. Это осознанный выбор для PvP-серверов — проверьте, что "
                    + "порог баллов не слишком низкий.");
        }
        if (suspiciousGoodPingPoints > 0 && suspiciousGoodPingMs < 150) {
            warnings.add("abuse.suspicious.points.good-average-ping-ms = " + suspiciousGoodPingMs + " — слишком "
                    + "низко: балл будет начисляться почти всем. Разумно 300-600 ms.");
        }
        if (chronicPingSeconds < 60) {
            warnings.add("abuse.chronic-ping.bad-ping-seconds = " + chronicPingSeconds + " — меньше минуты, "
                    + "это не «хронический» пинг, а обычный спайк.");
        }
        if (chronicAction == ChronicAction.KICK) {
            warnings.add("abuse.chronic-ping.action = KICK: игрок будет отключён от сервера после "
                    + chronicPingSeconds + " с плохого пинга. Сообщение игроку настраивается в "
                    + "messages.chronic-ping-kick.");
        }
        if (chronicKickWarningSeconds < 5) {
            chronicKickWarningSeconds = 5;
            warnings.add("abuse.chronic-ping.kick-warning-seconds < 5 — установлено 5: игрок не успеет прочитать "
                    + "предупреждение.");
        }
        if (chronicResetAfterGoodSeconds < 10) {
            chronicResetAfterGoodSeconds = 10;
            warnings.add("abuse.chronic-ping.reset-after-good-seconds < 10 — установлено 10.");
        }
        if (stormEnabled && stormMinPlayersSameSubnet < 2) {
            stormMinPlayersSameSubnet = 2;
            warnings.add("storm.min-players-same-subnet < 2 — установлено 2: штормом будет считаться пара игроков "
                    + "из одной подсети.");
        }
        if (stormPolicy == StormPolicy.PAUSE_SUBNET && stormMinPlayersSameSubnet < 3) {
            warnings.add("storm.policy = PAUSE_SUBNET при пороге " + stormMinPlayersSameSubnet + ": защита будет "
                    + "сниматься слишком охотно. Обычно порог 3-5 игроков.");
        }
        if (networkSpikeEnabled) {
            if (networkSpikeMinPlayers < 2) {
                networkSpikeMinPlayers = 2;
                warnings.add("network-spike.min-players < 2 — установлено 2: на меньшем числе игроков "
                        + "медиана недостоверна, скачок легко спутать с одним лагающим.");
            }
            if (networkSpikeDeltaMs < 20) {
                networkSpikeDeltaMs = 20;
                warnings.add("network-spike.median-delta-ms < 20 — установлено 20 мс (иначе обычный "
                        + "дрейф пинга будет считаться скачком).");
            }
            if (networkSpikeMinRatio < 1.05D) {
                networkSpikeMinRatio = 1.05D;
                warnings.add("network-spike.min-ratio < 1.05 — установлено 1.05.");
            }
            if (networkSpikeAffectedShare < 0.2D || networkSpikeAffectedShare > 1.0D) {
                networkSpikeAffectedShare = 0.6D;
                warnings.add("network-spike.affected-share должен быть в диапазоне 0.2…1.0 — установлено 0.6.");
            }
            if (networkSpikeBaselineSeconds < 10) {
                networkSpikeBaselineSeconds = 10;
                warnings.add("network-spike.baseline-seconds < 10 — установлено 10: база не успевает "
                        + "набрать статистику.");
            }
            if (networkSpikeRecoveryCycles < 1) {
                networkSpikeRecoveryCycles = 1;
            }
            if (networkSpikeMaxBonusMs < 0) {
                networkSpikeMaxBonusMs = 0;
            }
            if (networkSpikeMaxSeconds < 60) {
                networkSpikeMaxSeconds = 60;
                warnings.add("network-spike.max-spike-seconds < 60 — установлено 60: иначе аномалия "
                        + "будет считаться «новой нормой» почти сразу.");
            }
        }
        if (stormHoldSeconds < 10) {
            stormHoldSeconds = 10;
            warnings.add("storm.hold-seconds < 10 — установлено 10.");
        }
        if (chronicPolicy == ChronicPolicy.IMMUNITY_ONLY) {
            warnings.add("abuse.chronic-policy = IMMUNITY_ONLY: игроки, часто попадающие в лимит, получают "
                    + "щит БЕЗ заморозки (урон в обе стороны отключён, двигаться можно). Убедитесь, что это "
                    + "подходит правилам вашего PvP.");
        }
        if (releasesProtectionInstantlyAtLowPing()) {
            warnings.add("Гистерезис всего " + (enterThresholdMs - exitThresholdMs) + " ms — при " +
                    "«пилообразном» пинге игрок будет то размораживаться, то замораживаться.");
        }
        if (enterThresholdMs <= exitThresholdMs + 200) {
            warnings.add("Разница порогов меньше 200 ms: состояние будет дёргаться.");
        }

        for (Map.Entry<String, Integer> entry : thresholdOverrides.entrySet()) {
            if (entry.getValue() == null || entry.getValue() < 100) {
                warnings.add("thresholds.overrides." + entry.getKey() + " задан неверно — будет использован "
                        + "общий порог.");
            } else if (entry.getValue() <= exitThresholdMs) {
                warnings.add("thresholds.overrides." + entry.getKey() + " = " + entry.getValue()
                        + " ms не больше порога выхода (" + exitThresholdMs + " ms) — игрок будет "
                        + "защищён постоянно.");
            }
        }

        if (potionLock && freezeMode == FreezeMode.TELEPORT) {
            warnings.add("freeze.potion-lock не работает с freeze.mode=TELEPORT: в этом режиме скорость "
                    + "не трогается (позиция удерживается телепортами).");
        }
        if (freezeMode == FreezeMode.FLY) {
            warnings.add("freeze.mode = FLY: игрок висит в воздухе через полёт. Это самый заметный режим, "
                    + "и античиты (в т.ч. на стороне клиента) могут пометить «Fly». Для «просто застыл, "
                    + "но бессмертен» используйте PASSIVE — он не выдаёт полёт и не похож на GM1.");
        }
        if (freezeMode == FreezeMode.PASSIVE) {
            warnings.add("freeze.mode = PASSIVE: скорость ходьбы обнуляется (это видно только по движению), "
                    + "игрок остаётся в своём GameMode. Так и должно быть по задумке — бессмертие даёт "
                    + "freeze.invulnerable-flag, а не креатив.");
        }
        if (!preventFlyingKick) {
            warnings.add("freeze.prevent-flying-kick = false: игрока, замороженного в воздухе "
                    + "(падение или элитры), сервер отключит через 4 секунды с сообщением "
                    + "«Flying is not enabled on this server» — он считает такое зависание полётом "
                    + "без права полёта. Включайте только осознанно.");
        }
        if (!keepAir) {
            warnings.add("freeze.keep-air = false: замороженный под водой игрок теряет воздух "
                    + "(пузырьки, звук захлёбывания), а после снятия защиты всплывает уже мёртвым. "
                    + "Урон при этом всё равно отменяется — но выглядит это как «защита убила».");
        }
        if (escapeSearchBlocks < 1 || escapeSearchBlocks > 64) {
            warnings.add("release.safety.escape-search-blocks = " + escapeSearchBlocks
                    + " вне разумных границ (1..64) — беру 8.");
            escapeSearchBlocks = 8;
        }
        if (!clearFreezeTicks) {
            warnings.add("freeze.clear-freeze-ticks = false: замороженный в порошковом снеге игрок "
                    + "продолжает замерзать, а после снятия защиты сразу получает урон от FREEZE.");
        }
        if (!escapeSuffocation) {
            warnings.add("release.safety.escape-suffocation = false: игрок, засыпанный песком или "
                    + "гравием, будет отпущен внутри блока и задохнётся сразу после снятия защиты.");
        }
        if (!keepGliding) {
            warnings.add("freeze.keep-gliding = false: при заморозке элитры сворачиваются. Игрок "
                    + "теряет глайд (после разморозки падает) и попадает под серверную проверку "
                    + "зависания — от неё спасает только freeze.prevent-flying-kick.");
        }
        if (freezeAllowVertical && teleportBack) {
            warnings.add("freeze.allow-vertical-movement = true: по вертикали якорь не удерживается, "
                    + "игрок будет медленно падать (урон от падения при снятии защиты гасится).");
        }
        if (teleportBack && teleportBackTolerance < 1.0D) {
            teleportBackTolerance = 1.0D;
            warnings.add("freeze.teleport-back.tolerance-blocks < 1.0 — установлено 1.0, иначе обычный "
                    + "лаг позиций будет вызывать ложные телепорты.");
        }
        if (blockIncoming && !incomingExceptions.isEmpty()) {
            warnings.add("damage.incoming.exceptions = " + incomingExceptions + " — этот урон проходит даже "
                    + "сквозь защиту. Оставьте VOID/WORLD_BORDER, чтобы никто не «завис» навсегда.");
        }
        if (blockIncoming && incomingExceptions.isEmpty()) {
            warnings.add("damage.incoming.exceptions пуст: урон от пустоты (VOID) тоже блокируется — "
                    + "рекомендуем добавить [VOID, WORLD_BORDER], иначе игрок может застрять в пустоте "
                    + "с бессмертием (до срабатывания max-protection-seconds).");
        }
        if (extendInDanger && extendInDangerMaxSeconds < 1) {
            extendInDangerMaxSeconds = 1;
            warnings.add("release.safety.extend-in-danger-max-seconds < 1 — установлено 1.");
        }
        if (extendInDanger && dangerBlocks.isEmpty()) {
            extendInDanger = false;
            warnings.add("release.safety.danger-blocks пуст — удержание в опасном месте отключено.");
        }
        if (combatPreventProtection) {
            warnings.add("combat.prevent-protection = true: во время боя защита НЕ включается "
                    + "даже при пинге 3000+ ms. Это античит-режим для PvP-серверов, но игроки будут "
                    + "умирать от лагов в бою — выберите осознанно.");
        }
        if (lagGuardEnabled && lagGuardRatio >= 1.0D) {
            lagGuardRatio = 0.9D;
            warnings.add("server-lag-guard.ratio должен быть < 1.0 — установлено 0.9.");
        }
        if (lagGuardRatio <= 0.0D) {
            lagGuardRatio = 0.75D;
            warnings.add("server-lag-guard.ratio <= 0 — установлено 0.75.");
        }
        if (lagGuardEnabled && lagGuardMinPlayers < 2) {
            lagGuardMinPlayers = 2;
            warnings.add("server-lag-guard.min-players < 2 — установлено 2: при одном игроке серверный "
                    + "лаг неотличим от лага клиента.");
        }
        if (lagGuardResumeCycles < 1) {
            lagGuardResumeCycles = 1;
            warnings.add("server-lag-guard.resume-cycles < 1 — установлено 1 (автозащита вернётся сразу).");
        }
        if (maxProtectionSeconds > 0 && maxProtectionSeconds < 10) {
            warnings.add("ping.max-protection-seconds = " + maxProtectionSeconds + " — очень мало, "
                    + "игрок не успеет дождаться нормального соединения.");
        }
        if (abuseMaxActivationsPerHour > 0 && maxProtectionSeconds == 0) {
            warnings.add("abuse.max-activations-per-hour работает, но ping.max-protection-seconds = 0 — "
                    + "одна защита может длиться бесконечно. Оставьте лимит времени.");
        }
        if (actionbarIntervalSeconds < 1) {
            actionbarIntervalSeconds = 1;
            warnings.add("messages.interval-seconds < 1 — установлено 1.");
        }
        if (indicator == Indicator.BOSS_BAR || indicator == Indicator.BOTH) {
            warnings.add("Используется BossBar: он перекрывает ванильные полосы боссов (Иссушитель, "
                    + "Эндер-дракон). Если у вас арены с боссами — используйте ACTIONBAR.");
        }
        if (!invulnerableFlag) {
            warnings.add("freeze.invulnerable-flag = false: неуязвимость обеспечивается только отменой "
                    + "событий урона. Работает, но урон от эффектов/огня и других плагинов может просочиться "
                    + "(игрок может умереть от урона, обходящего события). Рекомендуется true.");
        }
        if (blockTeleports) {
            warnings.add("interactions.block-teleports = true: замороженный игрок не может быть перемещён "
                    + "порталом, эндер-жемчугом или сторонним плагином. Это и нужно для «ничего не может», "
                    + "но если у вас есть режимы вроде арен с принудительным ТП — внесите их в disabled-worlds.");
        }
        if (blockCommands && allowedCommands.isEmpty()) {
            warnings.add("interactions.commands.block = true без allowed: замороженный игрок не сможет "
                    + "даже написать /msg или /help.");
        }
        if (cpRadius < 0) {
            cpRadius = 0;
            warnings.add("integrations.coreprotect.radius < 0 — установлено 0 (проверяется только блок под игроком).");
        }
        if (cpRadius > 8) {
            cpRadius = 8;
            warnings.add("integrations.coreprotect.radius > 8 — уменьшено до 8: область растёт как куб "
                    + "(8 -> 4913 блоков), а CoreProtect читает базу на каждый запрос.");
        }
        if (cpCheckOnRelease && cpRadius > 4) {
            warnings.add("integrations.coreprotect.radius = " + cpRadius + " — отчёт может выполняться "
                    + "заметно дольше. Для практических задач достаточно 3 (7x7x7).");
        }
        if (cpMarkerTag == null || cpMarkerTag.isBlank()) {
            cpMarkerTag = "PingShield";
            warnings.add("integrations.coreprotect.marker-tag пуст — установлено 'PingShield'.");
        } else if (cpMarkerTag.contains(",") || cpMarkerTag.contains(" ")) {
            cpMarkerTag = cpMarkerTag.replace(",", "").replace(" ", "");
            warnings.add("integrations.coreprotect.marker-tag содержал пробел или запятую — убрано. "
                    + "Запятая ломает фильтр f: в /co lookup (она разделяет несколько префиксов).");
        }
        if (cpMaxResults < 50) {
            cpMaxResults = 50;
            warnings.add("integrations.coreprotect.max-results < 50 — установлено 50.");
        }
        if (cpMinutesForManual < 1) {
            cpMinutesForManual = 1;
            warnings.add("integrations.coreprotect.manual-lookup-minutes < 1 — установлено 1.");
        }
        if (lpThresholdMetaKey == null || lpThresholdMetaKey.isBlank()) {
            lpThresholdMetaKey = "pingshield-threshold";
            warnings.add("integrations.luckperms.threshold-meta-key пуст — установлено 'pingshield-threshold'.");
        }
        if (lpModeMetaKey == null || lpModeMetaKey.isBlank()) {
            lpModeMetaKey = "pingshield-freeze-mode";
            warnings.add("integrations.luckperms.freeze-mode-meta-key пуст — установлено 'pingshield-freeze-mode'.");
        }
        if (lpCacheSeconds < 0) {
            lpCacheSeconds = 0;
            warnings.add("integrations.luckperms.cache-seconds < 0 — установлено 0 (читать при каждой проверке).");
        }
        if (lpCacheSeconds > 300) {
            warnings.add("integrations.luckperms.cache-seconds = " + lpCacheSeconds + ": долгий кэш, изменения прав "
                    + "будут применяться с задержкой до " + lpCacheSeconds + " с (событие LuckPerms всё равно "
                    + "сбрасывает кэш, но лучше не превышать 120).");
        }
        if (viaExtraThresholdMs < 0) {
            viaExtraThresholdMs = 0;
            warnings.add("integrations.via.extra-threshold-ms < 0 — установлено 0.");
        }
        if (viaEnabled && viaExtraThresholdMs > 1500) {
            warnings.add("integrations.via.extra-threshold-ms = " + viaExtraThresholdMs + " ms: транслируемые "
                    + "клиенты будут защищены слишком поздно. Разумно 300-800 ms.");
        }
        if (viaServerProtocol < 0) {
            viaServerProtocol = 0;
            warnings.add("integrations.via.server-protocol < 0 — установлено 0 (автоопределение).");
        }
        if (auditEnabled && (auditFileName == null || auditFileName.isBlank())) {
            auditFileName = "audit.log";
            warnings.add("logging.file пуст — используется audit.log.");
        }
        if (cfgKillsUnusedThresholds()) {
            warnings.add("Простаивающие настройки: thresholds.overrides заданы, но ping.enter-threshold-ms "
                    + "меньше всех из них — большинство игроков будет защищено раньше.");
        }
        return warnings;
    }

    private boolean releasesProtectionInstantlyAtLowPing() {
        return enterThresholdMs - exitThresholdMs < 400;
    }

    private boolean cfgKillsUnusedThresholds() {
        for (Integer value : thresholdOverrides.values()) {
            if (value == null || value < enterThresholdMs) {
                return false;
            }
        }
        return !thresholdOverrides.isEmpty();
    }

    // ------------------------------------------------------------------ удобные проверки

    public boolean isWorldProtected(String worldName) {
        return disabledWorlds.isEmpty() || !disabledWorlds.contains(worldName.toLowerCase(Locale.ROOT));
    }

    public boolean isIncomingException(DamageCause cause) {
        return incomingExceptions.contains(cause);
    }

    public boolean audit() {
        return auditEnabled;
    }

    // ------------------------------------------------------------------ парсеры

    private static Set<DamageCause> parseDamageCauses(List<String> raw) {
        Set<DamageCause> causes = EnumSet.noneOf(DamageCause.class);
        for (String entry : raw) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            try {
                causes.add(DamageCause.valueOf(entry.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // неизвестное значение — просто пропускаем
            }
        }
        return causes;
    }

    private static Set<Material> parseMaterials(List<String> raw) {
        Set<Material> materials = new LinkedHashSet<>();
        for (String entry : raw) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            Material material = Material.matchMaterial(entry.trim().toUpperCase(Locale.ROOT));
            if (material != null) {
                materials.add(material);
            }
        }
        return materials;
    }

    private static Set<PotionEffectType> parseEffects(List<String> raw) {
        Set<PotionEffectType> effects = new LinkedHashSet<>();
        for (String entry : raw) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            PotionEffectType type = resolveEffect(entry.trim().toUpperCase(Locale.ROOT));
            if (type != null) {
                effects.add(type);
            }
        }
        return effects;
    }

    /**
     * Имя эффекта → {@link PotionEffectType}. Сначала пробуем реестр (любое корректное имя),
     * затем — известные константы. Обе попытки в try/catch: вне запущенного сервера реестр
     * Bukkit не инициализируется, и конфиг не должен из-за этого «взрываться» — лучше
     * потерять один эффект, чем не запустить плагин.
     */
    private static PotionEffectType resolveEffect(String name) {
        try {
            PotionEffectType fromRegistry = org.bukkit.Registry.MOB_EFFECT
                    .get(org.bukkit.NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT)));
            if (fromRegistry != null) {
                return fromRegistry;
            }
        } catch (Throwable ignored) {
            // реестр недоступен — идём по константам
        }
        try {
            return switch (name) {
                case "SLOW_FALLING" -> PotionEffectType.SLOW_FALLING;
                case "FIRE_RESISTANCE" -> PotionEffectType.FIRE_RESISTANCE;
                case "RESISTANCE" -> PotionEffectType.RESISTANCE;
                case "WATER_BREATHING" -> PotionEffectType.WATER_BREATHING;
                case "INVISIBILITY" -> PotionEffectType.INVISIBILITY;
                case "REGENERATION" -> PotionEffectType.REGENERATION;
                case "SPEED" -> PotionEffectType.SPEED;
                default -> null;
            };
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Set<String> lowerSet(List<String> raw) {
        Set<String> set = new LinkedHashSet<>();
        for (String entry : raw) {
            if (entry != null && !entry.isBlank()) {
                set.add(entry.trim().toLowerCase(Locale.ROOT));
            }
        }
        return set;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String raw, E fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private static String str(FileConfiguration c, String path) {
        String value = c.getString(path, "");
        return value == null ? "" : value;
    }
}
