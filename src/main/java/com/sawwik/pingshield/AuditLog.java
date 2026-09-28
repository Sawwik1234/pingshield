package com.sawwik.pingshield;

import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;

/**
 * Простой журнал событий в файл {@code plugins/PingShield/audit.log}.
 *
 * <p>Запись выполняется в AsyncScheduler — на Folia это единственный безопасный способ
 * делать файловый ввод-вывод, не тормозя регион.</p>
 */
public final class AuditLog {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Plugin plugin;
    private final File file;
    private final boolean enabled;

    public AuditLog(Plugin plugin, boolean enabled, String fileName) {
        this.plugin = plugin;
        this.enabled = enabled;
        String name = (fileName == null || fileName.isBlank()) ? "audit.log" : fileName;
        this.file = new File(plugin.getDataFolder(), name);
    }

    public void write(String player, String action, String details) {
        if (!enabled) {
            return;
        }
        String line = "[" + LocalDateTime.now().format(FORMAT) + "] "
                + player + " | " + action + " | " + details + System.lineSeparator();
        try {
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> append(line));
        } catch (Throwable throwable) {
            // Плагин выключается — пишем синхронно в последний раз
            append(line);
        }
    }

    private void append(String line) {
        try {
            if (file.getParentFile() != null && !file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
                return;
            }
            Files.writeString(file.toPath(), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Не удалось записать в журнал " + file.getName(), ex);
        }
    }
}
