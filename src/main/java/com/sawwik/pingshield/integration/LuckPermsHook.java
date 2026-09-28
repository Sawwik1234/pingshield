package com.sawwik.pingshield.integration;

import com.sawwik.pingshield.Permissions;
import com.sawwik.pingshield.PingShieldConfig;
import com.sawwik.pingshield.PingShieldPlugin;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Интеграция с LuckPerms (soft-depend: без него всё работает на правах и конфиге).
 *
 * <h2>Что даёт связка</h2>
 * <ol>
 *   <li><b>Персональный порог через право:</b> {@code pingshield.threshold.<мс>} — например
 *       {@code /lp group mobile permission set pingshield.threshold.4000 true}.
 *       Значение извлекается из <b>эффективных</b> прав игрока (с учётом наследования и контекстов),
 *       поэтому работает и для групп, и для конкретных миров: один и тот же игрок может иметь
 *       разные пороги на арене и в обычном мире.</li>
 *   <li><b>Персональный порог через meta:</b> {@code pingshield-threshold=4000}. Удобно, когда
 *       значение хочется видеть в одном месте — {@code /lp user Steve meta set pingshield-threshold 4000}.
 *       Meta приоритетнее права.</li>
 *   <li><b>Персональный режим заморозки:</b> meta {@code pingshield-freeze-mode} со значениями
 *       {@code passive | teleport | fly | immunity_only | default}. Для донатов с плохим интернетом:
 *       {@code /lp user Steve meta set pingshield-freeze-mode immunity_only}.</li>
 *   <li><b>Мгновенная реакция на изменения:</b> подписка на {@link UserDataRecalculateEvent} —
 *       как только админ поменял права, кэш порога игрока сбрасывается, а не ждёт опроса.</li>
 * </ol>
 *
 * <h2>Порядок приоритетов</h2>
 * <pre>meta LuckPerms → право pingshield.threshold.&lt;мс&gt; → thresholds.overrides (конфиг) → общий порог</pre>
 * Если найдено несколько прав-порогов, берётся <b>самый строгий (наименьший)</b>: случайное
 * понижение защиты невозможно, только осознанное.
 *
 * <h2>Безопасность загрузки</h2>
 * Все обращения к LuckPerms изолированы в {@link Bridge}: если плагина нет, класс не загрузится,
 * а вызывающий код поймает {@link Throwable} и продолжит работу на правах Bukkit.
 */
public final class LuckPermsHook {

    /** Что просили для игрока. {@link #DEFAULT} = «как в конфиге». */
    public enum ModeOverride {
        DEFAULT, PASSIVE, TELEPORT, FLY, IMMUNITY_ONLY
    }

    /** Итог разрешения: порог и режим с указанием источника (для /pingshield perms). */
    public record Resolution(Integer threshold, ModeOverride mode, String source) {

        public static Resolution empty() {
            return new Resolution(null, ModeOverride.DEFAULT, "конфиг");
        }

        public String describeThreshold() {
            return threshold == null ? "по конфигу" : threshold + " ms (" + source + ")";
        }

        public String describeMode() {
            return mode == ModeOverride.DEFAULT ? "по конфигу" : mode.name().toLowerCase() + " (" + source + ")";
        }
    }

    private final PingShieldPlugin plugin;
    private final PingShieldConfig cfg;
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

    private volatile boolean available;
    private volatile String apiVersion = "—";
    private volatile Object subscription;

    private record Cached(Resolution resolution, long stamp) {
    }

    public LuckPermsHook(PingShieldPlugin plugin, PingShieldConfig cfg) {
        this.plugin = plugin;
        this.cfg = cfg;
    }

    // ================================================================== инициализация

