package com.sawwik.pingshield.integration;

import com.sawwik.pingshield.PingShieldConfig;
import com.sawwik.pingshield.PingShieldPlugin;
import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Интеграция с CoreProtect — включая <b>встраивание поиска PingShield прямо в поиск CoreProtect</b>.
 *
 * <h2>Как события PingShield попадают в поиск CoreProtect</h2>
 * CoreProtect не позволяет добавить свой «тип данных» (схема базы фиксирована), но он умеет
 * фильтровать <b>текст сообщений по префиксу</b> — параметр {@code f:<filter>} у {@code /co lookup}.
 * Поэтому каждое событие пишется через {@code API#logChat} с устойчивым тегом в начале строки:
 *
 * <pre>
 *   [PingShield/START] защита от лагов ВКЛ: пинг 4210 ms (порог 3500 ms)
 *   [PingShield/END]   защита ВЫКЛ: PING_OK, длительность 42 с
 *   [PingShield/HOLD]  удержание: опасное место, осталось 9 с
 *   [PingShield/CP]    рядом меняли блоки: Alex (12), Steve (3)
 * </pre>
 *
 * И теперь всё ищется <b>родными командами CoreProtect</b>:
 *
 * <pre>
 *   /co lookup a:chat f:[PingShield] t:1h                  — все события PingShield за час
 *   /co lookup a:chat f:[PingShield/START] t:1d            — только входы в защиту
 *   /co lookup u:Steve a:chat f:[PingShield] t:6h          — события одного игрока
 *   /co lookup u:Steve t:30m r:5                           — что делали вокруг него (блоки)
 *   /co lookup a:chat f:[PingShield] t:2h #count           — только количество
 * </pre>
 *
 * Дополнительно (опция {@code log-interaction}) можно ставить в точке удержания запись
 * взаимодействия — тогда защищённые игроки видны и в географическом поиске {@code a:click}.
 *
 * <h2>Почему это безопасно</h2>
 * <ul>
 *   <li>Все запросы к базе идут через {@code AsyncScheduler} — тики сервера не блокируются.</li>
 *   <li>Используется публичный area-вызов {@code performLookup(...)} — он есть и в старых
 *       CoreProtect (22.x), и в актуальных (24.1), и не требует объектов {@code Block}
 *       (их нельзя читать из async-потока на Folia).</li>
 *   <li>Если CoreProtect отсутствует или его API выключен — интеграция молча отключается.</li>
 * </ul>
 */
public final class CoreProtectHook {

    /** Тип события PingShield — попадает в тег маркера и в фильтр {@code f:}. */
    public enum Kind {
        /** Защита включена. */
        START,
        /** Защита снята. */
        END,
        /** Щит без заморозки (хронически лагающий игрок). */
        IMMUNITY,
        /** Удержание: пинг в норме, но игрок в опасном месте. */
        HOLD,
        /** Вокруг защищённого игрока меняли блоки. */
        CP
    }

    /** Одно изменение, найденное в логах CoreProtect. */
    public record Change(long timestamp, String user, String action, String material) {

        public boolean isForeign(String selfName, Set<String> ignore) {
            if (user == null || user.isEmpty()) {
                return false;
            }
            if (user.startsWith("#")) {
                return false; // #tnt, #hopper, #explosion — источники без игрока
            }
            if (selfName != null && user.equalsIgnoreCase(selfName)) {
                return false;
            }
            for (String ignored : ignore) {
                if (user.equalsIgnoreCase(ignored)) {
                    return false;
                }
            }
            return true;
        }
    }

    /** Результат сканирования области вокруг игрока. */
    public record ScanResult(List<Change> changes, Map<String, Integer> byUser, boolean areaQuery) {

        public int total() {
            return changes.size();
        }

        public boolean isEmpty() {
            return changes.isEmpty();
        }
    }

    private static final int LEGACY_BREAK = 0;
    private static final int LEGACY_PLACE = 1;
    private static final int LEGACY_INTERACTION = 2;

    private final PingShieldPlugin plugin;
    private final PingShieldConfig cfg;

    private volatile CoreProtectAPI api;
    private volatile boolean available;
    private volatile boolean scanWarned;

    public CoreProtectHook(PingShieldPlugin plugin, PingShieldConfig cfg) {
        this.plugin = plugin;
        this.cfg = cfg;
    }

    public void init() {
        available = false;
        api = null;
        scanWarned = false;

        Plugin coreProtect = Bukkit.getPluginManager().getPlugin("CoreProtect");
        if (coreProtect == null) {
            return;
        }
        try {
            CoreProtectAPI candidate = ((CoreProtect) coreProtect).getAPI();
            if (candidate == null || !candidate.isEnabled()) {
                plugin.getLogger().warning("CoreProtect установлен, но его API выключен "
                        + "(config.yml CoreProtect → api.enabled). Интеграция не активна.");
                return;
            }
            api = candidate;
            available = true;
            plugin.getLogger().info("CoreProtect найден: API v" + candidate.APIVersion()
                    + ". События PingShield пишутся маркерами " + markerTag()
                    + " — их можно искать родной командой: /co lookup a:chat f:" + filterPrefix() + " t:1h");
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.WARNING, "Не удалось подключиться к API CoreProtect", throwable);
        }
    }

    public boolean isAvailable() {
        return available && api != null;
    }

    // ================================================================== маркеры

    /** Короткий тег (без вида события): {@code [PingShield]}. */
    private String markerTag() {
        return filterPrefix() + "]";
    }

    /**
     * Пишет маркер события в лог CoreProtect. Вызывать в регионе игрока.
     * Именно эти строки делают события PingShield доступными в
     * {@code /co lookup a:chat f:[PingShield] ...}.
     */
    public void logMarker(Player player, Kind kind, String text) {
        if (!isAvailable() || !cfg.cpMarkers || player == null) {
            return;
        }
        try {
            api.logChat(player, markerTag(kind) + " " + text);
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.FINE, "Не удалось записать маркер в CoreProtect", throwable);
        }
    }

    /**
     * Маркер в точке удержания (географически ищется через {@code a:click r:N}).
     * Требует {@code log-interaction: true}: каждая запись — строка в базе CoreProtect.
     */
    public void logInteraction(String user, Location location) {
        if (!isAvailable() || !cfg.cpLogInteraction || user == null || location == null) {
            return;
        }
        try {
            api.logInteraction(user, location);
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.FINE, "Не удалось записать взаимодействие в CoreProtect", throwable);
        }
    }

    /** Тег маркера без вида события: {@code [PingShield}. Нужен для фильтра f: «все события». */
    public String filterPrefix() {
        return LookupCommands.filterPrefix(cfg.cpMarkerTag);
    }

    /** Полный тег конкретного события: {@code [PingShield/START]}. */
    public String markerTag(Kind kind) {
        return LookupCommands.markerTag(cfg.cpMarkerTag, kind.name());
    }

    /** Все команды поиска. Формирование вынесено в {@link LookupCommands} — он покрыт тестами. */
    public String chatSearch(String user, int minutes, Kind kind) {
        return LookupCommands.chatKind(cfg.cpMarkerTag, user, minutes, kind.name());
    }

    public String chatSearchAll(int minutes) {
        return LookupCommands.chatAll(cfg.cpMarkerTag, minutes);
    }

    public String chatSearchPlayer(String user, int minutes) {
        return LookupCommands.chatPlayer(cfg.cpMarkerTag, user, minutes);
    }

    public String blockSearch(String user, int minutes, int radius) {
        return LookupCommands.blocks(user, minutes, radius);
    }

    public String areaSearch(int minutes, int radius) {
        return LookupCommands.area(minutes, radius);
    }

    public String clickSearch(int minutes, int radius) {
        return LookupCommands.clicks(minutes, radius);
    }

    public String containerSearch(String user, int minutes, int radius) {
        return LookupCommands.containers(user, minutes, radius);
    }

    public String countSuffix(String command) {
        return LookupCommands.count(command);
    }



    // ================================================================== сканирование области

    /** Читает изменения блоков вокруг точки за {@code seconds}. Асинхронно. */
    public void scanAsync(Location center, int radius, int seconds, Consumer<ScanResult> callback) {
        if (!isAvailable() || center == null || center.getWorld() == null) {
            return;
        }
        final int safeRadius = Math.max(0, Math.min(16, radius));
        final int safeSeconds = Math.max(1, seconds);
        final Location snapshot = center.clone();
        final int limit = Math.max(50, cfg.cpMaxResults);
        try {
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
                ScanResult result = scanSync(snapshot, safeRadius, safeSeconds, limit);
                if (result != null && callback != null) {
                    callback.accept(result);
                }
            });
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.FINE, "Не удалось запустить сканирование CoreProtect", throwable);
        }
    }

    private ScanResult scanSync(Location center, int radius, int seconds, int limit) {
        List<Change> changes = areaScan(center, radius, seconds);
        if (changes == null) {
            return null;
        }
        changes.sort(Comparator.comparingLong(Change::timestamp));
        if (changes.size() > limit) {
            changes = new ArrayList<>(changes.subList(changes.size() - limit, changes.size()));
        }

        Map<String, Integer> byUser = new LinkedHashMap<>();
        for (Change change : changes) {
            byUser.merge(change.user() == null ? "?" : change.user(), 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(byUser.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        Map<String, Integer> sorted = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : entries) {
            sorted.put(entry.getKey(), entry.getValue());
        }
        return new ScanResult(changes, sorted, true);
    }

    /**
     * Один запрос по области: {@code performLookup(time, users, excludeUsers, blocks, excludeBlocks,
     * actionTypes, radius, location)}. Разбор — через публичный {@link CoreProtectAPI.ParseResult}.
     */
    private List<Change> areaScan(Location center, int radius, int seconds) {
        try {
            List<Integer> actions = List.of(LEGACY_BREAK, LEGACY_PLACE, LEGACY_INTERACTION);
            List<String[]> rows = api.performLookup(seconds, null, null, null, null, actions, radius, center);
            if (rows == null) {
                return List.of();
            }
            List<Change> changes = new ArrayList<>(rows.size());
            for (String[] row : rows) {
                CoreProtectAPI.ParseResult parsed = new CoreProtectAPI.ParseResult(row);
                changes.add(new Change(
                        parsed.getTimestamp(),
                        parsed.getPlayer(),
                        parsed.getActionString(),
                        String.valueOf(parsed.getType())));
            }
            return changes;
        } catch (Throwable throwable) {
            if (!scanWarned) {
                scanWarned = true;
                plugin.getLogger().log(Level.WARNING, "Не удалось прочитать логи CoreProtect: "
                        + throwable.getClass().getSimpleName() + ". Проверка изменений вокруг игрока "
                        + "отключена, маркеры и встроенный поиск продолжают работать.", throwable);
            }
            return null;
        }
    }

    // ================================================================== сводки

    public String summaryText(ScanResult result, String selfName) {
        if (result == null || result.isEmpty()) {
            return "изменений блоков вокруг не найдено";
        }
        StringBuilder builder = new StringBuilder();
        int shown = 0;
        for (Map.Entry<String, Integer> entry : result.byUser().entrySet()) {
            if (shown++ > 0) {
                builder.append(", ");
            }
            builder.append(entry.getKey()).append(" — ").append(entry.getValue());
            if (shown >= 5) {
                builder.append(", …");
                break;
            }
        }
        return "всего изменений: " + result.total() + " (" + builder + ")";
    }

    public List<String> foreignUsers(ScanResult result, String selfName) {
        if (result == null || result.isEmpty()) {
            return List.of();
        }
        Set<String> ignore = cfg.cpIgnorePlayers;
        List<String> users = new ArrayList<>();
        for (Change change : result.changes()) {
            if (change.isForeign(selfName, ignore)) {
                String user = change.user();
                boolean known = users.stream().anyMatch(existing -> existing.equalsIgnoreCase(user));
                if (!known) {
                    users.add(user);
                }
            }
        }
        return users;
    }

    /** Текст «Alex (12), Steve (3)» — кому что принадлежит. */
    public String describeForeign(ScanResult result, String selfName) {
        Map<String, Integer> foreign = new LinkedHashMap<>();
        Set<String> ignore = cfg.cpIgnorePlayers;
        for (Change change : result.changes()) {
            if (change.isForeign(selfName, ignore)) {
                foreign.merge(change.user(), 1, Integer::sum);
            }
        }
        StringBuilder builder = new StringBuilder();
        int shown = 0;
        for (Map.Entry<String, Integer> entry : foreign.entrySet()) {
            if (shown++ > 0) {
                builder.append(", ");
            }
            builder.append(entry.getKey()).append(" (").append(entry.getValue()).append(")");
            if (shown >= 5) {
                break;
            }
        }
        return builder.toString();
    }

    public String actionOf(Change change) {
        String action = change.action() == null ? "" : change.action().toLowerCase(Locale.ROOT);
        return switch (action) {
            case "break", "block break" -> "сломал";
            case "place", "block place" -> "поставил";
            case "interaction", "click" -> "нажал";
            default -> action.isEmpty() ? "изменил" : action;
        };
    }

    /** Совместимое имя: раньше метод назывался lookupHint. */
    public String lookupHint(String user, int minutes, int radius) {
        return LookupCommands.display(blockSearch(user, minutes, radius));
    }
}
