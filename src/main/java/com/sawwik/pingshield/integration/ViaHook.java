package com.sawwik.pingshield.integration;

import com.sawwik.pingshield.PingShieldConfig;
import com.sawwik.pingshield.PingShieldPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Осведомлённость о ViaVersion (и ViaBackwards).
 *
 * <h2>Что важно понимать про пинг под Via</h2>
 * <ul>
 *   <li>На ядре 26.1.2 клиенты <b>1.21.4 и ниже</b> — это <b>старые</b> клиенты на новом сервере:
 *       их пускает <b>ViaBackwards</b> (ViaVersion — только «новый клиент на старый сервер»,
 *       то есть 26.2/26.3 клиент на 26.1.2).</li>
 *   <li>{@code Player#getPing()} — это RTT keep-alive пакетов данного соединения. Via его
 *       <b>не искажает</b>: значение остаётся реальным пингом до клиента, поэтому пороги
 *       и вся логика PingShield работают корректно.</li>
 *   <li>Но трансляция протокола добавляет серверу работы и повышает <b>джиттер</b> у старых
 *       клиентов: у них чаще встречаются «пилообразные» скачки. Поэтому для клиентов,
 *       протокол которых отличается от серверного, порог по умолчанию поднимается
 *       (см. {@code integrations.via.extra-threshold-ms}).</li>
 *   <li>Версия клиента читается из публичного Paper-интерфейса {@code NetworkClient}
 *       ({@code player.getProtocolVersion()}) — без NMS и без рефлексии в сторону ViaAPI.</li>
 * </ul>
 */
public final class ViaHook {

    private final PingShieldPlugin plugin;
    private final PingShieldConfig cfg;

    private volatile boolean viaPresent;
    private volatile boolean viaBackwardsPresent;
    private volatile int serverProtocol = -1;
    private final Map<Integer, String> versionNames = new LinkedHashMap<>();

    public ViaHook(PingShieldPlugin plugin, PingShieldConfig cfg) {
        this.plugin = plugin;
        this.cfg = cfg;
    }

    public void init() {
        versionNames.clear();
        versionNames.putAll(cfg.viaVersionNames);

        viaPresent = Bukkit.getPluginManager().getPlugin("ViaVersion") != null;
        viaBackwardsPresent = Bukkit.getPluginManager().getPlugin("ViaBackwards") != null;
        serverProtocol = resolveServerProtocol();

        if (!viaPresent) {
            return;
        }

        StringBuilder message = new StringBuilder("Обнаружен ViaVersion");
        if (viaBackwardsPresent) {
            message.append(" + ViaBackwards");
        }
        if (cfg.viaEnabled) {
            message.append(". Клиенты с протоколом, отличным от серверного (").append(serverProtocol <= 0 ? "?" : serverProtocol)
                    .append("), получают надбавку к порогу +").append(cfg.viaExtraThresholdMs).append(" ms: ")
                    .append("трансляция протокола повышает джиттер, и такие игроки чаще ловят скачки пинга.");
        }
        plugin.getLogger().info(message.toString());

        if (!viaBackwardsPresent) {
            plugin.getLogger().info("Подсказка: на ядре " + Bukkit.getMinecraftVersion() + " клиенты старее сервера "
                    + "(1.21.x и ниже) подключаются только с ViaBackwards. ViaVersion один пускает лишь более "
                    + "новые клиенты, чем ядро.");
        }
    }

    /**
     * Серверный протокол нужен, чтобы отличить «нативный» клиент от транслируемого.
     * Порядок: значение из конфига → публичный (хоть и помеченный deprecated) {@code UnsafeValues} → неизвестно.
     */
    private int resolveServerProtocol() {
        if (cfg.viaServerProtocol > 0) {
            return cfg.viaServerProtocol;
        }
        try {
            @SuppressWarnings("deprecation")
            int protocol = Bukkit.getUnsafe().getProtocolVersion();
            return protocol > 0 ? protocol : -1;
        } catch (Throwable throwable) {
            return -1;
        }
    }

    /**
     * Протокол игрока. Вызывать в регионе игрока (событие входа) — значение читается один раз
     * и хранится в состоянии пинга, чтобы не трогать соединение из глобального потока.
     */
    public int protocolOf(Player player) {
        try {
            return player.getProtocolVersion();
        } catch (Throwable throwable) {
            return -1;
        }
    }

    /** Клиент старше/новее сервера, то есть его пакеты транслирует Via. */
    public boolean isTranslated(int protocol) {
        return protocol > 0 && serverProtocol > 0 && protocol != serverProtocol;
    }

    /** Надбавка к порогу входа для транслируемых клиентов (мс). */
    public int thresholdBonus(int protocol) {
        if (!cfg.viaEnabled || !isTranslated(protocol)) {
            return 0;
        }
        return Math.max(0, cfg.viaExtraThresholdMs);
    }

    /** Дополнительная надбавка к «jitter-порогу»: у транслируемых клиентов шум выше. */
    public int jitterBonus(int protocol) {
        if (!cfg.viaEnabled || !isTranslated(protocol)) {
            return 0;
        }
        return Math.max(0, cfg.viaExtraJitterBonusMs);
    }

    /** Человекочитаемое имя протокола: из конфига или просто номер. */
    public String describe(int protocol) {
        if (protocol <= 0) {
            return "неизвестно";
        }
        String name = versionNames.get(protocol);
        String base = name != null ? name : ("протокол " + protocol);
        if (serverProtocol > 0 && protocol == serverProtocol) {
            return base + " (нативный)";
        }
        return serverProtocol > 0 ? base + " (через Via)" : base;
    }

    public boolean isViaPresent() {
        return viaPresent;
    }

    public boolean isViaBackwardsPresent() {
        return viaBackwardsPresent;
    }

    public int getServerProtocol() {
        return serverProtocol;
    }

    /** Сводка по протоколам онлайна — для {@code /pingshield via}. */
    public Map<String, Integer> onlineSummary(Collection<? extends Player> online) {
        Map<String, Integer> summary = new LinkedHashMap<>();
        for (Player player : online) {
            int protocol = player.getProtocolVersion();
            String key = describe(protocol) + " [" + protocol + "]";
            summary.merge(key, 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(summary.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        Map<String, Integer> sorted = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : entries) {
            sorted.put(entry.getKey(), entry.getValue());
        }
        return sorted;
    }

    /** Сколько игроков сейчас сидит через трансляцию протокола. */
    public int translatedCount(Collection<? extends Player> online) {
        int count = 0;
        for (Player player : online) {
            if (isTranslated(player.getProtocolVersion())) {
                count++;
            }
        }
        return count;
    }

    /** Короткая строка для /pingshield info. */
    public String statusLine() {
        if (!viaPresent) {
            return "не установлен";
        }
        String translated = serverProtocol <= 0 ? "?" : String.valueOf(translatedCount(Bukkit.getOnlinePlayers()));
        return "установлен" + (viaBackwardsPresent ? " (+ViaBackwards)" : "") + ", протокол сервера "
                + (serverProtocol <= 0 ? "неизвестен" : serverProtocol) + ", транслируемых игроков: " + translated;
    }

    /** Русское название состояния для логов/сообщений. */
    public String localizedProtocol(int protocol) {
        return describe(protocol).toLowerCase(Locale.ROOT);
    }
}