    public void init() {
        cache.clear();
        available = false;
        subscription = null;

        if (!cfg.luckPermsEnabled) {
            return;
        }
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
            return;
        }
        try {
            Bridge.init(Bukkit.getPluginManager().getPlugin("LuckPerms"), () -> cache.clear());
            available = true;
            apiVersion = Bridge.apiVersion();
            plugin.getLogger().info("LuckPerms найден (API " + apiVersion + "): персональные пороги можно задавать "
                    + "правом " + Permissions.THRESHOLD_PREFIX + "<мс> или meta "
                    + cfg.lpThresholdMetaKey + "; режим заморозки — meta " + cfg.lpModeMetaKey
                    + ". Изменения прав применяются сразу (UserDataRecalculateEvent).");
        } catch (Throwable throwable) {
            available = false;
            plugin.getLogger().log(Level.FINE, "LuckPerms API недоступен — работаю на правах Bukkit", throwable);
        }
    }

    public boolean isAvailable() {
        return available;
    }

    public String apiVersion() {
        return apiVersion;
    }

    public void unregister() {
        cache.clear();
        if (subscription != null && available) {
            try {
                Bridge.unsubscribe(subscription);
            } catch (Throwable ignored) {
                // плагин выключается, LuckPerms мог выгрузиться первым
            }
            subscription = null;
        }
        available = false;
    }

    // ================================================================== разрешение значений

    /**
     * Разрешает порог и режим для игрока. Результат кэшируется на {@code luckperms.cache-seconds}
     * и сбрасывается событием LuckPerms при изменении прав.
     */
    public Resolution resolve(Player player) {
        if (!available || player == null) {
            return Resolution.empty();
        }
        long now = System.currentTimeMillis();
        Cached cached = cache.get(player.getUniqueId());
        if (cached != null && now - cached.stamp() < cfg.lpCacheSeconds * 1000L) {
            return cached.resolution();
        }
        Resolution resolution = Resolution.empty();
        try {
            UUID uuid = player.getUniqueId();
            // 1. meta — самое точное значение, приоритет выше прав
            Optional<Integer> metaThreshold = Bridge.readIntMeta(uuid, cfg.lpThresholdMetaKey);
            if (metaThreshold.isPresent() && metaThreshold.get() >= 100) {
                resolution = new Resolution(metaThreshold.get(), resolution.mode(),
                        "LuckPerms meta " + cfg.lpThresholdMetaKey + "=" + metaThreshold.get());
            } else {
                // 2. права pingshield.threshold.<мс> — берём самый строгий (наименьший)
                Integer fromNode = Bridge.readThresholdNode(uuid, Permissions.THRESHOLD_PREFIX);
                if (fromNode != null) {
                    resolution = new Resolution(fromNode, resolution.mode(),
                            "LuckPerms право " + Permissions.THRESHOLD_PREFIX + fromNode);
                }
            }
            // 3. режим заморозки из meta
            String mode = Bridge.readMeta(uuid, cfg.lpModeMetaKey);
            if (mode != null && !mode.isBlank()) {
                ModeOverride parsed = parseMode(mode);
                if (parsed != ModeOverride.DEFAULT) {
                    resolution = new Resolution(resolution.threshold(), parsed,
                            "LuckPerms meta " + cfg.lpModeMetaKey + "=" + mode);
                }
            }
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.FINE, "Не удалось прочитать данные LuckPerms для " + player.getName(),
                    throwable);
        }
        cache.put(player.getUniqueId(), new Cached(resolution, now));
        return resolution;
    }

    /** Сбросить кэш конкретного игрока (например, после /pingshield reload). */
    public void invalidate(UUID uuid) {
        cache.remove(uuid);
    }

    public void invalidateAll() {
        cache.clear();
    }

    private ModeOverride parseMode(String raw) {
        String value = raw.trim().toLowerCase(java.util.Locale.ROOT).replace('-', '_');
        return switch (value) {
            case "passive", "заморозка" -> ModeOverride.PASSIVE;
            case "teleport" -> ModeOverride.TELEPORT;
            case "fly", "полет", "полёт" -> ModeOverride.FLY;
            case "immunity_only", "immunity", "shield", "щит" -> ModeOverride.IMMUNITY_ONLY;
            default -> ModeOverride.DEFAULT;
        };
    }

    /** Диагностика для {@code /pingshield perms <игрок>}. */
    public String diagnostics(Player player) {
        if (!available) {
            return "LuckPerms не подключён" + (cfg.luckPermsEnabled ? "" : " (integrations.luckperms.enabled=false)");
        }
        Resolution resolution = resolve(player);
        StringBuilder builder = new StringBuilder();
        builder.append("API ").append(apiVersion);
        builder.append(" | первичная группа: ").append(group(player));
        builder.append(" | порог: ").append(resolution.describeThreshold());
        builder.append(" | режим: ").append(resolution.describeMode());
        return builder.toString();
    }

    public String group(Player player) {
        if (!available) {
            return "—";
        }
        try {
            return Bridge.primaryGroup(player.getUniqueId());
        } catch (Throwable throwable) {
            return "—";
        }
    }

    /**
     * Изолированный мост к LuckPerms. Класс загружается только при первом обращении,
     * поэтому сервер без LuckPerms не получит {@code NoClassDefFoundError}.
     */
    private static final class Bridge {

        private static LuckPerms api;
        private static Object eventSubscription;

        static void init(org.bukkit.plugin.Plugin plugin, Runnable invalidate) throws Throwable {
            api = LuckPermsProvider.get();
            // Изменения прав применяются мгновенно: сбрасываем кэш порогов
            eventSubscription = api.getEventBus().subscribe(plugin, UserDataRecalculateEvent.class,
                    event -> invalidate.run());
        }

        static void unsubscribe(Object subscription) throws Throwable {
            if (subscription instanceof net.luckperms.api.event.EventSubscription<?> typed) {
                typed.close();
            }
        }

        static String apiVersion() throws Throwable {
            return api.getPluginMetadata().getVersion();
        }

        static Optional<Integer> readIntMeta(UUID uuid, String key) throws Throwable {
            CachedMetaData metaData = metaData(uuid);
            if (metaData == null) {
                return Optional.empty();
            }
            return metaData.getMetaValue(key, Integer::parseInt);
        }

        static String readMeta(UUID uuid, String key) throws Throwable {
            CachedMetaData metaData = metaData(uuid);
            return metaData == null ? null : metaData.getMetaValue(key);
        }

        private static CachedMetaData metaData(UUID uuid) throws Throwable {
            User user = api.getUserManager().getUser(uuid);
            if (user == null) {
                return null; // игрок ещё не загружен в LuckPerms
            }
            QueryOptions queryOptions = user.getQueryOptions();
            return user.getCachedData().getMetaData(queryOptions);
        }

        /** Ищет эффективные права вида pingshield.threshold.&lt;мс&gt; и берёт самый строгий. */
        static Integer readThresholdNode(UUID uuid, String prefix) throws Throwable {
            User user = api.getUserManager().getUser(uuid);
            if (user == null) {
                return null;
            }
            Integer strictest = null;
            for (Node node : user.resolveInheritedNodes(user.getQueryOptions())) {
                if (!node.getValue()) {
                    continue;
                }
                String key = node.getKey();
                if (key == null || !key.startsWith(prefix)) {
                    continue;
                }
                String raw = key.substring(prefix.length());
                try {
                    int value = Integer.parseInt(raw.trim());
                    if (value < 100) {
                        continue;
                    }
                    if (strictest == null || value < strictest) {
                        strictest = value;
                    }
                } catch (NumberFormatException ignored) {
                    // право без числа — пропускаем
                }
            }
            return strictest;
        }

        static String primaryGroup(UUID uuid) throws Throwable {
            User user = api.getUserManager().getUser(uuid);
            return user == null ? "—" : user.getPrimaryGroup();
        }

        private Bridge() {
        }
    }
}
