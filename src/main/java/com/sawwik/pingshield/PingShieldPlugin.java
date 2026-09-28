package com.sawwik.pingshield;

import io.papermc.paper.ServerBuildInfo;
import net.kyori.adventure.key.Key;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import com.sawwik.pingshield.listener.ProtectionListener;

import java.util.List;

/**
 * PingShield — защита игроков с очень высоким пингом (3000–5000 ms) на Paper / Purpur / Folia.
 *
 * <p>Пока пинг игрока выше порога, он заморожен (не двигается, не взаимодействует с миром,
 * не наносит урон), а весь входящий урон по нему отключён. Как только пинг вернулся в норму —
 * всё восстанавливается автоматически, с «мягкой посадкой» из защитных эффектов.</p>
 *
 * @author Sawwik
 */
public final class PingShieldPlugin extends JavaPlugin {

    private PingShieldConfig config;
    private ProtectionManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.config = new PingShieldConfig();
        this.config.load(getConfig());
        logWarnings(this.config.validate());

        this.manager = new ProtectionManager(this, this.config);

        getServer().getPluginManager().registerEvents(new ProtectionListener(this, this.manager), this);

        PluginCommand command = getCommand("pingshield");
        if (command != null) {
            PingShieldCommand executor = new PingShieldCommand(this, this.manager);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        } else {
            getLogger().warning("Команда /pingshield не зарегистрирована — проверьте plugin.yml");
        }

        this.manager.initIntegrations();
        this.manager.start();

        getLogger().info("PingShield v" + getPluginMeta().getVersion() + " включён | порог входа: "
                + this.config.enterThresholdMs + " ms, выхода: " + this.config.exitThresholdMs
                + " ms, проверка каждые " + this.config.checkIntervalTicks + " тик(ов) | заморозка: "
                + this.config.freezeMode + " | индикатор: " + this.config.indicator);
        if (!this.config.enabled) {
            getLogger().warning("Функция выключена в конфиге (enabled: false).");
        }
        if (this.manager.coreProtect().isAvailable()) {
            getLogger().info("Интеграция с CoreProtect активна: маркеры защиты пишутся в лог, "
                    + "при снятии защиты проверяются изменения блоков вокруг игрока.");
        }
        if (this.manager.via().isViaPresent()) {
            getLogger().info("Via: " + this.manager.via().statusLine());
        }
        if (isFolia()) {
            getLogger().info("Обнаружена Folia: цикл проверки в глобальном регионе, все действия с игроками "
                    + "выдаются в их регионы через EntityScheduler (Folia-safe).");
        }
    }

    @Override
    public void onDisable() {
        if (this.manager != null) {
            this.manager.stop();
        }
    }

    /** Перезагрузка конфига по {@code /pingshield reload}. */
    public void reloadEverything() {
        reloadConfig();
        config.load(getConfig());
        logWarnings(config.validate());
        manager.restart();
    }

    private void logWarnings(List<String> warnings) {
        for (String warning : warnings) {
            getLogger().warning(warning);
        }
    }

    public boolean isFolia() {
        try {
            return ServerBuildInfo.buildInfo().isBrandCompatible(Key.key("papermc", "folia"));
        } catch (Throwable throwable) {
            try {
                Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
                return true;
            } catch (ClassNotFoundException ignored) {
                return false;
            }
        }
    }

    public PingShieldConfig config() {
        return config;
    }

    public ProtectionManager manager() {
        return manager;
    }
}
