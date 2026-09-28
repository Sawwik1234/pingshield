package com.sawwik.pingshield;

import com.sawwik.pingshield.integration.CoreProtectHook;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /pingshield <reload|status|check|protect|unprotect|info|help>}.
 *
 * <p>На Folia команда игрока выполняется в его регионе, поэтому ответ отправителю
 * можно слать напрямую; сообщения другим игрокам идут через их EntityScheduler
 * (см. {@link ProtectionManager#runOn(Player, Runnable)}).</p>
 */
public final class PingShieldCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS =
            List.of("reload", "status", "check", "protect", "unprotect", "info", "profile", "perf",
                    "cp", "search", "perms", "via", "help");

    private final PingShieldPlugin plugin;
    private final ProtectionManager manager;

    public PingShieldCommand(PingShieldPlugin plugin, ProtectionManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        PingShieldConfig cfg = plugin.config();

        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("help")) {
            // подсказку видят все, у кого есть хоть какое-то право PingShield
            if (!has(sender, "help") && !hasAny(sender)) {
                Msg.send(sender, cfg.cmdNoPermission);
                return true;
            }
            Msg.send(sender, cfg.cmdUsage);
            return true;
        }
        if (!has(sender, sub)) {
            Msg.send(sender, cfg.cmdNoPermission);
            return true;
        }

        switch (sub) {
            case "reload" -> {
                plugin.reloadEverything();
                Msg.send(sender, cfg.cmdReloaded,
                        "enter", cfg.enterThresholdMs,
                        "exit", cfg.exitThresholdMs,
                        "interval", cfg.checkIntervalTicks);
            }
            case "status" -> sendStatus(sender);
            case "info" -> sendInfo(sender);
            case "perf" -> sendPerf(sender);
            case "via" -> sendVia(sender);
            case "perms", "permissions" -> {
                Player target = resolveTarget(sender, args, cfg);
                if (target != null) {
                    sendPerms(sender, target);
                }
            }
            case "search" -> {
                int minutes = args.length >= 2 ? parseInt(args[1], cfg.cpMinutesForManual) : cfg.cpMinutesForManual;
                int radius = args.length >= 3 ? parseInt(args[2], cfg.cpRadius) : cfg.cpRadius;
                sendSearch(sender, minutes, radius);
            }
            case "cp", "coreprotect" -> {
                Player target = resolveTarget(sender, args, cfg);
                if (target != null) {
                    int minutes = args.length >= 3 ? parseInt(args[2], cfg.cpMinutesForManual) : cfg.cpMinutesForManual;
                    int radius = args.length >= 4 ? parseInt(args[3], cfg.cpRadius) : cfg.cpRadius;
                    sendCoreProtect(sender, target, minutes, radius);
                }
            }
            case "profile" -> {
                Player target = resolveTarget(sender, args, cfg);
                if (target != null) {
                    sendProfile(sender, target);
                }
            }
            case "check" -> {
                Player target = resolveTarget(sender, args, cfg);
                if (target == null) {
                    return true;
                }
                int[] limits = manager.effectiveThresholds(target);
                Protection protection = manager.get(target);
                if (protection != null) {
                    Msg.send(sender, cfg.cmdCheckProtected,
                            "player", target.getName(),
                            "ping", manager.pingOf(target),
                            "elapsed", protection.getElapsedSeconds(),
                            "reason", protection.isManual() ? "вручную" : "авто",
                            "enter", protection.getThresholdUsed(),
                            "peak", protection.getPeakPing());
                } else {
                    Msg.send(sender, cfg.cmdCheckClear,
                            "player", target.getName(),
                            "ping", manager.pingOf(target),
                            "enter", limits[0],
                            "exit", limits[1],
                            "combat", manager.isInCombat(target) ? "да" : "нет",
                            "cooldown", manager.isCooldown(target) ? "да" : "нет");
                }
            }
            case "protect" -> {
                Player target = resolveTarget(sender, args, cfg);
                if (target == null) {
                    return true;
                }
                if (manager.beginProtection(target, Protection.Reason.MANUAL, System.currentTimeMillis())) {
                    Msg.send(sender, cfg.cmdManualProtect, "player", target.getName());
                } else {
                    Msg.send(sender, cfg.cmdPlayerNotProtected, "player", target.getName());
                }
            }
            case "unprotect" -> {
                Player target = resolveTarget(sender, args, cfg);
                if (target == null) {
                    return true;
                }
                if (manager.endManually(target)) {
                    Msg.send(sender, cfg.cmdManualUnprotect, "player", target.getName());
                } else {
                    Msg.send(sender, cfg.cmdPlayerNotProtected, "player", target.getName());
                }
            }
            default -> Msg.send(sender, cfg.cmdUsage);
        }
        return true;
    }

    /**
     * Проверка права подкоманды с учётом «зонтика» {@code pingshield.admin}.
     * В LuckPerms можно выдать как общий {@code pingshield.admin}, так и точечное
     * {@code pingshield.command.status}.
     */
    /** Есть ли у отправителя хоть одно право PingShield (для /pingshield help). */
    private boolean hasAny(CommandSender sender) {
        if (sender.hasPermission(Permissions.ADMIN) || sender.hasPermission(Permissions.NOTIFY)
                || sender.hasPermission(Permissions.BYPASS) || sender.hasPermission(Permissions.COREPROTECT)) {
            return true;
        }
        for (String node : List.of("reload", "status", "check", "protect", "unprotect", "info",
                "profile", "perf", "cp", "search", "perms", "via")) {
            if (sender.hasPermission(Permissions.command(node))) {
                return true;
            }
        }
        return false;
    }

    private boolean has(CommandSender sender, String subcommand) {
        if (sender.hasPermission(Permissions.ADMIN)) {
            return true;
        }
        if (sender.hasPermission(Permissions.command(subcommand))) {
            return true;
        }
        if (subcommand.equals("help")) {
            return hasAny(sender);
        }
        // алиасы: coreprotect -> cp, permissions -> perms, check-all
        return switch (subcommand) {
            case "coreprotect" -> sender.hasPermission(Permissions.command("cp"));
            case "permissions" -> sender.hasPermission(Permissions.command("perms"));
            default -> false;
        };
    }

    /**
     * {@code /pingshield perms <игрок>} — что реально действует для игрока и откуда взято.
     * Помогает разбирать вопросы «почему меня заморозило раньше/позже, чем остальных».
     */
    private void sendPerms(CommandSender sender, Player target) {
        PingShieldConfig cfg = plugin.config();
        com.sawwik.pingshield.integration.LuckPermsHook lp = manager.luckPerms();
        com.sawwik.pingshield.integration.LuckPermsHook.Resolution resolution = lp.resolve(target);
        int[] limits = manager.effectiveThresholds(target);

        Msg.send(sender, cfg.cmdPermsHeader,
                "player", target.getName(),
                "threshold", limits[0],
                "exit", limits[1],
                "source", resolution.describeThreshold(),
                "mode", resolution.describeMode());
        if (lp.isAvailable()) {
            Msg.send(sender, cfg.cmdPermsLuckPerms, "details", lp.diagnostics(target));
        } else {
            Msg.send(sender, cfg.cmdPermsUnavailable);
        }
        // ключевые права наглядно
        for (String node : List.of(Permissions.ADMIN, Permissions.BYPASS, Permissions.NOTIFY,
                Permissions.command("status"), Permissions.THRESHOLD_PREFIX + Math.max(0, limits[0]))) {
            Msg.send(sender, cfg.cmdPermsNode,
                    "node", node,
                    "value", target.hasPermission(node) ? "да" : "нет");
        }
    }

    private void sendStatus(CommandSender sender) {
        PingShieldConfig cfg = plugin.config();
        List<Protection> protections = manager.snapshot();
        Msg.send(sender, cfg.cmdStatusHeader,
                "count", protections.size(),
                "enter", cfg.enterThresholdMs,
                "exit", cfg.exitThresholdMs,
                "interval", cfg.checkIntervalTicks,
                "lag", manager.isLagSuspended() ? "да" : "нет");
        if (protections.isEmpty()) {
            Msg.send(sender, cfg.cmdStatusEmpty);
            return;
        }
        for (Protection protection : protections) {
            Player player = Bukkit.getPlayer(protection.getUuid());
            Msg.send(sender, cfg.cmdStatusEntry,
                    "player", protection.getName(),
                    "ping", protection.getLastPing(),
                    "peak", protection.getPeakPing(),
                    "elapsed", protection.getElapsedSeconds(),
                    "reason", protection.isManual() ? "вручную" : "авто",
                    "world", player != null ? player.getWorld().getName() : "-");
        }
    }

    private void sendInfo(CommandSender sender) {
        PingShieldConfig cfg = plugin.config();
        Msg.send(sender, cfg.cmdInfo,
                "version", plugin.getPluginMeta().getVersion(),
                "enabled", cfg.enabled ? "да" : "нет",
                "enter", cfg.enterThresholdMs,
                "exit", cfg.exitThresholdMs,
                "interval", cfg.checkIntervalTicks,
                "max", cfg.maxProtectionSeconds,
                "grace", cfg.joinGraceSeconds,
                "freeze", cfg.freezeMode.name(),
                "indicator", cfg.indicator.name(),
                "proxy", manager.proxyDetected() ? "обнаружен" : "нет (или ещё не определён)",
                "proxyMode", cfg.proxyMode.name(),
                "audit", cfg.auditEnabled ? "вкл" : "выкл",
                "coreprotect", manager.coreProtect().isAvailable() ? "подключён" : "нет",
                "via", manager.via().statusLine(),
                "folia", plugin.isFolia() ? "да" : "нет");
        if (manager.proxyDetected()) {
            Msg.send(sender, cfg.cmdProxyWarning);
        }
    }

    /**
     * Диагностика конкретного игрока: сырой пинг, сглаженный EWMA, jitter, число активаций
     * за час. По этим цифрам видно, «плохой провайдер» у игрока или разовая просадка.
     */
    private void sendProfile(CommandSender sender, Player target) {
        PingShieldConfig cfg = plugin.config();
        PingState state = manager.state(target.getUniqueId());
        if (state == null) {
            Msg.send(sender, cfg.cmdPlayerNotFound, "player", target.getName());
            return;
        }
        int hoursActivations = state.activationsLastHour(System.currentTimeMillis());
        Protection protection = manager.get(target);
        Msg.send(sender, cfg.cmdProfile,
                "player", target.getName(),
                "ping", state.raw,
                "ewma", (long) state.ewma,
                "jitter", (long) state.jitter,
                "enter", state.enter > 0 ? state.enter : cfg.enterThresholdMs,
                "exit", state.exit > 0 ? state.exit : cfg.exitThresholdMs,
                "activations", hoursActivations + "/" + cfg.abuseMaxActivationsPerHour,
                "above", state.aboveCount,
                "below", state.belowCount,
                "combat", manager.isInCombat(target) ? "да" : "нет",
                "cooldown", manager.isCooldown(target) ? "да" : "нет",
                "protect", protection == null ? "нет"
                        : (protection.isImmunityOnly() ? "щит без заморозки" : "заморожен"));
    }

    /**
     * Метрики стоимости: измеренное время цикла проверки и синтетический замер математики
     * на одного игрока. Нужны, чтобы обсуждать нагрузку цифрами.
     */
    private void sendPerf(CommandSender sender) {
        PingShieldConfig cfg = plugin.config();
        double benchNanos = manager.benchmarkSampleNanos(200_000);
        Runtime runtime = Runtime.getRuntime();
        long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L);
        double tps;
        try {
            @SuppressWarnings("deprecation")
            double[] tpsArray = Bukkit.getServer().getTPS();
            tps = (tpsArray != null && tpsArray.length > 0) ? tpsArray[0] : 20.0D;
        } catch (Throwable throwable) {
            tps = 20.0D;
        }
        Msg.send(sender, cfg.cmdPerf,
                "i", cfg.checkIntervalTicks,
                "enforce", cfg.enforceIntervalTicks,
                "avg_us", String.format(java.util.Locale.ROOT, "%.1f", manager.avgTickNanos() / 1000.0D),
                "max_us", String.format(java.util.Locale.ROOT, "%.1f", manager.maxTickNanos() / 1000.0D),
                "scanned", manager.lastScanned(),
                "tpp", manager.lastScanned() == 0 ? "—"
                        : String.format(java.util.Locale.ROOT, "%.0f",
                                manager.avgTickNanos() / Math.max(1, manager.lastScanned())),
                "bench", String.format(java.util.Locale.ROOT, "%.1f", benchNanos),
                "protect", manager.protectedCount(),
                "ticks", manager.totalTicks(),
                "heap", usedMb,
                "tps", String.format(java.util.Locale.ROOT, "%.1f", tps),
                "lag", manager.isLagSuspended() ? "да" : "нет");
    }

    /**
     * Отчёт CoreProtect по игроку: что происходило вокруг него за последние минуты.
     * Запрос идёт асинхронно, поэтому ответ отправляется уже из потока AsyncScheduler —
     * для игрока-отправителя возвращаемся в его регион через EntityScheduler.
     */
    private void sendCoreProtect(CommandSender sender, Player target, int minutes, int radius) {
        PingShieldConfig cfg = plugin.config();
        if (!manager.coreProtect().isAvailable()) {
            Msg.send(sender, cfg.cmdCoreProtectUnavailable);
            return;
        }
        Location center = target.getLocation();
        int seconds = Math.max(60, minutes * 60);
        manager.coreProtect().scanAsync(center, radius, seconds, result -> {
            List<String> foreign = manager.coreProtect().foreignUsers(result, target.getName());
            String summary = manager.coreProtect().summaryText(result, target.getName());
            String hint = manager.coreProtect().lookupHint(
                    foreign.isEmpty() ? target.getName() : foreign.get(0), minutes, radius);
            deliver(sender, () -> {
                Msg.send(sender, cfg.cmdCpHeader,
                        "player", target.getName(),
                        "minutes", minutes,
                        "radius", radius,
                        "total", result == null ? 0 : result.total(),
                        "type", result != null && result.areaQuery() ? "запрос по области" : "по блокам");
                if (result == null || result.isEmpty()) {
                    Msg.send(sender, cfg.cmdCpEmpty);
                } else {
                    result.byUser().forEach((user, count) -> Msg.send(sender, cfg.cmdCpEntry,
                            "user", user,
                            "count", count,
                            "player", target.getName()));
                }
                Msg.send(sender, cfg.cmdCpHint, "hint", hint);
                sendLookupButtons(sender, target.getName(), minutes, radius,
                        manager.coreProtect().chatSearchPlayer(target.getName(), minutes));
            });
        });
    }

    /**
     * Ряд кнопок, каждая запускает <b>нативный</b> поиск CoreProtect ({@code /co lookup ...}).
     * Так поиск событий PingShield оказывается внутри привычного админам инструмента.
     */
    private void sendLookupButtons(CommandSender sender, String playerName, int minutes, int radius, String primary) {
        PingShieldConfig cfg = plugin.config();
        CoreProtectHook cp = manager.coreProtect();
        if (!cp.isAvailable()) {
            return;
        }
        if (!cfg.btnHint.isEmpty()) {
            Msg.send(sender, cfg.btnHint, "player", playerName, "minutes", minutes, "radius", radius);
        }
        List<net.kyori.adventure.text.Component> row = new ArrayList<>();
        if (!cfg.btnChatPlayer.isEmpty()) {
            row.add(Msg.button(cfg.btnChatPlayer, cp.chatSearchPlayer(playerName, minutes), cfg.btnHint));
        }
        if (!cfg.btnChatStart.isEmpty()) {
            row.add(Msg.button(cfg.btnChatStart, cp.chatSearch(playerName, minutes, CoreProtectHook.Kind.START),
                    cfg.btnHint));
        }
        if (!cfg.btnChatAll.isEmpty()) {
            row.add(Msg.button(cfg.btnChatAll, cp.chatSearchAll(minutes), cfg.btnHint));
        }
        if (!cfg.btnBlocks.isEmpty()) {
            row.add(Msg.button(cfg.btnBlocks, cp.blockSearch(playerName, minutes, radius), cfg.btnHint));
        }
        if (!cfg.btnContainer.isEmpty()) {
            row.add(Msg.button(cfg.btnContainer, cp.containerSearch(playerName, minutes, radius), cfg.btnHint));
        }
        if (!row.isEmpty()) {
            net.kyori.adventure.text.Component line = net.kyori.adventure.text.Component.empty();
            for (net.kyori.adventure.text.Component button : row) {
                line = line.append(button).append(net.kyori.adventure.text.Component.space());
            }
            sender.sendMessage(line);
        }
        if (primary != null && !primary.isBlank() && !cfg.btnHint.isEmpty()) {
            Msg.send(sender, "<dark_gray>" + com.sawwik.pingshield.integration.LookupCommands.display(primary));
        }
    }

    /**
     * {@code /pingshield search [минут] [радиус]} — заготовки поиска вокруг того места,
     * где стоит администратор. Радиус в CoreProtect считается от позиции того, кто выполняет
     * команду, поэтому кнопки нужно нажимать уже на месте происшествия.
     */
    private void sendSearch(CommandSender sender, int minutes, int radius) {
        PingShieldConfig cfg = plugin.config();
        CoreProtectHook cp = manager.coreProtect();
        if (!cp.isAvailable()) {
            Msg.send(sender, cfg.cmdCoreProtectUnavailable);
            return;
        }
        String playerName = sender instanceof Player player ? player.getName() : "console";
        Msg.send(sender, cfg.cmdSearchHeader,
                "minutes", minutes,
                "radius", radius,
                "tag", cp.filterPrefix());
        sendLookupButtons(sender, playerName, minutes, radius, null);
        if (!cfg.btnArea.isEmpty()) {
            sender.sendMessage(Msg.button(cfg.btnArea, cp.areaSearch(minutes, radius), cfg.btnHint));
        }
        if (!cfg.btnClick.isEmpty()) {
            sender.sendMessage(Msg.button(cfg.btnClick, cp.clickSearch(minutes, radius), cfg.btnHint));
        }
    }

    /** Отправка сообщения с учётом потока: игроку — через его регион (Folia), консоли — сразу. */
    private void deliver(CommandSender sender, Runnable action) {
        if (sender instanceof Player player) {
            manager.runOn(player, action);
        } else {
            action.run();
        }
    }

    /** Сводка по клиентским версиям онлайна: кто нативный, кто через Via. */
    private void sendVia(CommandSender sender) {
        PingShieldConfig cfg = plugin.config();
        Map<String, Integer> summary = manager.via().onlineSummary(Bukkit.getOnlinePlayers());
        Msg.send(sender, cfg.cmdViaHeader,
                "status", manager.via().statusLine(),
                "protocol", manager.via().getServerProtocol() <= 0 ? "неизвестен"
                        : String.valueOf(manager.via().getServerProtocol()),
                "bonus", cfg.viaEnabled ? cfg.viaExtraThresholdMs : 0);
        if (summary.isEmpty()) {
            Msg.send(sender, cfg.cmdCpEmpty);
            return;
        }
        summary.forEach((key, count) -> Msg.send(sender, cfg.cmdViaEntry, "version", key, "count", count));
    }

    private int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private Player resolveTarget(CommandSender sender, String[] args, PingShieldConfig cfg) {
        if (args.length < 2) {
            Msg.send(sender, cfg.cmdUsage);
            return null;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            Msg.send(sender, cfg.cmdPlayerNotFound, "player", args[1]);
        }
        return target;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission(Permissions.ADMIN)) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(SUBCOMMANDS, args[0]);
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("search")) {
            if (args.length == 2) {
                return filter(List.of("5", "10", "30", "60", "180"), args[1]);
            }
            if (args.length == 3) {
                return filter(List.of("1", "3", "5", "10"), args[2]);
            }
        }
        if (args.length >= 2 && (args[0].equalsIgnoreCase("cp") || args[0].equalsIgnoreCase("coreprotect"))) {
            if (args.length == 2) {
                List<String> names = new ArrayList<>();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    names.add(player.getName());
                }
                return filter(names, args[1]);
            }
            if (args.length == 3) {
                return filter(List.of("5", "10", "30", "60"), args[2]);
            }
            if (args.length == 4) {
                return filter(List.of("1", "3", "5"), args[3]);
            }
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("check")
                || args[0].equalsIgnoreCase("protect")
                || args[0].equalsIgnoreCase("unprotect")
                || args[0].equalsIgnoreCase("profile")
                || args[0].equalsIgnoreCase("perms")
                || args[0].equalsIgnoreCase("permissions"))) {
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                names.add(player.getName());
            }
            return filter(names, args[1]);
        }
        return List.of();
    }

    private List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }
}
